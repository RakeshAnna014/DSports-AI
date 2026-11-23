import { fingerprint, type Json } from './fingerprint';
import { currentInnings } from './match';
import { nextOf, NOTHING_TO_UNDO, stateOf, type ScoringLog, type StepResult } from './log';

/** A state as the plain JSON the fingerprint is computed over (the engine's states are plain data already). */
export const stateJson = (log: ScoringLog): Json => stateOf(log) as unknown as Json;

/** The fingerprint of the state of a log: what the server reports as `stateFingerprint` for the same seq. */
export const stateFingerprint = (log: ScoringLog): string => fingerprint(stateJson(log));

/**
 * The expectation recorded for a step, built from the log AFTER the step and the step's result, in exactly the shape the Java vector writer records
 * (`VectorRunner.expect`). `withState` adds the whole state (the golden files do, the big random files only now and then).
 */
export function expectOf(log: ScoringLog, result: StepResult, withState: boolean): { [key: string]: Json } {
  if (!result.accepted) {
    return result.violation === NOTHING_TO_UNDO ? { result: 'nothing_to_undo' } : { result: 'rejected', violation: result.violation };
  }
  const state = stateJson(log);
  const out: { [key: string]: Json } = { result: 'accepted', seq: result.seq };
  if (result.voids.length > 0) {
    out.voids = result.voids;
  }
  if (result.waivers.length > 0) {
    out.waivers = result.waivers.map((w) => ({ over: w.over, bowlerId: w.bowlerId, kind: w.kind }));
  }
  const current = currentInnings(log.score);
  const resultState = (state as { result: { type: string } | null }).result;
  out.fp = fingerprint(state);
  out.status = (state as { status: string }).status;
  out.next = nextOf(log);
  out.end = current === null || current.endReason === null ? null : current.endReason;
  out.resultType = resultState === null ? null : resultState.type;
  if (withState) {
    out.state = state;
  }
  return out;
}
