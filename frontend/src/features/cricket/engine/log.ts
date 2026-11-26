import { applyMatch, emptyScore, foldMatch, nextAction, scoreState } from './match';
import type { MatchScore, MatchSetup, RuleViolation, ScoreCommand, UndoMode, Uuid, Waiver } from './types';

/**
 * Port of `ScoringLog` and of the step runner the vectors use. Undo is an event, not a deletion: the effective log is the commands that no later UNDO voids, and
 * the match state is the fold of that. Everything here is pure: the caller passes the ids and the times it wants recorded, nothing is read from a clock.
 */

export type LogEntry =
  | { seq: number; kind: 'COMMAND'; command: ScoreCommand; clientEventId: Uuid | null; waivers: Waiver[] }
  | { seq: number; kind: 'UNDO'; voids: number[]; clientEventId: Uuid | null }
  | { seq: number; kind: 'ADMIN_UNLOCK'; clientEventId: Uuid | null };

/** The commands that are still in force, in order. */
export function effective(entries: readonly LogEntry[]): Extract<LogEntry, { kind: 'COMMAND' }>[] {
  const voided = new Set<number>();
  for (const entry of entries) {
    if (entry.kind === 'UNDO') {
      entry.voids.forEach((seq) => voided.add(seq));
    }
  }
  return entries.filter((e): e is Extract<LogEntry, { kind: 'COMMAND' }> => e.kind === 'COMMAND' && !voided.has(e.seq));
}

export const commandsOf = (entries: readonly LogEntry[]): ScoreCommand[] => effective(entries).map((e) => e.command);

/** True for the choices that follow a ball (the new batter, the new bowler): "undo ball" takes them off together with the ball they followed. */
export const isFollower = (command: ScoreCommand): boolean => command.type === 'SELECT_BATTER' || command.type === 'SELECT_BOWLER';

/**
 * The seqs an undo in this mode would void, latest first, or empty when there is nothing to undo. BALL mode takes the choices that follow a ball and then the one
 * command they followed, and never goes further, so it cannot silently cross the start of an innings.
 */
export function planUndo(entries: readonly LogEntry[], mode: UndoMode): number[] {
  const live = effective(entries);
  const voids: number[] = [];
  for (let i = live.length - 1; i >= 0; i--) {
    const entry = live[i];
    voids.push(entry.seq);
    if (mode === 'COMMAND' || !isFollower(entry.command)) {
      break;
    }
  }
  return voids;
}

// ---- a log with its score, one step at a time -----------------------------------------------------------------------

/** The log of one match and the score it folds to. Immutable: every step returns a new one. */
export interface ScoringLog {
  readonly setup: MatchSetup;
  readonly entries: readonly LogEntry[];
  readonly score: MatchScore;
}

export const NOTHING_TO_UNDO = 'NOTHING_TO_UNDO';

/** What one step did: the same facts the vectors record. */
export type StepResult =
  | { accepted: true; seq: number; voids: number[]; waivers: Waiver[] }
  | { accepted: false; violation: RuleViolation | typeof NOTHING_TO_UNDO; message: string };

export const lastSeq = (entries: readonly LogEntry[]): number => (entries.length === 0 ? 0 : entries[entries.length - 1].seq);

export const newLog = (setup: MatchSetup): ScoringLog => ({ setup, entries: [], score: emptyScore() });

/** Rebuilds a log's score from its entries (what a phone does with the log the server sent). Throws if an entry in force is no longer accepted. */
export const logFrom = (setup: MatchSetup, entries: readonly LogEntry[]): ScoringLog => ({ setup, entries, score: foldMatch(setup, commandsOf(entries)) });

/** Applies a command. A refused command returns the very same log, so nothing can have changed. */
export function stepCommand(log: ScoringLog, command: ScoreCommand, clientEventId: Uuid | null = null): { log: ScoringLog; result: StepResult } {
  const outcome = applyMatch(log.setup, log.score, command);
  if (!outcome.accepted) {
    return { log, result: { accepted: false, violation: outcome.violation, message: outcome.message } };
  }
  const seq = lastSeq(log.entries) + 1;
  const entry: LogEntry = { seq, kind: 'COMMAND', command, clientEventId, waivers: outcome.waivers };
  return { log: { setup: log.setup, entries: [...log.entries, entry], score: outcome.state }, result: { accepted: true, seq, voids: [], waivers: outcome.waivers } };
}

/** Appends an UNDO event that voids what `planUndo` says. With nothing to take off the same log comes back and nothing is written. */
export function stepUndo(log: ScoringLog, mode: UndoMode, clientEventId: Uuid | null = null): { log: ScoringLog; result: StepResult } {
  const voids = planUndo(log.entries, mode);
  if (voids.length === 0) {
    return { log, result: { accepted: false, violation: NOTHING_TO_UNDO, message: 'There is nothing to undo' } };
  }
  const seq = lastSeq(log.entries) + 1;
  const entries: LogEntry[] = [...log.entries, { seq, kind: 'UNDO', voids, clientEventId }];
  return { log: { setup: log.setup, entries, score: foldMatch(log.setup, commandsOf(entries)) }, result: { accepted: true, seq, voids, waivers: [] } };
}

/** The `fp`, `status`, `next` and friends of an accepted step, in the order the vectors record them (see testdata/cricket-engine/README.md). */
export const stateOf = (log: ScoringLog) => scoreState(log.score);
export const nextOf = (log: ScoringLog) => nextAction(log.score);
