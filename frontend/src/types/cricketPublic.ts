/** What anyone may read about a started match, signed in or not: names and numbers only (no ids but the match's own). */
export type PublicStatus = 'IN_PROGRESS' | 'INNINGS_BREAK' | 'COMPLETED' | 'ABANDONED' | 'NO_RESULT';

export interface PublicBatter {
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
