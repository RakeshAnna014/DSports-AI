/** What anyone may read about a started match, signed in or not: names and numbers only (the only ids are the match's own and the players', which link to their public career). */
export type PublicStatus = 'IN_PROGRESS' | 'INNINGS_BREAK' | 'COMPLETED' | 'ABANDONED' | 'NO_RESULT';

export interface PublicBatter {
  /** Null for a guest or a player who hid their public page: show the name without a link. */
  playerId: string | null;
  name: string;
  runs: number;
  balls: number;
  fours: number;
  sixes: number;
  howOut: string;
  notOut: boolean;
  atCrease: boolean;
  striker: boolean;
}

export interface PublicBowler {
  /** Null for a guest or a player who hid their public page: show the name without a link. */
  playerId: string | null;
  name: string;
  overs: string;
  maidens: number;
  runs: number;
  wickets: number;
  wides: number;
  noBalls: number;
  economy: number;
  bowling: boolean;
}

export interface PublicFall {
  number: number;
  runs: number;
  batter: string;
  overs: string;
  howOut: string;
}

export interface PublicInnings {
  number: number;
  battingTeam: string;
  bowlingTeam: string;
  status: 'IN_PROGRESS' | 'COMPLETE';
  endReason: string | null;
  runs: number;
  wickets: number;
  overs: string;
  ballsRemaining: number;
  target: number | null;
  runsNeeded: number | null;
  runRate: number | null;
  requiredRate: number | null;
  extras: { wides: number; noBalls: number; byes: number; legByes: number; total: number };
  batters: PublicBatter[];
  yetToBat: string[];
  bowlers: PublicBowler[];
  fallOfWickets: PublicFall[];
  waivers: { over: number; text: string }[];
}

export interface PublicScorecard {
  matchId: string;
  seq: number;
  status: PublicStatus;
  live: boolean;
  teamA: string;
  teamB: string;
  overs: number;
  city: string | null;
  venue: string | null;
  scheduledAt: string | null;
  innings: PublicInnings[];
  result: { type: string; winner: string | null; text: string } | null;
}

export interface PublicMatchSummary {
  matchId: string;
  status: PublicStatus | 'TOSS_DONE';
  teamA: string;
  teamB: string;
  overs: number;
  city: string | null;
  venue: string | null;
  scheduledAt: string | null;
  scores: { team: string; runs: number; wickets: number; overs: string; current: boolean }[];
}

export interface PublicPage<T> {
  items: T[];
  page: number;
  size: number;
  hasNext: boolean;
}

/** One over of the Manhattan chart: runs and wickets in it, who bowled it, and whether it was a maiden. `complete` is false for the over in progress. */
export interface OverRuns {
  over: number;
  runs: number;
  wickets: number;
  bowler: string | null;
  maiden: boolean;
  complete: boolean;
}

/** The total after `over` overs (0 is the start); `legalBalls` makes a part-over at the end exact. */
export interface WormPoint {
  over: number;
  legalBalls: number;
  runs: number;
  wickets: number;
}

/** The partnership that ended with the `wicket`-th wicket (the batter out, then the partner), or the unbroken last one (the two still in). */
export interface Partnership {
  wicket: number;
  batterA: string | null;
  batterB: string | null;
  runs: number;
  balls: number;
  unbroken: boolean;
}

export interface InningsAnalytics {
  number: number;
  battingTeam: string;
  bowlingTeam: string;
  runs: number;
  wickets: number;
  overs: string;
  target: number | null;
  overRuns: OverRuns[];
  worm: WormPoint[];
  partnerships: Partnership[];
}

export interface CommentaryLine {
  innings: number;
  seq: number;
  over: string;
  text: string;
  runs: number;
  wicket: boolean;
  boundary: boolean;
}

/** The charts and commentary of a started match (names and numbers only). */
/** Where the scorer said a scoring ball went (optional; only marked balls appear). x and y are thousandths of the field's radius from the middle, right and up positive. */
export interface ShotMark {
  innings: number;
  seq: number;
  over: string;
  batter: string;
  runs: number;
  boundary: boolean;
  x: number;
  y: number;
}

export interface PublicAnalytics {
  seq: number;
  innings: InningsAnalytics[];
  commentary: CommentaryLine[];
  shots: ShotMark[];
}

/** A player's career for one ball type. A ratio the server could not work out (no dismissal, no ball bowled) is null, never zero. */
export interface PublicCareerBatting {
  innings: number;
  runs: number;
  balls: number;
  notOuts: number;
  highest: number;
  highestNotOut: boolean;
  fours: number;
  sixes: number;
  fifties: number;
  hundreds: number;
  ducks: number;
  average: number | null;
  strikeRate: number | null;
}

export interface PublicCareerBowling {
  innings: number;
  overs: string;
  runs: number;
  wickets: number;
  maidens: number;
  best: string | null;
  fiveWickets: number;
  average: number | null;
  economy: number | null;
  strikeRate: number | null;
}

export interface PublicCareerLine {
  ballType: 'LEATHER' | 'TENNIS';
  matches: number;
  batting: PublicCareerBatting;
  bowling: PublicCareerBowling;
  fielding: { catches: number; stumpings: number; runOuts: number };
}

export interface PublicPlayerStats {
  name: string;
  lines: PublicCareerLine[];
}
