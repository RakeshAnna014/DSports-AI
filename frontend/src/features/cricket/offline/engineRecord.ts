import { logFrom, stateFingerprint } from '../engine';
import type { EngineStateRecord, QueueState } from './types';

/** The engine-state record for what the phone currently knows of the server's log (and, if it has them, the names and window). Pure; shared by `load` and `queue`. */
export function saveEngineStateRecord(state: QueueState, now: string): EngineStateRecord {
  return {
    matchId: state.matchId,
    userId: state.userId,
    setup: state.setup,
    entries: state.confirmedEntries,
    seq: state.confirmedSeq,
    engineVersion: state.engineVersion,
    stateFingerprint: stateFingerprint(logFrom(state.setup, state.confirmedEntries)),
    savedAt: now,
    ...(state.display === null ? {} : { display: state.display }),
  };
}
