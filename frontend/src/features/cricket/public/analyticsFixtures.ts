import type { PublicAnalytics } from '@/types/cricketPublic';

/** The golden match's analytics, as the server gives them (Lions 22/2, Tigers chase 23 and win off the last ball), with the commentary cut down to a few lines. */
export const ANALYTICS: PublicAnalytics = {
  seq: 36,
  innings: [
    {
      number: 1, battingTeam: 'Lions', bowlingTeam: 'Tigers', runs: 22, wickets: 2, overs: '2.0', target: null,
      overRuns: [
        { over: 1, runs: 11, wickets: 2, bowler: 'Tarun', maiden: false, complete: true },
        { over: 2, runs: 11, wickets: 0, bowler: 'Uday', maiden: false, complete: true },
      ],
      worm: [{ over: 0, legalBalls: 0, runs: 0, wickets: 0 }, { over: 1, legalBalls: 6, runs: 11, wickets: 2 }, { over: 2, legalBalls: 12, runs: 22, wickets: 2 }],
      partnerships: [
        { wicket: 1, batterA: 'Arun', batterB: 'Bala', runs: 11, balls: 4, unbroken: false },
        { wicket: 2, batterA: 'Chetan', batterB: 'Bala', runs: 0, balls: 1, unbroken: false },
        { wicket: 3, batterA: 'Bala', batterB: 'Vik', runs: 11, balls: 7, unbroken: true },
      ],
    },
    {
      number: 2, battingTeam: 'Tigers', bowlingTeam: 'Lions', runs: 23, wickets: 0, overs: '2.0', target: 23,
      overRuns: [{ over: 1, runs: 17, wickets: 0, bowler: 'Arun', maiden: false, complete: true }, { over: 2, runs: 6, wickets: 0, bowler: 'Bala', maiden: false, complete: true }],
      worm: [{ over: 0, legalBalls: 0, runs: 0, wickets: 0 }, { over: 1, legalBalls: 6, runs: 17, wickets: 0 }, { over: 2, legalBalls: 12, runs: 23, wickets: 0 }],
      partnerships: [{ wicket: 1, batterA: 'Tarun', batterB: 'Uday', runs: 23, balls: 12, unbroken: true }],
    },
  ],
  shots: [],
  commentary: [
    { innings: 1, seq: 2, over: '0.1', text: 'Tarun: wide', runs: 1, wicket: false, boundary: false },
    { innings: 1, seq: 3, over: '0.1', text: 'Tarun to Arun: 1 run', runs: 1, wicket: false, boundary: false },
    { innings: 1, seq: 4, over: '0.2', text: 'Tarun to Bala: no ball, 4 runs; free hit next', runs: 5, wicket: false, boundary: true },
    { innings: 1, seq: 7, over: '0.5', text: 'Tarun to Arun: 1 run. OUT! Arun, run out (Wasim) (11/1)', runs: 1, wicket: true, boundary: false },
    { innings: 2, seq: 23, over: '0.1', text: 'Tarun to Arun: SIX', runs: 6, wicket: false, boundary: true },
  ],
};
