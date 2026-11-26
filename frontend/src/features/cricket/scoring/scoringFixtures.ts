import { AxiosError } from 'axios';
import type { Match } from '@/types/cricket';
import type { BatterRow, BowlerChoice, DismissalType, InningsView, Ref, ScoringConflict, ScoringView } from '@/types/cricketScoring';
import { MATCH } from '../cricketFixtures';

export const ref = (id: string, name: string): Ref => ({ id, name });

export const ARUN = ref('a-1', 'Arun');
export const BALA = ref('a-2', 'Bala');
export const CHETAN = ref('a-3', 'Chetan');
export const TARUN = ref('b-1', 'Tarun');
export const UDAY = ref('b-2', 'Uday');
export const VIK = ref('b-3', 'Vik');

const ALL: DismissalType[] = ['BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET', 'RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'];
export const ALLOWED: ScoringView['allowedDismissals'] = {
  NORMAL: ALL,
  WIDE: ['STUMPED', 'HIT_WICKET', 'RUN_OUT', 'OBSTRUCTING_FIELD'],
  NO_BALL: ['RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'],
};
export const FREE_HIT_ALLOWED: ScoringView['allowedDismissals'] = {
  NORMAL: ['RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'],
  WIDE: ['RUN_OUT', 'OBSTRUCTING_FIELD'],
  NO_BALL: ['RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'],
};

const batter = (player: Ref, runs: number, balls: number, atCrease = true): BatterRow => ({ player, runs, balls, fours: 0, sixes: 0, howOut: 'not out', notOut: true, atCrease });

export const INNINGS: InningsView = {
  number: 1, battingTeam: ref('ta', 'Lions'), bowlingTeam: ref('tb', 'Tigers'), status: 'IN_PROGRESS', endReason: null, runs: 14, wickets: 1, overs: '1.4', ballsRemaining: 20,
  target: null, runsNeeded: null, extras: { wides: 1, noBalls: 0, byes: 0, legByes: 0, total: 1 }, striker: ARUN, nonStriker: BALA, bowler: TARUN, thisOver: ['1', '•', 'Wd', '4'],
  batters: [batter(ARUN, 9, 6), batter(BALA, 4, 5)], yetToBat: [CHETAN],
  bowlers: [{ player: TARUN, overs: '0.4', creditedOvers: 0, maidens: 0, runs: 10, wickets: 0, wides: 1, noBalls: 0, economy: 15 }],
  fallOfWickets: [], waivers: [],
};

const bowlerChoice = (player: Ref, overs: string, over: Partial<BowlerChoice> = {}): BowlerChoice => ({ player, overs, creditedOvers: 0, limit: 4, canBowlNext: false, canReplaceMidOver: false, why: null, ...over });

export const VIEW: ScoringView = {
  matchId: 'm-1', seq: 12, matchStatus: 'IN_PROGRESS', rules: { overs: 20, ballsPerOver: 6, playersPerSide: 11, maxOversPerBowler: 4 }, innings: [INNINGS], needs: null, freeHit: false,
  allowedDismissals: ALLOWED, eligibleBatters: [], bowlers: [bowlerChoice(TARUN, '0.4'), bowlerChoice(UDAY, '0.0', { canReplaceMidOver: true }), bowlerChoice(VIK, '0.0', { canReplaceMidOver: true })],
  waiverRequired: false, result: null, canUndo: { allowed: true, describe: 'Undo: 4 runs', crossesInnings: false, blockedReason: null }, editableUntil: null, locked: false,
  recentEvents: [{ seq: 12, kind: 'DELIVER', text: '4 runs', by: ref('u-1', 'Olive Organiser'), at: '2026-11-20T10:00:00Z', mine: true, clientEventId: null, tappedAt: null }],
  engineVersion: 1, stateFingerprint: '0000000000000000',
};

export const view = (over: Partial<ScoringView> = {}): ScoringView => ({ ...VIEW, ...over });
export const withInnings = (over: Partial<InningsView>, viewOver: Partial<ScoringView> = {}): ScoringView => ({ ...VIEW, ...viewOver, innings: [{ ...INNINGS, ...over }] });

const squad = (prefix: string, names: string[]) => names.map((name, i) => ({ playerId: `${prefix}-${i + 1}`, name, battingOrder: i + 1 }));

/** The match as the setup page returns it, ready to start: Lions bat first. */
export const READY_MATCH: Match = {
  ...MATCH, status: 'TOSS_DONE', readyToStart: true, battingFirstTeamId: 'ta', toss: { winnerTeamId: 'ta', decision: 'BAT' },
  teamA: { teamId: 'ta', teamName: 'Lions', playingXi: squad('a', ['Arun', 'Bala', 'Chetan']), squad: [] },
  teamB: { teamId: 'tb', teamName: 'Tigers', playingXi: squad('b', ['Tarun', 'Uday', 'Vik']), squad: [] },
};

export const conflictOf = (currentSeq: number, over: Partial<ScoringView> = {}): ScoringConflict => ({
  currentSeq, view: view({ seq: currentSeq, ...over }),
  eventsSince: [{ seq: currentSeq, kind: 'DELIVER', text: '2 runs', by: ref('u-2', 'Sam Scorer'), at: '2026-11-20T10:01:00Z', mine: false, clientEventId: null, tappedAt: null }],
});

/** An error answered by the server, with the ApiError body and optional details. */
export const serverError = (status: number, code: string, message: string, details?: unknown) =>
  new AxiosError('failed', String(status), undefined, undefined, { status, data: { code, message, status, details } } as never);

/** No answer at all: the phone is offline or the server is unreachable. */
export const networkError = () => new AxiosError('Network Error', 'ERR_NETWORK');
