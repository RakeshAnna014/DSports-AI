import type { VOCABULARY } from './vocabulary';

/**
 * The engine's data, in exactly the JSON shape the Java codec writes (`ScoringJsonCodec`), so a state is its own fingerprint input and a stored state from
 * the server can be read without conversion. Ids are lower-case UUID text; absent values are `null`, never `undefined`. Everything here is plain data: it
 * is copied, never mutated (the engine builds a new object for every accepted command).
 */
type Word<K extends keyof typeof VOCABULARY> = (typeof VOCABULARY)[K][number];

export type Uuid = string;
export type RuleViolation = Word<'violations'>;
export type DismissalType = Word<'dismissalTypes'>;
export type CreaseEnd = Word<'creaseEnds'>;
export type DeliveryKind = Word<'deliveryKinds'>;
export type RunsType = Word<'runsTypes'>;
export type EndReason = Word<'endReasons'>;
export type RetirementType = Word<'retirementTypes'>;
export type EndMatchReason = Word<'endMatchReasons'>;
export type WaiverKind = Word<'waiverKinds'>;
export type UndoMode = Word<'undoModes'>;
export type MatchStatus = Word<'matchStatuses'>;
export type ResultType = Word<'resultTypes'>;
export type NextAction = Word<'nextActions'>;
export type InningsStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETE';
export type BatterStatus = 'NOT_OUT' | 'OUT' | 'RETIRED_HURT' | 'RETIRED_OUT';

export interface MatchRules {
  ballsPerOver: number;
  playersPerSide: number;
  maxOversPerBowler: number | null;
  widePenalty: number;
  noBallPenalty: number;
  freeHitEnabled: boolean;
  lastManStands: boolean;
}

export interface Dismissal {
  type: DismissalType;
  batterOut: Uuid | null;
  end: CreaseEnd | null;
  fielderId: Uuid | null;
}

export interface Delivery {
  kind: DeliveryKind;
  runs: number;
  /** Always null for a wide (the engine ignores any runs type a wide carries). */
  runsType: RunsType | null;
  boundary: boolean;
  dismissal: Dismissal | null;
}

/** An innings command: what the innings engine understands (starting an innings is match level, see `ScoreCommand`). */
export type InningsCommand =
  | { type: 'DELIVER'; delivery: Delivery }
  | { type: 'SELECT_BATTER'; batterId: Uuid }
  | { type: 'SELECT_BOWLER'; bowlerId: Uuid }
  | { type: 'CHANGE_BOWLER'; bowlerId: Uuid }
  | { type: 'RETIRE_BATTER'; batterId: Uuid; retirement: RetirementType }
  | { type: 'REVISE_TARGET'; target: number }
  | { type: 'END_INNINGS'; reason: EndReason };

/** The engine's own opening command; the match fills in the teams, XIs, rules and target. */
export interface StartInningsCommand {
  type: 'START_INNINGS';
  striker: Uuid;
  nonStriker: Uuid;
  bowler: Uuid;
}

export interface EndMatchCommand {
  type: 'END_MATCH';
  reason: EndMatchReason;
}

/** What a scorer can ask for at match level (the body the phone sends, once parsed). */
export type ScoreCommand = StartInningsCommand | InningsCommand | EndMatchCommand;
export type ScoreCommandType = ScoreCommand['type'];

export interface Extras {
  wides: number;
  noBalls: number;
  byes: number;
  legByes: number;
}

export interface BatterLine {
  playerId: Uuid;
  runs: number;
  balls: number;
  fours: number;
  sixes: number;
  status: BatterStatus;
  dismissal: DismissalType | null;
  bowlerId: Uuid | null;
  fielderId: Uuid | null;
}

export interface BowlerLine {
  playerId: Uuid;
  legalBalls: number;
  creditedOvers: number;
  maidens: number;
  runs: number;
  wickets: number;
  wides: number;
  noBalls: number;
}

export interface FallOfWicket {
  number: number;
  runs: number;
  batterId: Uuid;
  type: DismissalType | null;
  legalBalls: number;
}

export interface Waiver {
  /** The 0-based number of the over (the first over is 0). */
  over: number;
  bowlerId: Uuid;
  kind: WaiverKind;
}

export interface OverShare {
  bowlerId: Uuid;
  legalBalls: number;
}

export interface Over {
  deliveries: number;
  legalBalls: number;
  shares: OverShare[];
  maidenBroken: boolean;
}

export interface InningsState {
  status: InningsStatus;
  endReason: EndReason | null;
  overs: number;
  rules: MatchRules;
  battingTeamId: Uuid | null;
  bowlingTeamId: Uuid | null;
  battingXi: Uuid[];
  bowlingXi: Uuid[];
  target: number | null;
  runs: number;
  wickets: number;
  legalBalls: number;
  extras: Extras;
  atBattingEnd: Uuid | null;
  atBowlersEnd: Uuid | null;
  batters: BatterLine[];
  bowlers: BowlerLine[];
  currentBowler: Uuid | null;
  previousOverBowler: Uuid | null;
  over: Over;
  freeHit: boolean;
  needsBatter: boolean;
  needsBowler: boolean;
  fallOfWickets: FallOfWicket[];
  waivers: Waiver[];
}

export interface MatchScore {
  innings: InningsState[];
  endedBy: EndMatchReason | null;
}

export interface MatchResult {
  type: ResultType;
  winnerTeamId: Uuid | null;
  margin: number;
  ballsRemaining: number;
}

/** What the match fixes before a ball is bowled (the playing XIs are in batting order). */
export interface MatchSetup {
  overs: number;
  rules: MatchRules;
  teamA: Uuid;
  teamB: Uuid;
  battingFirst: Uuid;
  xiA: Uuid[];
  xiB: Uuid[];
}

/** The state recorded in the vectors and fingerprinted: the score, the result (null until complete) and the status. */
export interface ScoreState {
  score: MatchScore;
  result: MatchResult | null;
  status: MatchStatus;
}

export type Accepted<S> = { accepted: true; state: S; waivers: Waiver[] };
export type Rejected = { accepted: false; violation: RuleViolation; message: string };
export type Outcome<S> = Accepted<S> | Rejected;
