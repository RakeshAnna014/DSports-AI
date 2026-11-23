import type { PublicInnings, PublicMatchSummary, PublicScorecard } from '@/types/cricketPublic';

export const FIRST_INNINGS: PublicInnings = {
  number: 1, battingTeam: 'Lions', bowlingTeam: 'Tigers', status: 'COMPLETE', endReason: 'OVERS_COMPLETE', runs: 22, wickets: 2, overs: '2.0', ballsRemaining: 0,
  target: null, runsNeeded: null, runRate: 11, requiredRate: null,
  extras: { wides: 1, noBalls: 1, byes: 1, legByes: 0, total: 3 },
  batters: [
    { playerId: 'p-ravi', name: 'Ravi', runs: 9, balls: 6, fours: 1, sixes: 0, howOut: 'c Kiran b Tarun', notOut: false, atCrease: false, striker: false },
    { playerId: 'p-sam', name: 'Sam', runs: 4, balls: 3, fours: 0, sixes: 0, howOut: 'b Tarun', notOut: false, atCrease: false, striker: false },
    { playerId: 'p-dev', name: 'Dev', runs: 6, balls: 3, fours: 0, sixes: 1, howOut: 'not out', notOut: true, atCrease: false, striker: false },
  ],
  yetToBat: ['Anil', 'Bala'],
  bowlers: [{ playerId: 'p-tarun', name: 'Tarun', overs: '1.0', maidens: 0, runs: 12, wickets: 2, wides: 1, noBalls: 0, economy: 12, bowling: false }],
  fallOfWickets: [{ number: 1, runs: 12, batter: 'Ravi', overs: '0.5', howOut: 'c Kiran b Tarun' }, { number: 2, runs: 15, batter: 'Sam', overs: '1.2', howOut: 'b Tarun' }],
  waivers: [{ over: 2, text: 'Kiran bowled two overs in a row in over 2 because no other bowler was left' }],
};

export const CHASE: PublicInnings = {
  number: 2, battingTeam: 'Tigers', bowlingTeam: 'Lions', status: 'IN_PROGRESS', endReason: null, runs: 10, wickets: 0, overs: '0.2', ballsRemaining: 10,
  target: 23, runsNeeded: 13, runRate: 30, requiredRate: 7.8,
  extras: { wides: 0, noBalls: 0, byes: 0, legByes: 0, total: 0 },
  batters: [
    { playerId: 'p-kiran', name: 'Kiran', runs: 6, balls: 1, fours: 0, sixes: 1, howOut: 'not out', notOut: true, atCrease: true, striker: false },
    { playerId: 'p-tarun', name: 'Tarun', runs: 4, balls: 1, fours: 1, sixes: 0, howOut: 'not out', notOut: true, atCrease: true, striker: true },
  ],
  yetToBat: [],
  bowlers: [{ playerId: 'p-ravi', name: 'Ravi', overs: '0.2', maidens: 0, runs: 10, wickets: 0, wides: 0, noBalls: 0, economy: 30, bowling: true }],
  fallOfWickets: [],
  waivers: [],
};

export const LIVE_CARD: PublicScorecard = {
  matchId: 'm-1', seq: 25, status: 'IN_PROGRESS', live: true, teamA: 'Lions', teamB: 'Tigers', overs: 2, city: 'Chennai', venue: 'Green Park', scheduledAt: null,
  innings: [FIRST_INNINGS, CHASE], result: null,
};

export const FINISHED_CARD: PublicScorecard = {
  ...LIVE_CARD, seq: 36, status: 'COMPLETED', live: false,
  innings: [FIRST_INNINGS, { ...CHASE, status: 'COMPLETE', endReason: 'TARGET_REACHED', runs: 23, overs: '1.5', ballsRemaining: 0, runsNeeded: 0, requiredRate: null }],
  result: { type: 'WON_BY_WICKETS', winner: 'Tigers', text: 'Tigers won by 10 wickets (0 balls left)' },
};

export const SUMMARY: PublicMatchSummary = {
  matchId: 'm-1', status: 'IN_PROGRESS', teamA: 'Lions', teamB: 'Tigers', overs: 2, city: 'Chennai', venue: 'Green Park', scheduledAt: null,
  scores: [{ team: 'Lions', runs: 22, wickets: 2, overs: '2.0', current: false }, { team: 'Tigers', runs: 10, wickets: 0, overs: '0.2', current: true }],
};
