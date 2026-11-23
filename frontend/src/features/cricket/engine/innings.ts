import { bowlerLimit, canNameFielder, creditedToBowler, dismissalViolation, eitherBatterCanBeOut, keepsCompletedRuns, rulesProblem, standardRules } from './rules';
import type {
  BatterLine,
  BowlerLine,
  Delivery,
  Dismissal,
  EndReason,
  FallOfWicket,
  InningsCommand,
  InningsState,
  MatchRules,
  Outcome,
  Over,
  OverShare,
  RuleViolation,
  Uuid,
  Waiver,
  WaiverKind,
} from './types';

/**
 * Port of `ScoringEngine` (and `InningsState`): the scoring rules of one innings as a pure function. `applyInnings(state, command)` returns the new state or the
 * rule that was broken, and a refused command changes nothing (the old state object is returned to the caller untouched, never mutated). No clock, no randomness.
 * Java is the oracle: when this file and the Java engine disagree, this file is wrong.
 */

/** Most runs of one kind (bat, bye, leg bye, wide) on one ball. The penalty of a wide or no-ball is added on top. */
export const MAX_RUNS = 7;

export const notStarted = (): InningsState => ({
  status: 'NOT_STARTED',
  endReason: null,
  overs: 0,
  rules: standardRules(),
  battingTeamId: null,
  bowlingTeamId: null,
  battingXi: [],
  bowlingXi: [],
  target: null,
  runs: 0,
  wickets: 0,
  legalBalls: 0,
  extras: { wides: 0, noBalls: 0, byes: 0, legByes: 0 },
  atBattingEnd: null,
  atBowlersEnd: null,
  batters: [],
  bowlers: [],
  currentBowler: null,
  previousOverBowler: null,
  over: freshOver(),
  freeHit: false,
  needsBatter: false,
  needsBowler: false,
  fallOfWickets: [],
  waivers: [],
});

const freshOver = (): Over => ({ deliveries: 0, legalBalls: 0, shares: [], maidenBroken: false });
const batterArrives = (playerId: Uuid): BatterLine => ({ playerId, runs: 0, balls: 0, fours: 0, sixes: 0, status: 'NOT_OUT', dismissal: null, bowlerId: null, fielderId: null });
const bowlerArrives = (playerId: Uuid): BowlerLine => ({ playerId, legalBalls: 0, creditedOvers: 0, maidens: 0, runs: 0, wickets: 0, wides: 0, noBalls: 0 });

const reject = (violation: RuleViolation, message: string): Outcome<InningsState> => ({ accepted: false, violation, message });
const accept = (state: InningsState, waivers: Waiver[] = []): Outcome<InningsState> => ({ accepted: true, state, waivers });

export const batterOf = (s: InningsState, id: Uuid): BatterLine | undefined => s.batters.find((b) => b.playerId === id);
export const bowlerOf = (s: InningsState, id: Uuid): BowlerLine | undefined => s.bowlers.find((b) => b.playerId === id);
export const yetToBat = (s: InningsState): Uuid[] => s.battingXi.filter((p) => batterOf(s, p) === undefined);
export const ballsRemaining = (s: InningsState): number => s.overs * s.rules.ballsPerOver - s.legalBalls;
export const isComplete = (s: InningsState): boolean => s.status === 'COMPLETE';

// ---- start ---------------------------------------------------------------------------------------------------------

/** What an innings is opened with. At match level the teams, XIs, rules and target come from the match setup. */
export interface InningsStart {
  rules: MatchRules | null;
  overs: number;
  battingTeamId: Uuid | null;
  bowlingTeamId: Uuid | null;
  battingXi: Uuid[] | null;
  bowlingXi: Uuid[] | null;
  striker: Uuid | null;
  nonStriker: Uuid | null;
  bowler: Uuid | null;
  target: number | null;
}

const validXi = (xi: Uuid[] | null, rules: MatchRules): boolean =>
  xi !== null && xi.length >= 2 && xi.length <= rules.playersPerSide && xi.every((p) => p !== null && p !== undefined) && new Set(xi).size === xi.length;

export function startInnings(s: InningsState, c: InningsStart): Outcome<InningsState> {
  if (s.status !== 'NOT_STARTED') {
    return reject('INNINGS_ALREADY_STARTED', 'This innings has already started');
  }
  if (c.rules === null || c.overs < 1 || c.overs > 100 || c.battingTeamId === null || c.bowlingTeamId === null || c.battingTeamId === c.bowlingTeamId) {
    return reject('INVALID_SETUP', 'An innings needs rules, 1 to 100 overs and two different teams');
  }
  const problem = rulesProblem(c.rules, c.overs);
  if (problem !== null) {
    return reject('INVALID_SETUP', problem);
  }
  if (!validXi(c.battingXi, c.rules) || !validXi(c.bowlingXi, c.rules) || c.battingXi!.some((p) => c.bowlingXi!.includes(p))) {
    return reject('INVALID_SETUP', `Each side needs 2 to ${c.rules.playersPerSide} different players, and nobody plays for both sides`);
  }
  const batting = c.battingXi!;
  const bowling = c.bowlingXi!;
  if (c.striker === null || c.nonStriker === null || c.striker === c.nonStriker || !batting.includes(c.striker) || !batting.includes(c.nonStriker)) {
    return reject('INVALID_OPENERS', 'The openers must be two different players from the batting XI');
  }
  if (c.bowler === null || !bowling.includes(c.bowler)) {
    return reject('BOWLER_NOT_IN_XI', 'The opening bowler must be in the bowling XI');
  }
  if (c.target !== null && c.target < 1) {
    return reject('INVALID_TARGET', 'The target must be at least 1');
  }
  return accept({
    status: 'IN_PROGRESS',
    endReason: null,
    overs: c.overs,
    rules: c.rules,
    battingTeamId: c.battingTeamId,
    bowlingTeamId: c.bowlingTeamId,
    battingXi: [...batting],
    bowlingXi: [...bowling],
    target: c.target,
    runs: 0,
    wickets: 0,
    legalBalls: 0,
    extras: { wides: 0, noBalls: 0, byes: 0, legByes: 0 },
    atBattingEnd: c.striker,
    atBowlersEnd: c.nonStriker,
    batters: [batterArrives(c.striker), batterArrives(c.nonStriker)],
    bowlers: [bowlerArrives(c.bowler)],
    currentBowler: c.bowler,
    previousOverBowler: null,
    over: freshOver(),
    freeHit: false,
    needsBatter: false,
    needsBowler: false,
    fallOfWickets: [],
    waivers: [],
  });
}

// ---- apply ---------------------------------------------------------------------------------------------------------

export function applyInnings(state: InningsState, command: InningsCommand): Outcome<InningsState> {
  switch (command.type) {
    case 'DELIVER':
      return deliver(state, command.delivery);
    case 'SELECT_BATTER':
      return selectBatter(state, command.batterId);
    case 'SELECT_BOWLER':
      return selectBowler(state, command.bowlerId);
    case 'CHANGE_BOWLER':
      return changeBowlerMidOver(state, command.bowlerId);
    case 'RETIRE_BATTER':
      return retire(state, command.batterId, command.retirement);
    case 'REVISE_TARGET':
      return reviseTarget(state, command.target);
    case 'END_INNINGS':
      return endInnings(state, command.reason);
  }
}

function requireActive(s: InningsState): Outcome<InningsState> | null {
  switch (s.status) {
    case 'NOT_STARTED':
      return reject('INNINGS_NOT_STARTED', 'The innings has not started');
    case 'COMPLETE':
      return reject('INNINGS_COMPLETE', 'The innings is complete');
    case 'IN_PROGRESS':
      return null;
  }
}

// ---- a ball --------------------------------------------------------------------------------------------------------

function deliver(s: InningsState, d: Delivery): Outcome<InningsState> {
  const blocked = requireActive(s);
  if (blocked !== null) {
    return blocked;
  }
  if (s.needsBatter) {
    return reject('NEEDS_NEW_BATTER', 'Choose the next batter before the next ball');
  }
  if (s.needsBowler) {
    return reject('NEEDS_NEW_BOWLER', 'Choose the bowler of the next over before the next ball');
  }
  const bad = checkDelivery(s, d);
  if (bad !== null) {
    return bad;
  }

  const w = new Work(s);
  const wide = d.kind === 'WIDE';
  const noBall = d.kind === 'NO_BALL';
  const legal = d.kind === 'NORMAL';
  const penalty = wide ? s.rules.widePenalty : noBall ? s.rules.noBallPenalty : 0;
  const bat = !wide && d.runsType === 'BAT' ? d.runs : 0;
  const byes = !wide && d.runsType === 'BYE' ? d.runs : 0;
  const legByes = !wide && d.runsType === 'LEG_BYE' ? d.runs : 0;
  const wideRuns = wide ? penalty + d.runs : 0;
  const noBallRuns = noBall ? penalty : 0;

  w.runs += bat + byes + legByes + wideRuns + noBallRuns;
  w.wides += wideRuns;
  w.noBalls += noBallRuns;
  w.byes += byes;
  w.legByes += legByes;

  const striker = w.atBatting;
  if (!wide) {
    w.updateBatter(striker, (b) => ({ ...b, runs: b.runs + bat, balls: b.balls + 1, fours: b.fours + (d.boundary && bat === 4 ? 1 : 0), sixes: b.sixes + (d.boundary && bat === 6 ? 1 : 0) }));
  }
  w.updateBowler(w.currentBowler, (b) => ({
    ...b,
    legalBalls: b.legalBalls + (legal ? 1 : 0),
    runs: b.runs + bat + wideRuns + noBallRuns,
    wides: b.wides + (wide ? 1 : 0),
    noBalls: b.noBalls + (noBall ? 1 : 0),
  }));
  if (legal) {
    w.legalBalls++;
    w.overLegal++;
  }
  w.overDeliveries++;
  w.addShare(w.currentBowler!, legal);
  if (bat > 0 || !legal) {
    w.maidenBroken = true;
  }
  if (noBall && s.rules.freeHitEnabled) {
    w.freeHit = true;
  } else if (legal) {
    w.freeHit = false;
  }

  const dismissal = d.dismissal;
  if (dismissal !== null && dismissal.type === 'RUN_OUT') {
    const out = dismissal.batterOut!;
    const survivor = out === w.atBatting ? w.atBowlers : w.atBatting;
    w.atBatting = null;
    w.atBowlers = null;
    if (dismissal.end === 'BATTING_END') {
      w.atBowlers = survivor;
    } else {
      w.atBatting = survivor;
    }
    w.dismiss(out, dismissal);
  } else {
    w.rotate(d.boundary ? 0 : d.runs);
    if (dismissal !== null) {
      const out = dismissal.batterOut ?? striker!;
      w.vacate(out);
      w.dismiss(out, dismissal);
    }
  }

  if (legal && w.overLegal === s.rules.ballsPerOver) {
    w.finishOver();
  }
  if (dismissal !== null) {
    w.afterRemoval();
  }
  w.finish(true);
  return accept(w.build());
}

function checkDelivery(s: InningsState, d: Delivery): Outcome<InningsState> | null {
  if (d.runs < 0 || d.runs > MAX_RUNS) {
    return reject('RUNS_OUT_OF_RANGE', `Runs on a ball are between 0 and ${MAX_RUNS}`);
  }
  if (d.boundary) {
    const four = d.runs === 4;
    const six = d.runs === 6 && d.kind !== 'WIDE' && d.runsType === 'BAT';
    if (!(four || six) || d.dismissal !== null) {
      return reject('INVALID_BOUNDARY', 'A boundary is a four, or a six off the bat, and is not a dismissal');
    }
  }
  const dismissal = d.dismissal;
  if (dismissal === null) {
    return null;
  }
  const type = dismissal.type;
  if (type === null || type === undefined) {
    return reject('INVALID_DELIVERY', 'A dismissal needs a type');
  }
  const notAllowed = dismissalViolation(d.kind, s.freeHit, type);
  if (notAllowed !== null) {
    return reject(notAllowed, `${type} cannot happen on this ball`);
  }
  const striker = s.atBattingEnd;
  if (eitherBatterCanBeOut(type)) {
    if (dismissal.batterOut === null) {
      return reject('DISMISSED_BATTER_REQUIRED', 'Say which batter is out');
    }
    if (dismissal.batterOut !== s.atBattingEnd && dismissal.batterOut !== s.atBowlersEnd) {
      return reject('BATTER_NOT_AT_CREASE', 'That batter is not at the crease');
    }
  } else if (dismissal.batterOut !== null && dismissal.batterOut !== striker) {
    return reject('ONLY_STRIKER_CAN_BE_OUT', `${type} can only dismiss the striker`);
  }
  if (type === 'RUN_OUT' && dismissal.end === null) {
    return reject('RUN_OUT_END_REQUIRED', 'Say at which end the batter was run out');
  }
  if (dismissal.fielderId !== null) {
    if (!canNameFielder(type)) {
      return reject('FIELDER_NOT_ALLOWED', 'A fielder can be named only for caught, stumped and run out');
    }
    if (!s.bowlingXi.includes(dismissal.fielderId)) {
      return reject('FIELDER_NOT_IN_XI', "The fielder must be in the fielding side's XI");
    }
  }
  if (!keepsCompletedRuns(type) && d.runs !== 0) {
    return reject('DISMISSAL_RUNS_NOT_ALLOWED', `${type} scores no runs off the ball`);
  }
  return null;
}

// ---- batters -------------------------------------------------------------------------------------------------------

function selectBatter(s: InningsState, id: Uuid): Outcome<InningsState> {
  const blocked = requireActive(s);
  if (blocked !== null) {
    return blocked;
  }
  if (!s.needsBatter) {
    return reject('NO_BATTER_NEEDED', 'No batter is needed right now');
  }
  if (id === null || id === undefined || !s.battingXi.includes(id)) {
    return reject('BATTER_NOT_IN_XI', 'That player is not in the batting XI');
  }
  if (id === s.atBattingEnd || id === s.atBowlersEnd) {
    return reject('BATTER_ALREADY_AT_CREASE', 'That batter is already at the crease');
  }
  const line = batterOf(s, id);
  if (line !== undefined && line.status !== 'RETIRED_HURT') {
    return reject('BATTER_ALREADY_OUT', 'That batter is out and cannot bat again');
  }
  const w = new Work(s);
  if (line !== undefined) {
    w.updateBatter(id, (b) => ({ ...b, status: 'NOT_OUT', dismissal: null, bowlerId: null, fielderId: null }));
  } else {
    w.batters.set(id, batterArrives(id));
  }
  if (w.atBatting === null) {
    w.atBatting = id;
  } else {
    w.atBowlers = id;
  }
  w.needsBatter = false;
  w.finish(false);
  return accept(w.build());
}

function retire(s: InningsState, id: Uuid, type: 'HURT' | 'OUT'): Outcome<InningsState> {
  const blocked = requireActive(s);
  if (blocked !== null) {
    return blocked;
  }
  if (s.needsBatter) {
    return reject('NEEDS_NEW_BATTER', 'Choose the next batter first');
  }
  if (type === null || type === undefined) {
    return reject('INVALID_RETIREMENT', 'Say whether the batter retired hurt or out');
  }
  if (id === null || id === undefined || !(id === s.atBattingEnd || id === s.atBowlersEnd)) {
    return reject('BATTER_NOT_AT_CREASE', 'That batter is not at the crease');
  }
  const w = new Work(s);
  w.vacate(id);
  if (type === 'HURT') {
    w.updateBatter(id, (b) => ({ ...b, status: 'RETIRED_HURT', dismissal: null, bowlerId: null, fielderId: null }));
  } else {
    w.wickets++;
    w.updateBatter(id, (b) => ({ ...b, status: 'RETIRED_OUT', dismissal: null, bowlerId: null, fielderId: null }));
    w.fow.push({ number: w.wickets, runs: w.runs, batterId: id, type: null, legalBalls: w.legalBalls });
  }
  w.afterRemoval();
  w.finish(false);
  return accept(w.build());
}

// ---- bowlers -------------------------------------------------------------------------------------------------------

function selectBowler(s: InningsState, id: Uuid): Outcome<InningsState> {
  const blocked = requireActive(s);
  if (blocked !== null) {
    return blocked;
  }
  if (!s.needsBowler) {
    return reject('NO_BOWLER_NEEDED', 'The over is not finished, so no new bowler is needed');
  }
  const refused = checkBowler(s, id, new Set());
  if (refused !== null) {
    return refused;
  }
  const w = new Work(s);
  const noted = recordWaiver(s, id, new Set(), w);
  w.currentBowler = id;
  if (!w.bowlers.has(id)) {
    w.bowlers.set(id, bowlerArrives(id));
  }
  w.needsBowler = false;
  return accept(w.build(), noted);
}

function changeBowlerMidOver(s: InningsState, id: Uuid): Outcome<InningsState> {
  const blocked = requireActive(s);
  if (blocked !== null) {
    return blocked;
  }
  if (s.needsBowler) {
    return reject('NEEDS_NEW_BOWLER', 'The over is finished: choose the bowler of the next over');
  }
  if (s.needsBatter) {
    return reject('NEEDS_NEW_BATTER', 'Choose the next batter first');
  }
  if (s.over.deliveries === 0) {
    return reject('NO_DELIVERY_IN_OVER', 'No ball has been bowled in this over yet');
  }
  if (id !== null && id === s.currentBowler) {
    return reject('SAME_BOWLER', 'That bowler is already bowling');
  }
  const inOver = new Set<Uuid>(s.over.shares.map((share) => share.bowlerId));
  if (id !== null && inOver.has(id)) {
    return reject('BOWLER_ALREADY_BOWLED_THIS_OVER', 'That bowler has already bowled in this over');
  }
  const refused = checkBowler(s, id, inOver);
  if (refused !== null) {
    return refused;
  }
  const w = new Work(s);
  const noted = recordWaiver(s, id, inOver, w);
  w.currentBowler = id;
  if (!w.bowlers.has(id)) {
    w.bowlers.set(id, bowlerArrives(id));
  }
  return accept(w.build(), noted);
}

/** Notes on the innings, and on the command's event, when this bowler is allowed only because a rule was waived as a last resort. */
function recordWaiver(s: InningsState, bowler: Uuid, excluded: Set<Uuid>, w: Work): Waiver[] {
  const kind = eligibility(s, excluded).waiver;
  if (kind === null) {
    return [];
  }
  const waiver: Waiver = { over: Math.trunc(s.legalBalls / s.rules.ballsPerOver), bowlerId: bowler, kind };
  w.waivers.push(waiver);
  return [waiver];
}

function checkBowler(s: InningsState, id: Uuid, excluded: Set<Uuid>): Outcome<InningsState> | null {
  if (id === null || id === undefined || !s.bowlingXi.includes(id)) {
    return reject('BOWLER_NOT_IN_XI', 'That player is not in the bowling XI');
  }
  if (!eligibility(s, excluded).bowlers.includes(id)) {
    return id === s.previousOverBowler
      ? reject('BOWLER_BOWLED_PREVIOUS_OVER', 'A bowler cannot bowl two overs in a row')
      : reject('BOWLER_LIMIT_REACHED', 'That bowler has bowled the most overs allowed');
  }
  return null;
}

interface Eligibility {
  bowlers: Uuid[];
  waiver: WaiverKind | null;
}

/**
 * Who may bowl next. Not the bowler of the previous over, and not one who has reached the limit. If that leaves nobody, the limit is waived so the innings
 * can go on; the rule against two overs in a row is waived only when no other bowler is left.
 */
function eligibility(s: InningsState, excluded: Set<Uuid>): Eligibility {
  const limit = bowlerLimit(s.rules, s.overs);
  const pool = s.bowlingXi.filter((b) => !excluded.has(b));
  const notPrevious = pool.filter((b) => b !== s.previousOverBowler);
  const underLimit = notPrevious.filter((b) => (bowlerOf(s, b)?.creditedOvers ?? 0) < limit);
  if (underLimit.length > 0) {
    return { bowlers: underLimit, waiver: null };
  }
  return notPrevious.length === 0 ? { bowlers: pool, waiver: 'CONSECUTIVE_OVER' } : { bowlers: notPrevious, waiver: 'OVER_LIMIT' };
}

export const eligibleBowlers = (s: InningsState, excluded: ReadonlySet<Uuid> = new Set()): Uuid[] => eligibility(s, new Set(excluded)).bowlers;
/** Which rule would have to be set aside for the next bowler to be anyone at all, or null when ordinary choices exist. */
export const waiverNeeded = (s: InningsState, excluded: ReadonlySet<Uuid> = new Set()): WaiverKind | null => eligibility(s, new Set(excluded)).waiver;

// ---- target and ending ---------------------------------------------------------------------------------------------

function reviseTarget(s: InningsState, target: number): Outcome<InningsState> {
  if (s.status === 'NOT_STARTED') {
    return reject('INNINGS_NOT_STARTED', 'The innings has not started');
  }
  if (s.target === null) {
    return reject('NO_TARGET_TO_REVISE', 'Only a chase has a target');
  }
  if (target < 1) {
    return reject('INVALID_TARGET', 'The target must be at least 1');
  }
  if (s.status === 'COMPLETE' && s.endReason === 'TARGET_REACHED' && s.runs < target) {
    return reject('TARGET_ALREADY_REACHED', 'The chase was already won at the old target');
  }
  const w = new Work(s);
  w.target = target;
  w.finish(false);
  return accept(w.build());
}

function endInnings(s: InningsState, reason: EndReason): Outcome<InningsState> {
  const blocked = requireActive(s);
  if (blocked !== null) {
    return blocked;
  }
  if (reason !== 'DECLARED' && reason !== 'NO_BATTERS_LEFT') {
    return reject('CANNOT_END_YET', 'An innings can be ended by the scorer only as declared or with no batters left');
  }
  if (reason === 'NO_BATTERS_LEFT' && yetToBat(s).length > 0) {
    return reject('CANNOT_END_YET', 'There are still batters who have not batted');
  }
  const w = new Work(s);
  w.complete(reason);
  return accept(w.build());
}

// ---- working copy --------------------------------------------------------------------------------------------------

/** A mutable copy of a state for one command. It never escapes: `build()` makes the new state, and the old one is never touched. */
class Work {
  status: InningsState['status'];
  endReason: EndReason | null;
  target: number | null;
  runs: number;
  wickets: number;
  legalBalls: number;
  wides: number;
  noBalls: number;
  byes: number;
  legByes: number;
  atBatting: Uuid | null;
  atBowlers: Uuid | null;
  readonly batters = new Map<Uuid, BatterLine>();
  readonly bowlers = new Map<Uuid, BowlerLine>();
  currentBowler: Uuid | null;
  previousOverBowler: Uuid | null;
  overDeliveries: number;
  overLegal: number;
  shares: OverShare[];
  maidenBroken: boolean;
  freeHit: boolean;
  needsBatter: boolean;
  needsBowler: boolean;
  fow: FallOfWicket[];
  waivers: Waiver[];

  constructor(private readonly base: InningsState) {
    this.status = base.status;
    this.endReason = base.endReason;
    this.target = base.target;
    this.runs = base.runs;
    this.wickets = base.wickets;
    this.legalBalls = base.legalBalls;
    this.wides = base.extras.wides;
    this.noBalls = base.extras.noBalls;
    this.byes = base.extras.byes;
    this.legByes = base.extras.legByes;
    this.atBatting = base.atBattingEnd;
    this.atBowlers = base.atBowlersEnd;
    base.batters.forEach((b) => this.batters.set(b.playerId, b));
    base.bowlers.forEach((b) => this.bowlers.set(b.playerId, b));
    this.currentBowler = base.currentBowler;
    this.previousOverBowler = base.previousOverBowler;
    this.overDeliveries = base.over.deliveries;
    this.overLegal = base.over.legalBalls;
    this.shares = base.over.shares.map((x) => ({ ...x }));
    this.maidenBroken = base.over.maidenBroken;
    this.freeHit = base.freeHit;
    this.needsBatter = base.needsBatter;
    this.needsBowler = base.needsBowler;
    this.fow = base.fallOfWickets.map((x) => ({ ...x }));
    this.waivers = base.waivers.map((x) => ({ ...x }));
  }

  /** Java's `computeIfPresent`: a missing (or null) id changes nothing. */
  updateBatter(id: Uuid | null, change: (b: BatterLine) => BatterLine): void {
    const line = id === null ? undefined : this.batters.get(id);
    if (line !== undefined) {
      this.batters.set(id!, change(line));
    }
  }

  updateBowler(id: Uuid | null, change: (b: BowlerLine) => BowlerLine): void {
    const line = id === null ? undefined : this.bowlers.get(id);
    if (line !== undefined) {
      this.bowlers.set(id!, change(line));
    }
  }

  rotate(crossings: number): void {
    if (crossings % 2 === 1 && this.atBatting !== null && this.atBowlers !== null) {
      this.swapEnds();
    }
  }

  /** Swaps the batters, except that a lone batter (last man stands) never moves; `finish` puts him back on strike. */
  swapEnds(): void {
    const t = this.atBatting;
    this.atBatting = this.atBowlers;
    this.atBowlers = t;
  }

  vacate(id: Uuid): void {
    if (id === this.atBatting) {
      this.atBatting = null;
    } else if (id === this.atBowlers) {
      this.atBowlers = null;
    }
  }

  dismiss(id: Uuid, dismissal: Dismissal): void {
    const type = dismissal.type;
    this.wickets++;
    const credited = creditedToBowler(type);
    const bowler = this.currentBowler;
    this.updateBatter(id, (b) => ({ ...b, status: 'OUT', dismissal: type, bowlerId: credited ? bowler : null, fielderId: dismissal.fielderId }));
    this.fow.push({ number: this.wickets, runs: this.runs, batterId: id, type, legalBalls: this.legalBalls });
    if (credited) {
      this.updateBowler(bowler, (b) => ({ ...b, wickets: b.wickets + 1 }));
    }
  }

  addShare(bowler: Uuid, legal: boolean): void {
    const i = this.shares.findIndex((x) => x.bowlerId === bowler);
    if (i >= 0) {
      this.shares[i] = { bowlerId: bowler, legalBalls: this.shares[i].legalBalls + (legal ? 1 : 0) };
      return;
    }
    this.shares.push({ bowlerId: bowler, legalBalls: legal ? 1 : 0 });
  }

  /** The over counts for the bowler of most legal balls; a tie goes to whoever bowled first. Then the batters swap and a new bowler is needed. */
  finishOver(): void {
    let credited = this.shares[0];
    for (const share of this.shares) {
      if (share.legalBalls > credited.legalBalls) {
        credited = share;
      }
    }
    const maiden = !this.maidenBroken;
    this.updateBowler(credited.bowlerId, (b) => ({ ...b, creditedOvers: b.creditedOvers + 1, maidens: b.maidens + (maiden ? 1 : 0) }));
    this.swapEnds();
    this.previousOverBowler = this.currentBowler;
    this.overDeliveries = 0;
    this.overLegal = 0;
    this.shares = [];
    this.maidenBroken = false;
    this.needsBowler = true;
  }

  /** After a batter has gone: ask for the next one if there is anybody who can bat, otherwise the innings is over (or the lone batter carries on). */
  afterRemoval(): void {
    const atCrease = new Set<Uuid>();
    if (this.atBatting !== null) {
      atCrease.add(this.atBatting);
    }
    if (this.atBowlers !== null) {
      atCrease.add(this.atBowlers);
    }
    const someoneCanBat = this.base.battingXi.some((p) => !atCrease.has(p) && (!this.batters.has(p) || this.batters.get(p)!.status === 'RETIRED_HURT'));
    if (someoneCanBat) {
      this.needsBatter = true;
    } else if (atCrease.size === 0 || !this.base.rules.lastManStands) {
      this.complete('ALL_OUT');
    }
  }

  complete(reason: EndReason): void {
    this.status = 'COMPLETE';
    this.endReason = reason;
    this.needsBatter = false;
    this.needsBowler = false;
  }

  /**
   * The end-of-ball checks: the target, the last over, and a lone batter standing at the batting end. On the ball just bowled, winning the chase counts even
   * if the same ball also bowled the side out.
   */
  finish(afterBall: boolean): void {
    const reached = this.target !== null && this.runs >= this.target;
    if (reached && (this.status === 'IN_PROGRESS' || (afterBall && this.status === 'COMPLETE'))) {
      this.complete('TARGET_REACHED');
    } else if (this.status === 'IN_PROGRESS' && this.legalBalls === this.base.overs * this.base.rules.ballsPerOver) {
      this.complete('OVERS_COMPLETE');
    }
    if (this.status === 'IN_PROGRESS' && !this.needsBatter && this.atBatting === null && this.atBowlers !== null) {
      this.atBatting = this.atBowlers;
      this.atBowlers = null;
    }
  }

  build(): InningsState {
    const b = this.base;
    return {
      status: this.status,
      endReason: this.endReason,
      overs: b.overs,
      rules: b.rules,
      battingTeamId: b.battingTeamId,
      bowlingTeamId: b.bowlingTeamId,
      battingXi: b.battingXi,
      bowlingXi: b.bowlingXi,
      target: this.target,
      runs: this.runs,
      wickets: this.wickets,
      legalBalls: this.legalBalls,
      extras: { wides: this.wides, noBalls: this.noBalls, byes: this.byes, legByes: this.legByes },
      atBattingEnd: this.atBatting,
      atBowlersEnd: this.atBowlers,
      batters: [...this.batters.values()],
      bowlers: [...this.bowlers.values()],
      currentBowler: this.currentBowler,
      previousOverBowler: this.previousOverBowler,
      over: { deliveries: this.overDeliveries, legalBalls: this.overLegal, shares: this.shares, maidenBroken: this.maidenBroken },
      freeHit: this.freeHit,
      needsBatter: this.needsBatter,
      needsBowler: this.needsBowler,
      fallOfWickets: this.fow,
      waivers: this.waivers,
    };
  }
}
