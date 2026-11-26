import { applyInnings, ballsRemaining, isComplete, notStarted, startInnings } from './innings';
import type { InningsState, MatchResult, MatchScore, MatchSetup, MatchStatus, NextAction, Outcome, RuleViolation, ScoreCommand, ScoreState, Uuid } from './types';

/**
 * Port of `MatchScoring`, `MatchScore` and `MatchResultCalculator`: match-level scoring as a pure function. `applyMatch` works out the innings command (the
 * teams, XIs, rules and target of an innings come from the setup), runs the innings engine and returns the new score. Everything else about a match (its
 * status, its result, what the scorer must do next) is worked out from the score every time, so nothing can disagree with it.
 */

/**
 * The version of the scoring rules. Bump it by hand, together with the Java constant `MatchScoring.ENGINE_VERSION`, whenever any rule changes an outcome. The
 * vectors record the Java value and a test fails when the two differ.
 */
export const ENGINE_VERSION = 1;

export const emptyScore = (): MatchScore => ({ innings: [], endedBy: null });
export const currentInnings = (score: MatchScore): InningsState | null => (score.innings.length === 0 ? null : score.innings[score.innings.length - 1]);

const reject = (violation: RuleViolation, message: string): Outcome<MatchScore> => ({ accepted: false, violation, message });

const otherTeam = (setup: MatchSetup, teamId: Uuid): Uuid => (teamId === setup.teamA ? setup.teamB : setup.teamA);
const xiOf = (setup: MatchSetup, teamId: Uuid): Uuid[] => (teamId === setup.teamA ? setup.xiA : setup.xiB);

export function applyMatch(setup: MatchSetup, score: MatchScore, command: ScoreCommand): Outcome<MatchScore> {
  if (score.endedBy !== null) {
    return reject('MATCH_ENDED', 'The match has been ended');
  }
  switch (command.type) {
    case 'START_INNINGS':
      return start(setup, score, command);
    case 'END_MATCH':
      return end(score, command.reason);
    default:
      return play(score, command);
  }
}

function start(setup: MatchSetup, score: MatchScore, c: Extract<ScoreCommand, { type: 'START_INNINGS' }>): Outcome<MatchScore> {
  const innings = score.innings;
  if (innings.length >= 2) {
    return reject('ALL_INNINGS_PLAYED', 'Both innings have been played');
  }
  if (setup.battingFirst === null || setup.xiA.length < 2 || setup.xiB.length < 2) {
    return reject('INVALID_SETUP', 'The toss and both playing XIs are needed before scoring');
  }
  let batting: Uuid;
  let target: number | null = null;
  if (innings.length === 0) {
    batting = setup.battingFirst;
  } else {
    const first = innings[0];
    if (!isComplete(first)) {
      return reject('PREVIOUS_INNINGS_NOT_COMPLETE', 'The first innings is not finished');
    }
    batting = otherTeam(setup, first.battingTeamId!);
    target = first.runs + 1;
  }
  const bowling = otherTeam(setup, batting);
  const outcome = startInnings(notStarted(), {
    rules: setup.rules,
    overs: setup.overs,
    battingTeamId: batting,
    bowlingTeamId: bowling,
    battingXi: xiOf(setup, batting),
    bowlingXi: xiOf(setup, bowling),
    striker: c.striker,
    nonStriker: c.nonStriker,
    bowler: c.bowler,
    target,
  });
  if (!outcome.accepted) {
    return outcome;
  }
  return { accepted: true, state: { innings: [...innings, outcome.state], endedBy: score.endedBy }, waivers: outcome.waivers };
}

function play(score: MatchScore, command: Exclude<ScoreCommand, { type: 'START_INNINGS' | 'END_MATCH' }>): Outcome<MatchScore> {
  const current = currentInnings(score);
  if (current === null) {
    return reject('INNINGS_NOT_STARTED', 'The innings has not started');
  }
  const outcome = applyInnings(current, command);
  if (!outcome.accepted) {
    return outcome;
  }
  return { accepted: true, state: { innings: [...score.innings.slice(0, -1), outcome.state], endedBy: score.endedBy }, waivers: outcome.waivers };
}

function end(score: MatchScore, reason: 'ABANDONED' | 'NO_RESULT'): Outcome<MatchScore> {
  if (matchStatus(score) === 'COMPLETED') {
    return reject('MATCH_COMPLETE', 'The match is complete');
  }
  if (reason === null || reason === undefined) {
    return reject('INVALID_SETUP', 'Say whether the match was abandoned or has no result');
  }
  return { accepted: true, state: { innings: score.innings, endedBy: reason }, waivers: [] };
}

/** Rebuilds a score by applying the commands in order. Every command must have been accepted when it was first applied (it throws if one is not). */
export function foldMatch(setup: MatchSetup, commands: readonly ScoreCommand[]): MatchScore {
  let score = emptyScore();
  for (const command of commands) {
    const outcome = applyMatch(setup, score, command);
    if (!outcome.accepted) {
      throw new Error(`A logged command is no longer accepted: ${JSON.stringify(command)} (${outcome.violation})`);
    }
    score = outcome.state;
  }
  return score;
}

// ---- what a score implies ------------------------------------------------------------------------------------------

/** The match status this score implies. */
export function matchStatus(score: MatchScore): MatchStatus {
  if (score.endedBy !== null) {
    return score.endedBy === 'ABANDONED' ? 'ABANDONED' : 'NO_RESULT';
  }
  switch (score.innings.length) {
    case 0:
      return 'TOSS_DONE';
    case 1:
      return isComplete(score.innings[0]) ? 'INNINGS_BREAK' : 'IN_PROGRESS';
    default:
      return isComplete(score.innings[1]) ? 'COMPLETED' : 'IN_PROGRESS';
  }
}

export const isFinished = (score: MatchScore): boolean => {
  const status = matchStatus(score);
  return status === 'COMPLETED' || status === 'ABANDONED' || status === 'NO_RESULT';
};

/** Wickets the batting side can lose: one fewer than its players, or all of them with last man stands. */
const wicketsAvailable = (s: InningsState): number => (s.rules.lastManStands ? s.battingXi.length : s.battingXi.length - 1);

/** The result of a completed match, recomputed from the two innings every time (null until the match is complete). */
export function matchResult(score: MatchScore): MatchResult | null {
  if (matchStatus(score) !== 'COMPLETED') {
    return null;
  }
  const [first, second] = score.innings;
  const target = second.target!;
  if (second.runs >= target) {
    const inHand = Math.max(0, wicketsAvailable(second) - second.wickets);
    return { type: 'WON_BY_WICKETS', winnerTeamId: second.battingTeamId, margin: inHand, ballsRemaining: Math.max(0, ballsRemaining(second)) };
  }
  if (second.runs === target - 1) {
    return { type: 'TIE', winnerTeamId: null, margin: 0, ballsRemaining: 0 };
  }
  return { type: 'WON_BY_RUNS', winnerTeamId: first.battingTeamId, margin: target - 1 - second.runs, ballsRemaining: 0 };
}

/** The full state the vectors record and the fingerprint covers: the score, the result and the status. */
export const scoreState = (score: MatchScore): ScoreState => ({ score, result: matchResult(score), status: matchStatus(score) });

/** What the scorer must do next: start an innings, name a batter, name a bowler, score a ball, or nothing (the match is over). */
export function nextAction(score: MatchScore): NextAction {
  if (score.endedBy !== null || isFinished(score)) {
    return 'NONE';
  }
  const current = currentInnings(score);
  if (current === null || isComplete(current)) {
    return 'START_INNINGS';
  }
  if (current.needsBatter) {
    return 'SELECT_BATTER';
  }
  if (current.needsBowler) {
    return 'SELECT_BOWLER';
  }
  return 'DELIVER';
}
