import type { LogEntry, MatchSetup, ScoreCommand, ScoringLog, UndoMode } from '../engine';

/**
 * The offline queue's data (gate 2f-2). Everything here is plain JSON so it can be stored in IndexedDB as it is, exported as text, and compared in tests.
 * Times are strings the caller passes in (the queue never reads a clock) and ids come from an injected function (plain http has no `crypto.randomUUID`).
 */

/** At most this many items wait on one phone for one match (D4). The next tap is refused, never dropped. */
export const QUEUE_CAP = 600;
/** From here on the screen warns that unsent changes are piling up (D4). */
export const QUEUE_WARN_AT = 150;

export type SetAsideReason =
  | 'CONFLICT_KEPT_THEIRS' // the scorer chose the other scorer's version
  | 'CONFLICT_NOT_APPLICABLE' // a rebase could not add this item after the other balls
  | 'LOCKED' // the 24 hour correction window closed before the item reached the server
  | 'FORBIDDEN' // no longer allowed to score this match, or the match is gone
  | 'REJECTED_BY_SERVER' // the server's engine refused it (the two engines disagreed, or the phone's copy of the log was wrong)
  | 'BLOCKED_BY_EARLIER' // an item before it was set aside
  | 'OTHER_ACCOUNT' // the phone is signed in as somebody else
  | 'CORRUPTED' // the stored row could not be read; kept as it was
  | 'SCORER_CHOICE'; // the scorer set it aside

export type ItemStatus = 'WAITING' | 'IN_FLIGHT' | 'SET_ASIDE';

export interface QueueItem {
  matchId: string;
  userId: string;
  /** A per match counter that is never reused: the storage key and the order. */
  n: number;
  kind: 'COMMAND' | 'UNDO';
  /** Made when the ball was tapped and kept for life: the server recognises a second delivery of the same item by it. */
  clientEventId: string;
  /** What the server calls `expectedSeq`: the last seq the scorer saw, so the item will get seq `expectedSeq + 1`. It is confirmed seq + position (from 0). Changed only by a rebase. */
  expectedSeq: number;
  command: ScoreCommand | null;
  mode: UndoMode | null;
  label: string;
  /** When the scorer tapped (display only, never used by a rule, see D9) and when the queue took it. */
  tappedAt: string;
  status: ItemStatus;
  reason: SetAsideReason | null;
  detail: string | null;
  attempts: number;
}

export type JournalEvent = 'enqueued' | 'sending' | 'confirmed' | 'replayed' | 'conflict' | 'rejected' | 'parked' | 'rebased' | 'discarded' | 'engineOff' | 'engineOn';

/** One line of the append-only history of a match's queue. */
export interface JournalEntry {
  matchId: string;
  at: string;
  event: JournalEvent;
  n: number | null;
  clientEventId: string | null;
  label: string | null;
  detail: string | null;
}

/**
 * What the screen shows that the engine does not know: who the players and teams are (the engine works on ids), and the server's correction window as it last
 * said. Kept beside the engine state so a page reloaded with no signal can still show names. Display only: nothing in a rule reads it.
 */
export interface DisplayData {
  players: Record<string, string>;
  teams: Record<string, string>;
  editableUntil: string | null;
  locked: boolean;
}

/** What the phone needs to score offline (D2.4): the setup and the effective server log, as of one seq. */
export interface EngineStateRecord {
  matchId: string;
  userId: string;
  setup: MatchSetup;
  entries: LogEntry[];
  seq: number;
  engineVersion: number;
  stateFingerprint: string;
  savedAt: string;
  display?: DisplayData;
}

/** What the server tells the phone about the match (the 2f-3 engine-state route, and what a 409 conflict carries). */
export interface ServerView {
  seq: number;
  entries: LogEntry[];
  engineVersion: number;
  stateFingerprint: string;
}

/** One change to storage. A list of them is committed all together or not at all. */
export type Mutation =
  | { kind: 'putItem'; item: QueueItem }
  | { kind: 'deleteItem'; matchId: string; n: number }
  | { kind: 'journal'; entry: JournalEntry }
  /** Replaces the stored copy of the server's log and forgets the confirmed-tail rows it now includes (seq up to and including `record.seq`). */
  | { kind: 'putEngineState'; record: EngineStateRecord }
  /** One more entry of the server's log, confirmed by a 200 since the last `putEngineState`. Written instead of rewriting the whole log on every ball. */
  | { kind: 'appendConfirmed'; matchId: string; entry: LogEntry };

export type OfflineScoring = { on: true } | { on: false; reason: 'ENGINE_VERSION_UNSUPPORTED' | 'FINGERPRINT_MISMATCH' | 'ENGINE_MISMATCH' | 'STORAGE_UNAVAILABLE' | 'LOG_UNREADABLE' };

export interface Counts {
  enqueued: number;
  confirmed: number;
  discarded: number;
}

/** Why the sender must not send: somebody has to decide first. */
export type Blocked = null | { kind: 'CONFLICT'; view: ServerView } | { kind: 'REJECTED'; n: number; violation: string; message: string } | { kind: 'LOCKED'; editableUntil: string | null } | { kind: 'FORBIDDEN' };

export interface QueueState {
  matchId: string;
  userId: string;
  setup: MatchSetup;
  engineVersion: number;
  /** The effective server log, as of `confirmedSeq`. Items that are confirmed are folded into it. */
  confirmedEntries: LogEntry[];
  confirmedSeq: number;
  /** Every item the phone still holds, in order. */
  items: QueueItem[];
  nextN: number;
  counts: Counts;
  offline: OfflineScoring;
  blocked: Blocked;
  display: DisplayData | null;
  /** Derived, never stored: the confirmed log with the items that are still in the chain applied. What the scorer sees. */
  local: ScoringLog;
}

export type RefusalCode = 'QUEUE_FULL' | 'OFFLINE_SCORING_OFF' | 'BLOCKED' | 'NOT_YOUR_QUEUE' | 'RULE' | 'NOTHING_TO_UNDO';

/** A tap that was not queued, and why. The screen shows `message`; nothing was changed or stored. */
export interface Refusal {
  code: RefusalCode;
  message: string;
  violation: string | null;
}

/** What every queue function returns: the new state, what to write, and a refusal when the tap was not taken (then `state` is the old one and `mutations` is empty). */
export interface Step {
  state: QueueState;
  mutations: Mutation[];
  refused: Refusal | null;
}

export const isInChain = (item: QueueItem): boolean => item.status === 'WAITING' || item.status === 'IN_FLIGHT';
