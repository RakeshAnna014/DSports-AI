/** Plain words for the rule the server refused. The server's own message is the fallback. */
const MESSAGES: Record<string, string> = {
  NEEDS_NEW_BATTER: 'Choose the next batter first.',
  NEEDS_NEW_BOWLER: 'Choose the bowler of the next over first.',
  NO_BATTER_NEEDED: 'No batter is needed right now.',
  NO_BOWLER_NEEDED: 'The over is not finished.',
  RUNS_OUT_OF_RANGE: 'That is too many runs for one ball (at most 7 of each kind).',
  INVALID_BOUNDARY: 'A boundary is a four, or a six off the bat, and cannot be a wicket.',
  DISMISSAL_NOT_ALLOWED_ON_WIDE: 'That kind of dismissal cannot happen on a wide.',
  DISMISSAL_NOT_ALLOWED_ON_NO_BALL: 'That kind of dismissal cannot happen on a no-ball.',
  DISMISSAL_NOT_ALLOWED_ON_FREE_HIT: 'That kind of dismissal cannot happen on a free hit.',
  ONLY_STRIKER_CAN_BE_OUT: 'Only the striker can be out that way.',
  DISMISSAL_RUNS_NOT_ALLOWED: 'No runs are scored off a ball that way.',
  FIELDER_NOT_ALLOWED: 'A fielder can be named only for caught, stumped and run out.',
  FIELDER_NOT_IN_XI: 'The fielder must be in the fielding side.',
  BOWLER_BOWLED_PREVIOUS_OVER: 'That bowler bowled the last over.',
  BOWLER_LIMIT_REACHED: 'That bowler has bowled the most overs allowed.',
  BOWLER_ALREADY_BOWLED_THIS_OVER: 'That bowler has already bowled in this over.',
  NO_DELIVERY_IN_OVER: 'No ball has been bowled in this over yet.',
  BATTER_ALREADY_OUT: 'That batter is out and cannot bat again.',
  INNINGS_COMPLETE: 'The innings is over.',
  PREVIOUS_INNINGS_NOT_COMPLETE: 'The first innings is not finished.',
  ALL_INNINGS_PLAYED: 'Both innings have been played.',
  MATCH_ENDED: 'The match has been ended.',
  MATCH_COMPLETE: 'The match is complete.',
  INVALID_OPENERS: 'The openers must be two different players of the batting side.',
};

export const ruleMessage = (violation: string, fallback: string): string => MESSAGES[violation] ?? fallback;
