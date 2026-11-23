import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { scoringApi } from '../../../api/cricketScoring';
import type { Match } from '../../../types/cricket';
import type { ScoringView } from '../../../types/cricketScoring';
import { newId } from '../scoring/newId';
import type { ScoreCommand, UndoMode } from '../engine';
import { parseEngineState, EngineStateFormatError } from './engineStateApi';
import { httpSendApi, toSendError } from './httpSendApi';
import { idbAvailable, openIdbStorage } from './idbStorage';
import { loadQueue, newQueueState, saveEngineState } from './load';
import { buildLocalView, type NameBook, type ViewContext } from './localView';
import * as queue from './queue';
import { drain, type SendStop } from './sender';
import { backoffMs } from './sendErrors';
import { selfCheck } from './selfCheck';
import type { QueueStorage } from './storage';
import type { DisplayData, QueueState, Refusal, Step } from './types';

/**
 * The phone side of offline scoring in one hook: it opens the on-phone store, rebuilds (or starts) the queue, keeps the sender running (on every tap, when the
 * network comes back, when the tab becomes visible, and after a back-off), and hands the screen the local view and the actions. It makes no decisions about
 * cricket or about what an answer means: the engine, the queue and the sender do (all tested without a browser).
 *
 * The phone's own copy of the rules is only used while the self-check passes. If IndexedDB is missing, the match is not ready, the server's engine version is not
 * one this page knows, or the self-check fails, the status says why and the screen falls back to scoring one change at a time online, exactly as before.
 */
export type OfflineStatus =
  | { kind: 'loading' }
  | { kind: 'unavailable'; reason: 'NO_INDEXEDDB' | 'STORAGE' | 'NOT_READY' | 'NO_SERVER' | 'NO_USER' }
  | { kind: 'ready' };

export interface SenderStatus {
  /** What the sender is doing now, for the chip on the screen. */
  phase: 'idle' | 'sending' | 'retrying' | 'needs-sign-in' | 'blocked' | 'lease-lost' | 'storage-failed';
  /** When the next attempt is due (epoch ms), while retrying. */
  nextAttemptAt: number | null;
  lastError: string | null;
  failures: number;
}

export interface OfflineScorer {
  status: OfflineStatus;
  state: QueueState | null;
  view: ScoringView | null;
  sender: SenderStatus;
  /** Takes a tap. Resolves with why it was not taken, or null. A storage failure resolves as a refusal (the ball is not shown). */
  tap: (command: ScoreCommand) => Promise<Refusal | null>;
  undo: (mode: UndoMode) => Promise<Refusal | null>;
  retryNow: () => void;
  /** Applies a queue step chosen by the scorer (rebase, keep theirs, send again, discard ...), commits it, and wakes the sender. */
  apply: (make: (state: QueueState, now: string) => Step) => Promise<void>;
  /** Folds in what the server last said about names and the correction window. */
  noteServerView: (view: ScoringView) => void;
  /** Names of the teams and of the players in the two XIs, from the match setup (so the first innings has names before the server has sent a view). */
  noteMatch: (match: Match) => void;
}

const nowIso = (): string => new Date().toISOString();

const displayFrom = (view: ScoringView): DisplayData => {
  const players: Record<string, string> = {};
  const teams: Record<string, string> = {};
  const add = (r: { id: string; name: string } | null | undefined) => {
    if (r) {
      players[r.id] = r.name;
    }
  };
  for (const innings of view.innings) {
    teams[innings.battingTeam.id] = innings.battingTeam.name;
    teams[innings.bowlingTeam.id] = innings.bowlingTeam.name;
    [innings.striker, innings.nonStriker, innings.bowler].forEach(add);
    innings.batters.forEach((b) => add(b.player));
    innings.yetToBat.forEach(add);
    innings.bowlers.forEach((b) => add(b.player));
  }
  view.eligibleBatters.forEach((c) => add(c.player));
  view.bowlers.forEach((c) => add(c.player));
  return { players, teams, editableUntil: view.editableUntil, locked: view.locked };
};

const namesOf = (display: DisplayData | null): NameBook => ({
  player: (id) => display?.players[id] ?? 'Player',
  team: (id) => display?.teams[id] ?? 'Team',
});

export function useOfflineScorer(matchId: string, userId: string | null, owner: boolean, recheck: () => boolean): OfflineScorer {
  // Decided on the first render where it can be (no user, no IndexedDB), so a browser that cannot score offline shows the ordinary screen at once.
  const [status, setStatus] = useState<OfflineStatus>(() =>
    userId === null ? { kind: 'unavailable', reason: 'NO_USER' } : !idbAvailable() ? { kind: 'unavailable', reason: 'NO_INDEXEDDB' } : { kind: 'loading' },
  );
  const [state, setState] = useState<QueueState | null>(null);
  const [sender, setSender] = useState<SenderStatus>({ phase: 'idle', nextAttemptAt: null, lastError: null, failures: 0 });
  const stateRef = useRef<QueueState | null>(null);
  const storageRef = useRef<QueueStorage | null>(null);
  const running = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const failures = useRef(0);
  const ownerRef = useRef(owner);
  const kickRef = useRef<() => void>(() => undefined);
  const lastView = useRef<ScoringView | null>(null);

  useEffect(() => {
    ownerRef.current = owner;
  });

  /** Writes a step's mutations in one transaction and only then shows its state. A refused tap writes nothing. Throws if storage fails (nothing changed). */
  const commit = useCallback(async (step: Step) => {
    if (step.refused !== null) {
      return;
    }
    if (step.mutations.length > 0) {
      await storageRef.current!.commit(step.mutations);
    }
    stateRef.current = step.state;
    setState(step.state);
  }, []);

  const kick = useCallback(() => {
    if (running.current || stateRef.current === null || storageRef.current === null) {
      return;
    }
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    running.current = true;
    setSender((s) => ({ ...s, phase: 'sending', nextAttemptAt: null }));
    void drain({
      getState: () => stateRef.current!,
      commit,
      api: httpSendApi,
      now: nowIso,
      hasLease: () => ownerRef.current && recheck(),
      refreshAuth: async () => {
        // The shared client refreshes an expired access token on its own when a request answers 401; a second 401 right after means the refresh did not work.
        try {
          await scoringApi.engineState(matchId);
          return true;
        } catch {
          return false;
        }
      },
      sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    }).then((stop: SendStop) => {
      running.current = false;
      switch (stop.reason) {
        case 'IDLE':
          failures.current = 0;
          setSender({ phase: 'idle', nextAttemptAt: null, lastError: null, failures: 0 });
          break;
        case 'BLOCKED':
          setSender({ phase: 'blocked', nextAttemptAt: null, lastError: null, failures: failures.current });
          break;
        case 'NEEDS_SIGN_IN':
          setSender({ phase: 'needs-sign-in', nextAttemptAt: null, lastError: 'Sign in again to send the waiting balls.', failures: failures.current });
          break;
        case 'LEASE_LOST':
          setSender({ phase: 'lease-lost', nextAttemptAt: null, lastError: null, failures: failures.current });
          break;
        case 'STORAGE_FAILED':
          setSender({ phase: 'storage-failed', nextAttemptAt: null, lastError: stop.message, failures: failures.current });
          break;
        case 'RETRY': {
          failures.current += 1;
          const wait = stop.afterMs ?? backoffMs(failures.current);
          setSender({ phase: 'retrying', nextAttemptAt: Date.now() + wait, lastError: 'No network, or the server did not answer.', failures: failures.current });
          timer.current = setTimeout(() => kickRef.current(), wait);
          break;
        }
      }
    });
  }, [commit, matchId, recheck]);

  useEffect(() => {
    kickRef.current = kick;
  }, [kick]);

  // ---- open: the store, the queue, the server's log ---------------------------------------------------------------------------

  useEffect(() => {
    let cancelled = false;
    if (userId === null) {
      setStatus({ kind: 'unavailable', reason: 'NO_USER' });
      return;
    }
    if (!idbAvailable()) {
      setStatus({ kind: 'unavailable', reason: 'NO_INDEXEDDB' });
      return;
    }
    (async () => {
      let storage: QueueStorage;
      try {
        storage = await openIdbStorage();
      } catch {
        if (!cancelled) {
          setStatus({ kind: 'unavailable', reason: 'STORAGE' });
        }
        return;
      }
      storageRef.current = storage;
      const loaded = await loadQueue(storage, { matchId, userId }, nowIso());
      let current: QueueState | null = null;
      if (loaded.ok) {
        if (loaded.mutations.length > 0) {
          await storage.commit(loaded.mutations);
        }
        current = loaded.state;
      }
      // The server's log: needed to start, and read again every time the screen opens, so that anything somebody else scored is noticed before the first tap.
      try {
        const answer = parseEngineState(await scoringApi.engineState(matchId));
        if (!answer.ready) {
          if (!cancelled) {
            setStatus({ kind: 'unavailable', reason: 'NOT_READY' });
          }
          return;
        }
        const view = { seq: answer.seq, entries: answer.entries, engineVersion: answer.engineVersion, stateFingerprint: answer.stateFingerprint };
        if (current === null) {
          current = newQueueState({ matchId, userId, setup: answer.setup, entries: answer.entries, engineVersion: answer.engineVersion, stateFingerprint: answer.stateFingerprint });
          await storage.commit([saveEngineState(current, nowIso())]);
        } else {
          const step = queue.syncWithServer(current, view, nowIso());
          await storage.commit(step.mutations);
          current = step.state;
        }
        const check = selfCheck(answer);
        const off = queue.setOfflineScoring(current, check, nowIso());
        await storage.commit(off.mutations);
        current = off.state;
      } catch (error) {
        const unreadable = error instanceof EngineStateFormatError;
        if (current === null) {
          if (!cancelled) {
            setStatus({ kind: 'unavailable', reason: unreadable ? 'NOT_READY' : 'NO_SERVER' });
          }
          return;
        }
        // No signal, but the phone holds this match: carry on from what it holds.
      }
      if (cancelled) {
        return;
      }
      stateRef.current = current;
      setState(current);
      setStatus({ kind: 'ready' });
      kickRef.current();
    })();
    return () => {
      cancelled = true;
      if (timer.current) {
        clearTimeout(timer.current);
      }
    };
  }, [matchId, userId]);

  // ---- wake-ups -------------------------------------------------------------------------------------------------------------------

  useEffect(() => {
    const wake = () => {
      failures.current = 0;
      kickRef.current();
    };
    const visible = () => {
      if (document.visibilityState === 'visible') {
        wake();
      }
    };
    window.addEventListener('online', wake);
    document.addEventListener('visibilitychange', visible);
    return () => {
      window.removeEventListener('online', wake);
      document.removeEventListener('visibilitychange', visible);
    };
  }, []);

  useEffect(() => {
    if (owner) {
      kickRef.current();
    }
  }, [owner]);

  // ---- what the screen does -------------------------------------------------------------------------------------------------------

  const take = useCallback(
    async (make: (s: QueueState) => Step): Promise<Refusal | null> => {
      const current = stateRef.current;
      if (current === null || !recheck()) {
        return { code: 'BLOCKED', message: 'This tab is not the one scoring.', violation: null };
      }
      const step = make(current);
      if (step.refused !== null) {
        return step.refused;
      }
      try {
        await commit(step);
      } catch {
        return { code: 'QUEUE_FULL', message: 'This phone could not save that ball, so it was not counted. Nothing else was lost.', violation: 'STORAGE_FAILED' };
      }
      kickRef.current();
      return null;
    },
    [commit, recheck],
  );

  const tap = useCallback((command: ScoreCommand) => take((s) => queue.tap(s, { kind: 'COMMAND', command }, { now: nowIso(), newId })), [take]);
  const undo = useCallback((mode: UndoMode) => take((s) => queue.tap(s, { kind: 'UNDO', mode }, { now: nowIso(), newId })), [take]);

  const apply = useCallback(
    async (make: (s: QueueState, now: string) => Step) => {
      const current = stateRef.current;
      if (current === null) {
        return;
      }
      try {
        await commit(make(current, nowIso()));
      } catch {
        // Nothing changed; the screen still shows the choice.
        return;
      }
      failures.current = 0;
      kickRef.current();
    },
    [commit],
  );

  const noteServerView = useCallback(
    (view: ScoringView) => {
      lastView.current = view;
      const current = stateRef.current;
      if (current === null) {
        return;
      }
      // Merged, not replaced: a view fetched before the innings started has no names, and must not wipe the ones taken from the match setup.
      const fresh = displayFrom(view);
      const base = current.display;
      const merged: DisplayData = { ...fresh, players: { ...(base?.players ?? {}), ...fresh.players }, teams: { ...(base?.teams ?? {}), ...fresh.teams } };
      if (JSON.stringify(merged) !== JSON.stringify(base)) {
        void commit(queue.setDisplay(current, merged, nowIso())).catch(() => undefined);
      }
    },
    [commit],
  );

  const noteMatch = useCallback(
    (match: Match) => {
      const current = stateRef.current;
      if (current === null) {
        return;
      }
      const base: DisplayData = current.display ?? { players: {}, teams: {}, editableUntil: null, locked: false };
      const players = { ...base.players };
      const teams = { ...base.teams };
      for (const side of [match.teamA, match.teamB]) {
        teams[side.teamId] = side.teamName;
        for (const p of side.playingXi) {
          if (p.name) {
            players[p.playerId] = p.name;
          }
        }
      }
      // Names the server's own view gave win over the setup's (they are the same people; this only fills gaps).
      const merged = { ...base, players: { ...players, ...base.players }, teams: { ...teams, ...base.teams } };
      if (JSON.stringify(merged) !== JSON.stringify(base)) {
        void commit(queue.setDisplay(current, merged, nowIso())).catch(() => undefined);
      }
    },
    [commit],
  );

  const retryNow = useCallback(() => {
    failures.current = 0;
    kickRef.current();
  }, []);

  const view = useMemo(() => {
    if (state === null) {
      return null;
    }
    const ctx: ViewContext = {
      matchId,
      names: namesOf(state.display),
      editableUntil: state.display?.editableUntil ?? null,
      locked: state.display?.locked ?? false,
      eventTimes: new Map(),
      ownLabel: 'You',
    };
    return buildLocalView(state.local, ctx);
  }, [state, matchId]);

  return { status, state, view, sender, tap, undo, retryNow, apply, noteServerView, noteMatch };
}

export { toSendError };
