import { logFrom, stepCommand, stepUndo, type ScoreCommand, type ScoringLog, type UndoMode } from '../engine';
import { saveEngineStateRecord as saveEngineState } from './engineRecord';
import { QUEUE_CAP, isInChain, type Counts, type DisplayData, type JournalEvent, type Mutation, type OfflineScoring, type QueueItem, type QueueState, type Refusal, type ServerView, type SetAsideReason, type Step } from './types';

/**
 * The offline queue as pure functions (gate 2f-2). Every function takes the state and returns `{ state, mutations, refused }`: the new state, what must be
 * written to storage (committed in one transaction by the caller BEFORE the new state is shown), and, when a tap could not be taken, why. Nothing here reads a
 * clock, makes an id, touches storage or the network, so the whole machine is tested without a browser.
 *
 * The rules it keeps (they are the tests of `queue.conservation.test.ts`):
 *  1. An item leaves the queue only by a 200 from the server (`confirm`) or by a scorer's confirmed discard of a SET_ASIDE item (`discard`). Nothing else deletes.
 *  2. enqueued = confirmed + discarded + items still held, always.
 *  3. The chain (WAITING and IN_FLIGHT items) is contiguous in seq: item i has expectedSeq = confirmedSeq + i + 1.
 *  4. A tap the local engine refuses is never queued.
 */

type Now = string;

const unchanged = (state: QueueState, refused: Refusal | null = null): Step => ({ state, mutations: [], refused });

export const chainOf = (items: readonly QueueItem[]): QueueItem[] => items.filter(isInChain);
export const queueLevel = (state: QueueState): 'OK' | 'WARN' | 'FULL' => (state.items.length >= QUEUE_CAP ? 'FULL' : state.items.length >= 150 ? 'WARN' : 'OK');

/** "1 run", "Wide", "Wicket (bowled)" ... the words the scorer sees for an item. Names are not used: a label never depends on who the players are. */
export function describeCommand(command: ScoreCommand): string {
  switch (command.type) {
    case 'DELIVER': {
      const d = command.delivery;
      const out = d.dismissal === null ? '' : ` + wicket (${d.dismissal.type.toLowerCase().replace(/_/g, ' ')})`;
      const runs = (n: number) => (n === 1 ? '1 run' : `${n} runs`);
      if (d.kind === 'WIDE') {
        return (d.runs > 0 ? `Wide + ${runs(d.runs)}` : 'Wide') + out;
      }
      const extra = d.runsType === 'BYE' ? 'bye' : d.runsType === 'LEG_BYE' ? 'leg bye' : '';
      const base = d.kind === 'NO_BALL' ? 'No ball' : d.runs === 0 ? 'Dot ball' : '';
      const scored = d.runs === 0 && base !== '' ? '' : d.boundary ? `${d.runs} (boundary)` : extra !== '' ? `${runs(d.runs)} as ${extra}` : runs(d.runs);
      return [base, d.kind === 'NO_BALL' && d.runs > 0 ? `+ ${scored}` : scored].filter((x) => x !== '').join(' ') + out;
    }
    case 'SELECT_BATTER':
      return 'Next batter';
    case 'SELECT_BOWLER':
      return 'Next bowler';
    case 'CHANGE_BOWLER':
      return 'Change bowler mid-over';
    case 'RETIRE_BATTER':
      return command.retirement === 'HURT' ? 'Batter retired hurt' : 'Batter retired out';
    case 'REVISE_TARGET':
      return `Revised target ${command.target}`;
    case 'END_INNINGS':
      return `End innings (${command.reason.toLowerCase().replace(/_/g, ' ')})`;
    case 'END_MATCH':
      return `End match (${command.reason.toLowerCase().replace(/_/g, ' ')})`;
    case 'START_INNINGS':
      return 'Start innings';
  }
}

export const describeUndo = (mode: UndoMode): string => (mode === 'BALL' ? 'Undo last ball' : 'Undo last entry');

const line = (state: Pick<QueueState, 'matchId'>, at: Now, event: JournalEvent, item: QueueItem | null, detail: string | null = null): Mutation => ({
  kind: 'journal',
  entry: { matchId: state.matchId, at, event, n: item?.n ?? null, clientEventId: item?.clientEventId ?? null, label: item?.label ?? null, detail },
});

const putItem = (item: QueueItem): Mutation => ({ kind: 'putItem', item });

/** Applies one item to a log the way the server will. Returns the new log, or the refusal. */
export function applyItem(log: ScoringLog, item: QueueItem): { log: ScoringLog; refusal: { violation: string; message: string } | null } {
  const step = item.kind === 'COMMAND' && item.command !== null ? stepCommand(log, item.command, item.clientEventId) : stepUndo(log, item.mode ?? 'BALL', item.clientEventId);
  return step.result.accepted ? { log: step.log, refusal: null } : { log, refusal: { violation: step.result.violation, message: step.result.message } };
}

/** The confirmed log with the items of the chain applied in order, and how many of them the engine accepts. */
export function chainLog(base: ScoringLog, chain: readonly QueueItem[]): { log: ScoringLog; applied: number; refusal: { n: number; violation: string; message: string } | null } {
  let log = base;
  for (let i = 0; i < chain.length; i++) {
    const next = applyItem(log, chain[i]);
    if (next.refusal !== null) {
      return { log, applied: i, refusal: { n: chain[i].n, ...next.refusal } };
    }
    log = next.log;
  }
  return { log, applied: chain.length, refusal: null };
}

const baseLog = (state: Pick<QueueState, 'setup' | 'confirmedEntries'>): ScoringLog => logFrom(state.setup, state.confirmedEntries);

/** Gives the chain its seqs again: item i (from 0) expects confirmedSeq + i + 1. Returns only the items that changed. */
function rechain(items: QueueItem[], confirmedSeq: number): { items: QueueItem[]; changed: QueueItem[] } {
  let position = 0;
  const changed: QueueItem[] = [];
  const next = items.map((item) => {
    if (!isInChain(item)) {
      return item;
    }
    const expectedSeq = confirmedSeq + position;
    position++;
    if (item.expectedSeq === expectedSeq) {
      return item;
    }
    const moved = { ...item, expectedSeq };
    changed.push(moved);
    return moved;
  });
  return { items: next, changed };
}

const setAside = (item: QueueItem, reason: SetAsideReason, detail: string | null): QueueItem => ({ ...item, status: 'SET_ASIDE', reason, detail });

// ---- a tap ---------------------------------------------------------------------------------------------------------------

export type TapInput = { kind: 'COMMAND'; command: ScoreCommand } | { kind: 'UNDO'; mode: UndoMode };

/**
 * Takes a scorer's tap. It is refused (and nothing changes) when offline scoring is off, when somebody has to decide about a conflict first, when the queue is
 * full (600), or when the local engine says the rules forbid it. Otherwise the item joins the end of the chain with the id it keeps for life.
 */
export function tap(state: QueueState, input: TapInput, ctx: { now: Now; newId: () => string }): Step {
  if (!state.offline.on) {
    return unchanged(state, { code: 'OFFLINE_SCORING_OFF', message: 'Offline scoring is off on this phone. Send what is waiting, then score one ball at a time while online.', violation: null });
  }
  if (state.blocked !== null) {
    return unchanged(state, { code: 'BLOCKED', message: 'Decide what to do with the waiting balls first.', violation: null });
  }
  if (state.items.length >= QUEUE_CAP) {
    return unchanged(state, {
      code: 'QUEUE_FULL',
      message: `This phone is holding ${QUEUE_CAP} unsent changes, the most it will keep. Get a signal so they can be sent, then carry on. Nothing already scored is lost.`,
      violation: null,
    });
  }
  const clientEventId = ctx.newId();
  const step = input.kind === 'COMMAND' ? stepCommand(state.local, input.command, clientEventId) : stepUndo(state.local, input.mode, clientEventId);
  if (!step.result.accepted) {
    return unchanged(state, {
      code: step.result.violation === 'NOTHING_TO_UNDO' ? 'NOTHING_TO_UNDO' : 'RULE',
      message: step.result.message,
      violation: step.result.violation,
    });
  }
  const item: QueueItem = {
    matchId: state.matchId,
    userId: state.userId,
    n: state.nextN,
    kind: input.kind,
    clientEventId,
    // The server's expectedSeq is the last seq the scorer saw, so one less than the seq this item will get.
    expectedSeq: step.result.seq - 1,
    command: input.kind === 'COMMAND' ? input.command : null,
    mode: input.kind === 'UNDO' ? input.mode : null,
    label: input.kind === 'COMMAND' ? describeCommand(input.command) : describeUndo(input.mode),
    tappedAt: ctx.now,
    status: 'WAITING',
    reason: null,
    detail: null,
    attempts: 0,
  };
  const local = step.log;
  return {
    state: { ...state, items: [...state.items, item], nextN: state.nextN + 1, counts: { ...state.counts, enqueued: state.counts.enqueued + 1 }, local },
    mutations: [putItem(item), line(state, ctx.now, 'enqueued', item)],
    refused: null,
  };
}

// ---- sending ---------------------------------------------------------------------------------------------------------------

/** The item the sender may send now: the first of the chain, when nothing is in flight and nobody has to decide anything. */
export function nextToSend(state: QueueState): QueueItem | null {
  if (state.blocked !== null) {
    return null;
  }
  const chain = chainOf(state.items);
  return chain.length > 0 && chain[0].status === 'WAITING' ? chain[0] : null;
}

const mustBeFirstInFlight = (state: QueueState, n: number): QueueItem => {
  const chain = chainOf(state.items);
  if (chain.length === 0 || chain[0].n !== n || chain[0].status !== 'IN_FLIGHT') {
    throw new Error(`Item ${n} is not the one in flight (the sender sends one at a time, in order)`);
  }
  return chain[0];
};

const replaceItem = (items: readonly QueueItem[], next: QueueItem): QueueItem[] => items.map((i) => (i.n === next.n ? next : i));

/** The sender is about to send item `n`. It is saved as in flight (with the attempt counted) so a reload knows. */
export function sendStarted(state: QueueState, n: number, now: Now): Step {
  const first = nextToSend(state);
  if (first === null || first.n !== n) {
    throw new Error(`Item ${n} cannot be sent now`);
  }
  const item = { ...first, status: 'IN_FLIGHT' as const, attempts: first.attempts + 1 };
  return { state: { ...state, items: replaceItem(state.items, item) }, mutations: [putItem(item), line(state, now, 'sending', item, `attempt ${item.attempts}`)], refused: null };
}

/**
 * The server answered 200 for item `n` with this seq (`replayed` when it had already applied the item: a lost answer). The item leaves the queue and its entry
 * joins the confirmed log. A seq that is not the one predicted means the two engines have disagreed: the item is still confirmed (the server has it) and
 * offline scoring is switched off until the phone has re-read the server's log.
 */
export function confirm(state: QueueState, n: number, serverSeq: number, replayed: boolean, now: Now): Step {
  const item = mustBeFirstInFlight(state, n);
  const entry = state.local.entries[state.confirmedEntries.length];
  if (entry === undefined || entry.seq !== item.expectedSeq + 1) {
    throw new Error(`The local log has no entry for item ${n}`);
  }
  const mismatch = serverSeq !== item.expectedSeq + 1;
  const offline: OfflineScoring = mismatch ? { on: false, reason: 'ENGINE_MISMATCH' } : state.offline;
  const detail = mismatch ? `the server gave seq ${serverSeq}, the phone expected ${item.expectedSeq + 1}` : replayed ? 'already on the server' : null;
  const mutations: Mutation[] = [
    { kind: 'deleteItem', matchId: state.matchId, n },
    { kind: 'appendConfirmed', matchId: state.matchId, entry },
    line(state, now, replayed ? 'replayed' : 'confirmed', item, detail),
  ];
  if (mismatch && state.offline.on) {
    mutations.push(line(state, now, 'engineOff', null, detail));
  }
  return {
    state: {
      ...state,
      items: state.items.filter((i) => i.n !== n),
      confirmedEntries: [...state.confirmedEntries, entry],
      confirmedSeq: Math.max(state.confirmedSeq, entry.seq),
      counts: { ...state.counts, confirmed: state.counts.confirmed + 1 },
      offline,
    },
    mutations,
    refused: null,
  };
}

/** The send failed in a way that says nothing about the item (no network, timeout, 502 to 504): it goes back to waiting and keeps its id. */
export function sendFailed(state: QueueState, n: number, detail: string): Step {
  const item = mustBeFirstInFlight(state, n);
  const waiting = { ...item, status: 'WAITING' as const, detail };
  return { state: { ...state, items: replaceItem(state.items, waiting) }, mutations: [putItem(waiting)], refused: null };
}

// ---- the server's log differs from ours ------------------------------------------------------------------------------------------

function ownIds(state: QueueState): Set<string> {
  return new Set(chainOf(state.items).map((i) => i.clientEventId));
}

/**
 * Takes the server's log as the truth (after a 409, or when the screen opens). Items the server already applied (their client event id is in its log) are
 * confirmed. If entries by somebody else came in, the rest of the chain is held until the scorer decides (`blocked.kind === 'CONFLICT'`, `rebase` or
 * `keepTheirs`); if there are none, the chain simply gets its seqs again and carries on.
 */
export function syncWithServer(state: QueueState, view: ServerView, now: Now, inFlight: number | null = null): Step {
  const mine = ownIds(state);
  const mutations: Mutation[] = [];
  let counts: Counts = state.counts;
  let items = state.items;
  const known = new Set(state.confirmedEntries.map((e) => e.seq));
  for (const item of chainOf(state.items)) {
    const applied = view.entries.some((e) => e.clientEventId === item.clientEventId && mine.has(item.clientEventId));
    if (applied) {
      items = items.filter((i) => i.n !== item.n);
      counts = { ...counts, confirmed: counts.confirmed + 1 };
      mutations.push({ kind: 'deleteItem', matchId: state.matchId, n: item.n }, line(state, now, 'replayed', item, 'already on the server'));
    }
  }
  // An administrator's unlock takes a seq of its own but changes nothing about the score, so it never needs the scorer's decision: the chain just gets its seqs again.
  const foreign = view.entries.filter((e) => e.seq > state.confirmedSeq && !known.has(e.seq) && e.kind !== 'ADMIN_UNLOCK' && !(e.clientEventId !== null && mine.has(e.clientEventId)));
  mutations.push({
    kind: 'putEngineState',
    record: { matchId: state.matchId, userId: state.userId, setup: state.setup, entries: view.entries, seq: view.seq, engineVersion: view.engineVersion, stateFingerprint: view.stateFingerprint, savedAt: now, ...(state.display === null ? {} : { display: state.display }) },
  });
  // The in-flight item (if the sync follows a 409 for it) goes back to waiting: the server did not apply it.
  items = items.map((i) => (i.status === 'IN_FLIGHT' && i.n === inFlight ? { ...i, status: 'WAITING' as const } : i));
  const remaining = chainOf(items);
  const synced: QueueState = { ...state, items, counts, confirmedEntries: view.entries, confirmedSeq: view.seq, engineVersion: view.engineVersion };
  const alreadyDeciding = state.blocked?.kind === 'CONFLICT';
  if (remaining.length > 0 && (foreign.length > 0 || alreadyDeciding)) {
    // Somebody else's entries are in (or the scorer has not decided yet): hold the chain, show the server's log, and let the scorer choose.
    if (!alreadyDeciding) {
      mutations.push(line(state, now, 'conflict', null, `${foreign.length} change(s) by somebody else, now at seq ${view.seq}`));
    }
    return { state: { ...synced, blocked: { kind: 'CONFLICT', view }, local: baseLog(synced) }, mutations, refused: null };
  }
  const moved = rechain(items, view.seq);
  const rebuilt = { ...synced, items: moved.items };
  const chained = chainLog(baseLog(rebuilt), chainOf(rebuilt.items));
  if (chained.refusal !== null) {
    // The chain no longer fits the server's log even though nobody else's entries are in it: the scorer has to look, as in any conflict.
    mutations.push(line(state, now, 'conflict', null, `a waiting ball no longer fits: ${chained.refusal.message}`));
    return { state: { ...synced, blocked: { kind: 'CONFLICT', view }, local: baseLog(synced) }, mutations, refused: null };
  }
  moved.changed.forEach((i) => mutations.push(putItem(i)));
  return { state: { ...rebuilt, blocked: state.blocked, local: chained.log }, mutations, refused: null };
}

/** What adding the waiting balls after the other scorer's would give, ball by ball. Only a preview: the server checks every ball again when it is sent. */
export function previewRebase(state: QueueState): { total: number; applicable: number; refusal: { n: number; violation: string; message: string } | null; log: ScoringLog } {
  const chain = chainOf(state.items);
  const result = chainLog(baseLog(state), chain);
  return { total: chain.length, applicable: result.applied, refusal: result.refusal, log: result.log };
}

/** The scorer chose "add my balls after theirs": the ones that apply keep their ids and get new seqs; the rest are set aside. The server still checks each. */
export function rebase(state: QueueState, now: Now): Step {
  if (state.blocked?.kind !== 'CONFLICT') {
    throw new Error('There is no conflict to rebase');
  }
  const preview = previewRebase(state);
  const chain = chainOf(state.items);
  const mutations: Mutation[] = [];
  let position = 0;
  const items = state.items.map((item) => {
    if (!isInChain(item)) {
      return item;
    }
    const index = chain.findIndex((c) => c.n === item.n);
    let next: QueueItem;
    if (index < preview.applicable) {
      next = { ...item, status: 'WAITING', expectedSeq: state.confirmedSeq + position };
      position++;
      mutations.push(putItem(next), line(state, now, 'rebased', next, `now follows seq ${next.expectedSeq}`));
    } else {
      next = setAside(item, 'CONFLICT_NOT_APPLICABLE', index === preview.applicable && preview.refusal !== null ? preview.refusal.message : 'an earlier ball cannot be added');
      mutations.push(putItem(next), line(state, now, 'parked', next, next.detail));
    }
    return next;
  });
  return { state: { ...state, items, blocked: null, local: preview.log }, mutations, refused: null };
}

/** The scorer chose to keep the other scorer's version: every waiting ball is set aside (saved, not in the match). */
export function keepTheirs(state: QueueState, now: Now): Step {
  if (state.blocked?.kind !== 'CONFLICT') {
    throw new Error('There is no conflict to settle');
  }
  return setAsideAll(state, 'CONFLICT_KEPT_THEIRS', 'you chose the other scorer\'s version', now, true);
}

function setAsideAll(state: QueueState, reason: SetAsideReason, detail: string | null, now: Now, clearBlock: boolean, fromN: number | null = null): Step {
  const mutations: Mutation[] = [];
  const items = state.items.map((item) => {
    if (!isInChain(item) || (fromN !== null && item.n < fromN)) {
      return item;
    }
    const next = setAside(item, reason, detail);
    mutations.push(putItem(next), line(state, now, 'parked', next, `${reason}${detail ? `: ${detail}` : ''}`));
    return next;
  });
  const chained = chainLog(baseLog(state), chainOf(items));
  return { state: { ...state, items, blocked: clearBlock ? null : state.blocked, local: chained.log }, mutations, refused: null };
}

// ---- the server said no ----------------------------------------------------------------------------------------------------------

/** 422: the server's engine refused item `n` although its seq was right. The two engines disagree (or the phone's log was wrong): stop, say so, keep everything. */
export function rejectedByServer(state: QueueState, n: number, violation: string, message: string, now: Now): Step {
  const item = mustBeFirstInFlight(state, n);
  const waiting = { ...item, status: 'WAITING' as const, detail: `${violation}: ${message}` };
  return {
    state: { ...state, items: replaceItem(state.items, waiting), blocked: { kind: 'REJECTED', n, violation, message }, offline: { on: false, reason: 'ENGINE_MISMATCH' } },
    mutations: [putItem(waiting), line(state, now, 'rejected', waiting, `${violation}: ${message}`), line(state, now, 'engineOff', null, 'the server refused a ball the phone accepted')],
    refused: null,
  };
}

/** 409 MATCH_LOCKED: the correction window closed before item `n` arrived. It and everything after it are set aside; the ones before it are in. */
export function locked(state: QueueState, n: number, editableUntil: string | null, now: Now): Step {
  mustBeFirstInFlight(state, n);
  const step = setAsideAll(state, 'LOCKED', editableUntil === null ? 'the correction window has closed' : `the correction window closed at ${editableUntil}`, now, false, n);
  return { ...step, state: { ...step.state, blocked: { kind: 'LOCKED', editableUntil } } };
}

/** 403 or 404: no longer allowed to score this match, or it is gone. Everything waiting is set aside with the reason. */
export function forbidden(state: QueueState, now: Now): Step {
  const step = setAsideAll(state, 'FORBIDDEN', 'you can no longer score this match, or it no longer exists', now, false);
  return { ...step, state: { ...step.state, blocked: { kind: 'FORBIDDEN' } } };
}

/** The scorer sets aside item `n` and everything after it (the "set aside this and the rest" choice after a refusal). */
export function setAsideFrom(state: QueueState, n: number, reason: SetAsideReason, now: Now): Step {
  const step = setAsideAll(state, reason, null, now, true, n);
  return step;
}

const RESENDABLE: readonly SetAsideReason[] = ['LOCKED', 'FORBIDDEN', 'REJECTED_BY_SERVER', 'BLOCKED_BY_EARLIER', 'SCORER_CHOICE', 'CONFLICT_KEPT_THEIRS', 'CONFLICT_NOT_APPLICABLE'];

/**
 * "Send again": the set-aside items whose reason can have gone (an administrator unlocked the match, the scorer was added back) return to the chain, in their
 * original order, with the same ids and new seqs. The engine checks each against the log first, so one that no longer applies stays aside with the reason.
 */
export function sendAgain(state: QueueState, now: Now): Step {
  const candidates = state.items.filter((i) => i.status === 'SET_ASIDE' && i.userId === state.userId && i.reason !== null && RESENDABLE.includes(i.reason));
  if (candidates.length === 0) {
    return unchanged(state);
  }
  const back = new Set(candidates.map((i) => i.n));
  const woken = state.items.map((i) => (back.has(i.n) ? { ...i, status: 'WAITING' as const, reason: null, detail: null } : i));
  const moved = rechain(woken, state.confirmedSeq);
  const chain = chainOf(moved.items);
  const result = chainLog(baseLog(state), chain);
  const mutations: Mutation[] = [];
  const items = moved.items.map((i) => {
    const idx = chain.findIndex((c) => c.n === i.n);
    if (idx < 0) {
      return i;
    }
    if (idx >= result.applied) {
      const aside = setAside(i, 'CONFLICT_NOT_APPLICABLE', idx === result.applied && result.refusal !== null ? result.refusal.message : 'an earlier ball cannot be added');
      mutations.push(putItem(aside), line(state, now, 'parked', aside, aside.detail));
      return aside;
    }
    if (back.has(i.n)) {
      mutations.push(putItem(i), line(state, now, 'rebased', i, 'sent again'));
    } else if (moved.changed.some((c) => c.n === i.n)) {
      mutations.push(putItem(i));
    }
    return i;
  });
  return { state: { ...state, items, blocked: null, local: result.log }, mutations, refused: null };
}

/** The scorer discards a set-aside item after confirming it by name. Nothing else can delete an unsent item. */
export function discard(state: QueueState, n: number, now: Now): Step {
  const item = state.items.find((i) => i.n === n);
  if (item === undefined || item.status !== 'SET_ASIDE') {
    throw new Error(`Item ${n} is not set aside, so it cannot be discarded`);
  }
  return {
    state: { ...state, items: state.items.filter((i) => i.n !== n), counts: { ...state.counts, discarded: state.counts.discarded + 1 } },
    mutations: [{ kind: 'deleteItem', matchId: state.matchId, n }, line(state, now, 'discarded', item, item.reason)],
    refused: null,
  };
}

/** The names and the correction window as the server last said them, saved with the engine state (display only). */
export function setDisplay(state: QueueState, display: DisplayData, now: Now): Step {
  const record = saveEngineState(state, now);
  return { state: { ...state, display }, mutations: [{ kind: 'putEngineState', record: { ...record, display } }], refused: null };
}

// ---- self-check ----------------------------------------------------------------------------------------------------------------

/** Switches offline scoring on or off (the self-check, section 7.2). Turning it off never touches an item. */
export function setOfflineScoring(state: QueueState, offline: OfflineScoring, now: Now): Step {
  const changed = state.offline.on !== offline.on || (!state.offline.on && !offline.on && state.offline.reason !== offline.reason);
  if (!changed) {
    return unchanged(state);
  }
  return { state: { ...state, offline }, mutations: [line(state, now, offline.on ? 'engineOn' : 'engineOff', null, offline.on ? null : offline.reason)], refused: null };
}
