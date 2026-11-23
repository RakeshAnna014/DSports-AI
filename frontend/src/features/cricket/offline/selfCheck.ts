import { SUPPORTED_ENGINE_VERSIONS, logFrom, stateFingerprint, type LogEntry, type MatchSetup } from '../engine';
import type { OfflineScoring } from './types';

/**
 * The phone's safety net (plan section 7.2): before it predicts anything offline, it folds the server's log with its own engine and compares the fingerprint
 * with the one the server reports for the same seq. A different engine version, an unreadable log or a different fingerprint switches offline scoring OFF.
 * Switching it off never touches a queued item: they stay, in order, and can still be sent and reviewed.
 */
export function selfCheck(view: { setup: MatchSetup; entries: LogEntry[]; engineVersion: number; stateFingerprint: string }): OfflineScoring {
  if (!SUPPORTED_ENGINE_VERSIONS.includes(view.engineVersion)) {
    return { on: false, reason: 'ENGINE_VERSION_UNSUPPORTED' };
  }
  let fingerprint: string;
  try {
    fingerprint = stateFingerprint(logFrom(view.setup, view.entries));
  } catch {
    return { on: false, reason: 'LOG_UNREADABLE' };
  }
  return fingerprint === view.stateFingerprint ? { on: true } : { on: false, reason: 'FINGERPRINT_MISMATCH' };
}
