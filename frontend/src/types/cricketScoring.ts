export interface Ref {
  id: string;
  name: string;
}

export type DeliveryKind = 'NORMAL' | 'WIDE' | 'NO_BALL';
export type RunsType = 'BAT' | 'BYE' | 'LEG_BYE';
export type DismissalType = 'BOWLED' | 'CAUGHT' | 'LBW' | 'STUMPED' | 'HIT_WICKET' | 'RUN_OUT' | 'OBSTRUCTING_FIELD' | 'HIT_BALL_TWICE';
export type CreaseEnd = 'BATTING_END' | 'BOWLERS_END';

export interface ScoringRulesView {
  overs: number;
  ballsPerOver: number;
  playersPerSide: number;
  maxOversPerBowler: number;
}

export interface BatterRow {
  player: Ref;
  runs: number;
  balls: number;
  fours: number;
  sixes: number;
  howOut: string;
  notOut: boolean;
  atCrease: boolean;
}

export interface BowlerRow {
  player: Ref;
  overs: string;
  creditedOvers: number;
  maidens: number;
  runs: number;
  wickets: number;
  wides: number;
  noBalls: number;
  economy: number;
}

export interface FallRow {
  number: number;
  runs: number;
  batter: Ref;
  overs: string;
  howOut: string;
}

export interface WaiverNote {
  over: number;
  bowler: Ref;
  kind: 'OVER_LIMIT' | 'CONSECUTIVE_OVER';
  text: string;
}

export interface InningsView {
  number: number;
  battingTeam: Ref;
  bowlingTeam: Ref;
  status: 'IN_PROGRESS' | 'COMPLETE';
  endReason: string | null;
  runs: number;
  wickets: number;
  overs: string;
  ballsRemaining: number;
  target: number | null;
  runsNeeded: number | null;
  extras: { wides: number; noBalls: number; byes: number; legByes: number; total: number };
  striker: Ref | null;
  nonStriker: Ref | null;
  bowler: Ref | null;
  thisOver: string[];
  batters: BatterRow[];
  yetToBat: Ref[];
  bowlers: BowlerRow[];
  fallOfWickets: FallRow[];
  waivers: WaiverNote[];
}

export interface BatterChoice {
  player: Ref;
  returning: boolean;
  note: string | null;
}

export interface BowlerChoice {
  player: Ref;
  overs: string;
  creditedOvers: number;
  limit: number;
  canBowlNext: boolean;
  canReplaceMidOver: boolean;
  why: 'BOWLED_LAST_OVER' | 'AT_LIMIT' | null;
}

export interface ScoringResult {
  type: string;
  winner: Ref | null;
  margin: number;
  ballsRemaining: number;
  text: string;
}

export interface CanUndo {
  allowed: boolean;
  describe: string | null;
  crossesInnings: boolean;
  blockedReason: 'LOCKED' | 'NOTHING_TO_UNDO' | null;
}

export interface ScoringEvent {
  seq: number;
  kind: string;
  text: string;
  by: Ref;
  at: string;
  /** Whether the person asking made this event; their own events carry the client event id they sent (how a phone knows the server already has a ball). */
  mine: boolean;
  clientEventId: string | null;
  /** When the scorer says they tapped an offline ball: for the timeline only, never used by any rule. */
  tappedAt: string | null;
}

export interface ScoringView {
  matchId: string;
  seq: number;
  matchStatus: 'TOSS_DONE' | 'IN_PROGRESS' | 'INNINGS_BREAK' | 'COMPLETED' | 'ABANDONED' | 'NO_RESULT';
  rules: ScoringRulesView;
  innings: InningsView[];
  needs: 'BATTER' | 'BOWLER' | null;
  freeHit: boolean;
  allowedDismissals: Record<DeliveryKind, DismissalType[]>;
  eligibleBatters: BatterChoice[];
  bowlers: BowlerChoice[];
  waiverRequired: boolean;
  result: ScoringResult | null;
  canUndo: CanUndo;
  editableUntil: string | null;
  locked: boolean;
  recentEvents: ScoringEvent[];
  /** The version of the scoring rules and the fingerprint of this state: what a phone compares with its own engine before scoring offline. */
  engineVersion: number;
  stateFingerprint: string;
}

export interface ScoringOutcome {
  view: ScoringView;
  replayed: boolean;
  eventSeq: number;
}

export interface ScoringConflict {
  currentSeq: number;
  view: ScoringView;
  eventsSince: ScoringEvent[];
}

export interface DismissalBody {
  type: DismissalType;
  batterOut?: string;
  end?: CreaseEnd;
  fielderId?: string;
}

export interface DeliveryBody {
  kind: DeliveryKind;
  runs: number;
  runsType?: RunsType;
  boundary?: boolean;
  dismissal?: DismissalBody;
}

export type CommandBody =
  | { type: 'START_INNINGS'; striker: string; nonStriker: string; bowler: string }
  | { type: 'DELIVER'; delivery: DeliveryBody }
  | { type: 'SELECT_BATTER'; batterId: string }
  | { type: 'SELECT_BOWLER'; bowlerId: string }
  | { type: 'CHANGE_BOWLER'; bowlerId: string }
  | { type: 'RETIRE_BATTER'; batterId: string; retirement: 'HURT' | 'OUT' }
  | { type: 'REVISE_TARGET'; target: number }
  | { type: 'END_INNINGS'; reason: 'DECLARED' | 'NO_BATTERS_LEFT' }
  | { type: 'END_MATCH'; reason: 'ABANDONED' | 'NO_RESULT' };

export type UndoMode = 'BALL' | 'COMMAND';
