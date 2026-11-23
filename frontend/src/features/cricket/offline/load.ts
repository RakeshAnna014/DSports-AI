import { SUPPORTED_ENGINE_VERSIONS, logFrom, parseCommand, stateFingerprint, type LogEntry, type ScoreCommand } from '../engine';
import { chainLog, chainOf, queueLevel } from './queue';
import { selfCheck } from './selfCheck';
import type { QueueStorage } from './storage';
import { isInChain, type JournalEntry, type Mutation, type OfflineScoring, type QueueItem, type QueueState, type SetAsideReason } from './types';

/**
 * Rebuilds a match's queue from what is on the phone. Nothing is ever dropped here: a row that cannot be read becomes a set-aside item that keeps the raw
 * row, an item of another account is shown as set aside (and comes back untouched when that account signs in), and an item that was in flight when the page
 * went away waits again with its id. What has to be written back (an item that no longer fits the saved log) is returned as mutations for the caller to commit.
 */
export type LoadResult =
  | { ok: true; state: QueueState; mutations: Mutation[]; notes: string[] }
  | { ok: false; reason: 'NO_ENGINE_STATE' | 'LOG_UNREADABLE'; items: unknown[] };

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isCount = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;

/** A stored row as an item, or null when the row is not one. The command is re-read through the same parser the server request uses. */
export function reviveItem(row: unknown): QueueItem | null {
  if (!isRecord(row)) {
    return null;
  }
  const okText = ['matchId', 'userId', 'clientEventId', 'label', 'tappedAt'].every((k) => typeof row[k] === 'string');
  if (!okText || !isCount(row.n) || !isCount(row.expectedSeq) || !isCount(row.attempts) || !['COMMAND', 'UNDO'].includes(String(row.kind)) || !['WAITING', 'IN_FLIGHT', 'SET_ASIDE'].includes(String(row.status))) {
    return null;
  }
  let command: ScoreCommand | null = null;
  if (row.kind === 'COMMAND') {
    try {
      command = parseCommand(row.command);
    } catch {
      return null;
    }
  } else if (row.mode !== 'BALL' && row.mode !== 'COMMAND') {
    return null;
  }
  return {
    matchId: row.matchId as string,
    userId: row.userId as string,
    n: row.n,
    kind: row.kind as 'COMMAND' | 'UNDO',
    clientEventId: row.clientEventId as string,
    expectedSeq: row.expectedSeq,
    command,
    mode: row.kind === 'UNDO' ? (row.mode as 'BALL' | 'COMMAND') : null,
    label: row.label as string,
    tappedAt: row.tappedAt as string,
    status: row.status as QueueItem['status'],
    reason: typeof row.reason === 'string' ? (row.reason as SetAsideReason) : null,
    detail: typeof row.detail === 'string' ? row.detail : null,
    attempts: row.attempts,
  };
}

/** Counts from the journal, which is the record conservation is checked against. */
export function countsFrom(journal: readonly JournalEntry[]) {
  return {
    enqueued: journal.filter((j) => j.event === 'enqueued').length,
    confirmed: journal.filter((j) => j.event === 'confirmed' || j.event === 'replayed').length,
    discarded: journal.filter((j) => j.event === 'discarded').length,
  };
}

export async function loadQueue(storage: QueueStorage, who: { matchId: string; userId: string }, now: string): Promise<LoadResult> {
  const rows = await storage.items(who.matchId);
  const record = await storage.engineState(who.matchId);
  if (record === undefined) {
    return { ok: false, reason: 'NO_ENGINE_STATE', items: rows.map((r) => r.row) };
  }
  const entries: LogEntry[] = [...record.entries, ...(await storage.tail(who.matchId)).filter((e) => e.seq > record.seq)];
  if (!entries.every((e, i) => e.seq === i + 1)) {
    return { ok: false, reason: 'LOG_UNREADABLE', items: rows.map((r) => r.row) };
  }
  let base;
  try {
    base = logFrom(record.setup, entries);
  } catch {
    return { ok: false, reason: 'LOG_UNREADABLE', items: rows.map((r) => r.row) };
  }

  const notes: string[] = [];
  const mutations: Mutation[] = [];
  const loaded = rows.map(({ n, row }) => {
    const item = reviveItem(row);
    if (item !== null) {
      return { ...item, n };
    }
    notes.push(`row ${n} could not be read and is kept as it was`);
    const placeholder: QueueItem = {
      matchId: who.matchId,
      userId: who.userId,
      n,
      kind: 'COMMAND',
      clientEventId: `unreadable-${n}`,
      expectedSeq: 0,
      command: null,
      mode: null,
      label: 'An unreadable saved change',
      tappedAt: '',
      status: 'SET_ASIDE',
      reason: 'CORRUPTED',
      detail: JSON.stringify(row).slice(0, 500),
      attempts: 0,
    };
    return placeholder;
  });

  // Who may send what: another account's items are shown as set aside and left exactly as they are in storage.
  let items: QueueItem[] = loaded.map((i) => {
    if (i.reason === 'CORRUPTED' && i.command === null && i.status === 'SET_ASIDE') {
      return i;
    }
    if (i.userId !== who.userId) {
      return { ...i, status: 'SET_ASIDE' as const, reason: 'OTHER_ACCOUNT' as const, detail: 'saved by another account on this phone' };
    }
    return i.status === 'IN_FLIGHT' ? { ...i, status: 'WAITING' as const } : i;
  });

  // The chain must fit the saved log. An item that does not fit is set aside (never dropped), together with the rest after it.
  const mine = chainOf(items);
  const fit = chainLog(base, mine);
  const local = fit.log;
  if (fit.refusal !== null) {
    const refusal = fit.refusal;
    const from = mine.findIndex((i) => i.n === refusal.n);
    const stuck = new Set(mine.slice(from).map((i) => i.n));
    items = items.map((i) => {
      if (!stuck.has(i.n)) {
        return i;
      }
      const aside: QueueItem = { ...i, status: 'SET_ASIDE', reason: 'CORRUPTED', detail: `no longer fits the saved log: ${refusal.message}` };
      mutations.push({ kind: 'putItem', item: aside }, { kind: 'journal', entry: { matchId: who.matchId, at: now, event: 'parked', n: i.n, clientEventId: i.clientEventId, label: i.label, detail: aside.detail } });
      return aside;
    });
    notes.push(`${stuck.size} saved change(s) no longer fit the saved log and were set aside`);
  }
  // The chain's seqs must follow the confirmed log without gaps.
  let position = 0;
  items = items.map((i) => {
    if (!isInChain(i)) {
      return i;
    }
    const expectedSeq = entries.length + position;
    position++;
    if (i.expectedSeq === expectedSeq) {
      return i;
    }
    const moved = { ...i, expectedSeq };
    mutations.push({ kind: 'putItem', item: moved });
    return moved;
  });

  // The saved fingerprint is the server's for the log as it was saved (up to record.seq), so that is the log to check; what was confirmed since is the server's own 200s.
  const offline: OfflineScoring = selfCheck({ setup: record.setup, entries: record.entries, engineVersion: record.engineVersion, stateFingerprint: record.stateFingerprint });
  if (!offline.on) {
    notes.push(`offline scoring is off: ${offline.reason}`);
  }
  if (!SUPPORTED_ENGINE_VERSIONS.includes(record.engineVersion)) {
    notes.push(`this page does not know engine version ${record.engineVersion}`);
  }
  const journal = await storage.journal(who.matchId);
  const state: QueueState = {
    matchId: who.matchId,
    userId: who.userId,
    setup: record.setup,
    engineVersion: record.engineVersion,
    confirmedEntries: entries,
    confirmedSeq: entries.length,
    items,
    // From the keys, so a row that cannot be read still keeps its place and can never be overwritten by a new item.
    nextN: rows.reduce<number>((max, r) => Math.max(max, r.n + 1), 1),
    counts: countsFrom(journal),
    offline,
    blocked: null,
    local,
  };
  if (queueLevel(state) === 'FULL') {
    notes.push('the queue is full');
  }
  return { ok: true, state, mutations, notes };
}

/** A fresh queue for a match that has none: the screen opened online and read the engine state (2f-3), nothing waits. */
export function newQueueState(init: { matchId: string; userId: string; setup: QueueState['setup']; entries: LogEntry[]; engineVersion: number; stateFingerprint: string }): QueueState {
  return {
    matchId: init.matchId,
    userId: init.userId,
    setup: init.setup,
    engineVersion: init.engineVersion,
    confirmedEntries: init.entries,
    confirmedSeq: init.entries.length,
    items: [],
    nextN: 1,
    counts: { enqueued: 0, confirmed: 0, discarded: 0 },
    offline: selfCheck({ setup: init.setup, entries: init.entries, engineVersion: init.engineVersion, stateFingerprint: init.stateFingerprint }),
    blocked: null,
    local: logFrom(init.setup, init.entries),
  };
}

/**
 * The write that makes a match reloadable: the server's log as the phone has it. The screen commits it when it opens online (and `syncWithServer` includes it
 * every time the phone re-reads the server's log), so that a reload can rebuild the queue even if the phone has been offline ever since.
 */
export function saveEngineState(state: QueueState, now: string): Mutation {
  return {
    kind: 'putEngineState',
    record: {
      matchId: state.matchId,
      userId: state.userId,
      setup: state.setup,
      entries: state.confirmedEntries,
      seq: state.confirmedSeq,
      engineVersion: state.engineVersion,
      stateFingerprint: stateFingerprint(logFrom(state.setup, state.confirmedEntries)),
      savedAt: now,
    },
  };
}
