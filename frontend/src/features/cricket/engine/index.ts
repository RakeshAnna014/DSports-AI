/**
 * The public face of the offline scoring engine. Everything exported here is pure: no React, no network, no clock, no storage, no randomness. The queue (2f-2) and
 * the scorer screen (2f-4) import from here and from nowhere deeper, so the engine can later move into a worker without touching them.
 */
export { ENGINE_VERSION, applyMatch, currentInnings, emptyScore, foldMatch, isFinished, matchResult, matchStatus, nextAction, scoreState } from './match';
export { eligibleBowlers, waiverNeeded, yetToBat } from './innings';
export { NOTHING_TO_UNDO, commandsOf, effective, isFollower, lastSeq, logFrom, newLog, planUndo, stepCommand, stepUndo } from './log';
export type { LogEntry, ScoringLog, StepResult } from './log';
export { CommandFormatError, parseCommand, toBody } from './wire';
export { stateFingerprint, stateJson } from './vectorExpect';
export { canonicalJson, fingerprint } from './fingerprint';
export { SUPPORTED_ENGINE_VERSIONS, VOCABULARY } from './vocabulary';
export type * from './types';
