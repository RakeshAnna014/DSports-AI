import * as queue from './queue';
import { parseEngineState, EngineStateFormatError, toServerView } from './engineStateApi';
import { selfCheck } from './selfCheck';
import { SendError, type SendFailure } from './sendErrors';
import type { QueueItem, QueueState, Step } from './types';

/**
 * The sender: takes the first waiting item, sends it, and turns the answer into one queue step (plan sections 3 and 4). It holds no state of its own: everything it
 * decides is committed to storage through `commit` before the next thing happens, so a crash or a reload at any point leaves the queue as the last step left it.
 * The network, the clock, the lease and the token refresh are passed in, so the whole loop is tested with a fake server and no timers.
 */
export interface SenderApi {
  /** Sends one item (its command or undo, with its own client event id, expected seq and tapped time). Resolves with the seq the server gave it. Rejects with a `SendError`. */
  send(item: QueueItem): Promise<{ seq: number; replayed: boolean }>;
  /** The server's setup and log (the engine-state route). Rejects with a `SendError`. */
  engineState(matchId: string): Promise<unknown>;
}

export interface SenderDeps {
  getState(): QueueState;
  /** Writes the step's mutations in one transaction and only then adopts its state. Throws if storage fails (nothing changed then). */
  commit(step: Step): Promise<void>;
  api: SenderApi;
  now(): string;
  /** Only the tab that holds the lease may send (and enqueue). Checked before every send. */
  hasLease(): boolean;
  /** Tries to get a new access token. True when the next request can be made. */
  refreshAuth(): Promise<boolean>;
  sleep(ms: number): Promise<void>;
}

/** Why the sender stopped. */
export type SendStop =
  | { reason: 'IDLE' } // nothing waits
  | { reason: 'BLOCKED'; blocked: NonNullable<QueueState['blocked']>['kind'] } // somebody has to decide
  | { reason: 'RETRY'; afterMs: number | null } // a failure that says nothing about the item: try again (null = the caller's backoff)
  | { reason: 'NEEDS_SIGN_IN' }
  | { reason: 'LEASE_LOST' }
  | { reason: 'STORAGE_FAILED'; message: string };

/** At most about five items a second (plan 3.1), so a flush of 600 never looks like an attack to a limiter. */
export const PACE_MS = 200;

const KINDS_BLOCKING: readonly SendFailure['kind'][] = ['CONFLICT', 'LOCKED', 'REJECTED', 'FORBIDDEN', 'BAD_REQUEST'];
export const isBlockingFailure = (f: SendFailure): boolean => KINDS_BLOCKING.includes(f.kind);

/**
 * Sends items until nothing waits, somebody has to decide, or something says stop. Returns why it stopped. It never sends two at once and never skips an item.
 */
export async function drain(deps: SenderDeps): Promise<SendStop> {
  let refreshed = false;
  for (;;) {
    if (!deps.hasLease()) {
      return { reason: 'LEASE_LOST' };
    }
    const state = deps.getState();
    if (state.blocked !== null) {
      return { reason: 'BLOCKED', blocked: state.blocked.kind };
    }
    const item = queue.nextToSend(state);
    if (item === null) {
      return { reason: 'IDLE' };
    }
    try {
      await deps.commit(queue.sendStarted(state, item.n, deps.now()));
    } catch (e) {
      return { reason: 'STORAGE_FAILED', message: e instanceof Error ? e.message : String(e) };
    }
    let answer: { seq: number; replayed: boolean };
    try {
      answer = await deps.api.send(item);
    } catch (error) {
      const stop = await handleFailure(deps, item, error, refreshed);
      if (stop === 'REFRESHED') {
        refreshed = true;
        continue;
      }
      if (stop !== null) {
        return stop;
      }
      continue;
    }
    refreshed = false;
    try {
      await deps.commit(queue.confirm(deps.getState(), item.n, answer.seq, answer.replayed, deps.now()));
    } catch (e) {
      return { reason: 'STORAGE_FAILED', message: e instanceof Error ? e.message : String(e) };
    }
    await deps.sleep(PACE_MS);
  }
}

/** Turns a failed send into one queue step. Returns null to carry on with the next loop turn, 'REFRESHED' after a token refresh, or why to stop. */
async function handleFailure(deps: SenderDeps, item: QueueItem, error: unknown, alreadyRefreshed: boolean): Promise<SendStop | 'REFRESHED' | null> {
  const failure: SendFailure = error instanceof SendError ? error.failure : { kind: 'NETWORK' };
  const state = () => deps.getState();
  const commit = async (step: Step): Promise<SendStop | null> => {
    try {
      await deps.commit(step);
      return null;
    } catch (e) {
      return { reason: 'STORAGE_FAILED', message: e instanceof Error ? e.message : String(e) };
    }
  };
  switch (failure.kind) {
    case 'NETWORK':
      return (await commit(queue.sendFailed(state(), item.n, 'no network or the server did not answer'))) ?? { reason: 'RETRY', afterMs: null };
    case 'RATE_LIMITED':
      return (await commit(queue.sendFailed(state(), item.n, 'the server asked to slow down'))) ?? { reason: 'RETRY', afterMs: failure.retryAfterMs };
    case 'AUTH': {
      const failed = await commit(queue.sendFailed(state(), item.n, 'sign-in needed'));
      if (failed !== null) {
        return failed;
      }
      if (!alreadyRefreshed && (await deps.refreshAuth())) {
        return 'REFRESHED';
      }
      return { reason: 'NEEDS_SIGN_IN' };
    }
    case 'FORBIDDEN':
      return (await commit(queue.forbidden(withoutInFlight(state(), item.n), deps.now()))) ?? { reason: 'BLOCKED', blocked: 'FORBIDDEN' };
    case 'LOCKED':
      return (await commit(queue.locked(state(), item.n, failure.editableUntil, deps.now()))) ?? { reason: 'BLOCKED', blocked: 'LOCKED' };
    case 'REJECTED':
      return (await commit(queue.rejectedByServer(state(), item.n, failure.violation, failure.message, deps.now()))) ?? { reason: 'BLOCKED', blocked: 'REJECTED' };
    case 'BAD_REQUEST':
      return (await commit(queue.rejectedByServer(state(), item.n, failure.code, failure.message, deps.now()))) ?? { reason: 'BLOCKED', blocked: 'REJECTED' };
    case 'CONFLICT':
      return resync(deps, item);
  }
}

/** The in-flight item goes back to waiting before everything is set aside, so a reload cannot find it "in flight" for a match it can no longer score. */
function withoutInFlight(state: QueueState, n: number): QueueState {
  return { ...state, items: state.items.map((i) => (i.n === n && i.status === 'IN_FLIGHT' ? { ...i, status: 'WAITING' as const } : i)) };
}

/** After a conflict: read the server's log, take it as the truth, and let the queue decide whether the scorer has to choose. */
async function resync(deps: SenderDeps, item: QueueItem): Promise<SendStop | null> {
  let answer;
  try {
    answer = parseEngineState(await deps.api.engineState(deps.getState().matchId));
  } catch (error) {
    if (error instanceof EngineStateFormatError) {
      // The server's answer cannot be read: offline scoring is off for this match, and nothing waiting is touched.
      try {
        await deps.commit(queue.sendFailed(deps.getState(), item.n, 'the server log could not be read'));
        await deps.commit(queue.setOfflineScoring(deps.getState(), { on: false, reason: 'LOG_UNREADABLE' }, deps.now()));
      } catch (e) {
        return { reason: 'STORAGE_FAILED', message: e instanceof Error ? e.message : String(e) };
      }
      return { reason: 'RETRY', afterMs: null };
    }
    try {
      await deps.commit(queue.sendFailed(deps.getState(), item.n, 'no network or the server did not answer'));
    } catch (e) {
      return { reason: 'STORAGE_FAILED', message: e instanceof Error ? e.message : String(e) };
    }
    return { reason: 'RETRY', afterMs: null };
  }
  try {
    await deps.commit(queue.syncWithServer(deps.getState(), toServerView(answer), deps.now(), item.n));
    const check = selfCheck(answer);
    if (!check.on) {
      await deps.commit(queue.setOfflineScoring(deps.getState(), check, deps.now()));
    }
  } catch (e) {
    return { reason: 'STORAGE_FAILED', message: e instanceof Error ? e.message : String(e) };
  }
  return null;
}
