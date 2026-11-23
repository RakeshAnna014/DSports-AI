import type { DeliveryKind, DismissalType, MatchRules, RuleViolation } from './types';

/** Port of `MatchRules`: the defaults, the validation and the bowler limit. */
export const standardRules = (): MatchRules => ({
  ballsPerOver: 6,
  playersPerSide: 11,
  maxOversPerBowler: null,
  widePenalty: 1,
  noBallPenalty: 1,
  freeHitEnabled: true,
  lastManStands: false,
});

/** The first problem with these rules for a match of `overs` overs, or null when they make sense (Java throws; the engine turns it into INVALID_SETUP). */
export function rulesProblem(rules: MatchRules, overs: number): string | null {
  if (!(rules.ballsPerOver >= 4 && rules.ballsPerOver <= 10)) {
    return 'Balls per over must be between 4 and 10';
  }
  if (!(rules.playersPerSide >= 2 && rules.playersPerSide <= 11)) {
    return 'Players per side must be between 2 and 11';
  }
  if (!(rules.maxOversPerBowler === null || (rules.maxOversPerBowler >= 1 && rules.maxOversPerBowler <= overs))) {
    return 'Overs per bowler must be between 1 and the match length';
  }
  if (!(rules.widePenalty >= 0 && rules.widePenalty <= 5 && rules.noBallPenalty >= 0 && rules.noBallPenalty <= 5)) {
    return 'Wide and no-ball penalties must be between 0 and 5 runs';
  }
  return null;
}

const ceilDiv = (a: number, b: number): number => Math.trunc((a + b - 1) / b);

/** The most overs one bowler may bowl: the configured limit, or the larger of a fifth of the match and the match shared by a side's bowlers. */
export function bowlerLimit(rules: MatchRules, overs: number): number {
  if (rules.maxOversPerBowler !== null) {
    return rules.maxOversPerBowler;
  }
  return Math.max(ceilDiv(overs, 5), ceilDiv(overs, rules.playersPerSide - 1));
}

// ---- dismissals (port of `DismissalRules`) -------------------------------------------------------------------------

const ALL: readonly DismissalType[] = ['BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET', 'RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'];
const ON_WIDE: readonly DismissalType[] = ['STUMPED', 'RUN_OUT', 'HIT_WICKET', 'OBSTRUCTING_FIELD'];
const ON_NO_BALL: readonly DismissalType[] = ['RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'];
const CREDITED_TO_BOWLER: readonly DismissalType[] = ['BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET'];
const EITHER_BATTER: readonly DismissalType[] = ['RUN_OUT', 'OBSTRUCTING_FIELD'];
const KEEPS_COMPLETED_RUNS: readonly DismissalType[] = ['RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'];

/** Why this dismissal cannot happen on this ball, or null when it can. The kind of ball is checked first, then the free hit. */
export function dismissalViolation(kind: DeliveryKind, freeHit: boolean, type: DismissalType): RuleViolation | null {
  const byKind = kind === 'NORMAL' ? ALL : kind === 'WIDE' ? ON_WIDE : ON_NO_BALL;
  if (!byKind.includes(type)) {
    return kind === 'WIDE' ? 'DISMISSAL_NOT_ALLOWED_ON_WIDE' : 'DISMISSAL_NOT_ALLOWED_ON_NO_BALL';
  }
  if (freeHit && !ON_NO_BALL.includes(type)) {
    return 'DISMISSAL_NOT_ALLOWED_ON_FREE_HIT';
  }
  return null;
}

export const creditedToBowler = (type: DismissalType): boolean => CREDITED_TO_BOWLER.includes(type);
export const eitherBatterCanBeOut = (type: DismissalType): boolean => EITHER_BATTER.includes(type);
export const canNameFielder = (type: DismissalType): boolean => type === 'CAUGHT' || type === 'STUMPED' || type === 'RUN_OUT';
export const keepsCompletedRuns = (type: DismissalType): boolean => KEEPS_COMPLETED_RUNS.includes(type);
