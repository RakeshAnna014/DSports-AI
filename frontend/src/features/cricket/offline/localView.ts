import { bowlerLimit, currentInnings, dismissalViolation, eligibleBowlers, foldMatch, matchResult, matchStatus, planUndo, stateFingerprint, waiverNeeded, yetToBat, effective, ENGINE_VERSION, VOCABULARY } from '../engine';
import type { InningsState, LogEntry, MatchScore, MatchSetup, ScoreCommand, ScoringLog, Uuid } from '../engine';
import type {
  BatterChoice,
  BowlerChoice,
  CanUndo,
  DeliveryKind,
  DismissalType,
  InningsView,
  Ref,
  ScoringEvent,
  ScoringResult,
  ScoringView,
} from '../../../types/cricketScoring';

/**
 * The scorer's view of the match built on the phone from its own log: a port of the Java `ScoringViewBuilder` and `Scorecard`. The pad takes every choice from a
 * `ScoringView` (what is needed next, what the next ball may be, who may bat or bowl, what an undo would do), so a phone with no signal needs the same view the
 * server would send. `localView.parity.test.ts` proves it equal to the Java view, step by step, on the golden match; names come from the last view the server sent.
 */
export interface NameBook {
  player(id: Uuid): string;
  team(id: Uuid): string;
}

/** What the phone cannot work out by itself and takes from the last server view: the correction window and, for display, the time of confirmed events. */
export interface ViewContext {
  matchId: string;
  names: NameBook;
  /** The server's `editableUntil` and `locked` from its last answer (the window is the server's, by its own clock). */
  editableUntil: string | null;
  locked: boolean;
  /** Time shown for an event: by seq, from the server's last view; unknown seqs show no time. */
  eventTimes: ReadonlyMap<number, string>;
  /** How the phone's own events are shown (the server's own view says the scorer's name). An entry that carries a client event id is the phone's own: the server leaves it out for everybody else's. */
  ownLabel: string;
}

/** "Player" for a name the phone has never been told (never an id). */
export const nameBookFrom = (view: ScoringView): NameBook => {
  const players = new Map<string, string>();
  const teams = new Map<string, string>();
  const add = (r: Ref | null | undefined) => {
    if (r) {
      players.set(r.id, r.name);
    }
  };
  for (const innings of view.innings) {
    teams.set(innings.battingTeam.id, innings.battingTeam.name);
    teams.set(innings.bowlingTeam.id, innings.bowlingTeam.name);
    [innings.striker, innings.nonStriker, innings.bowler].forEach(add);
    innings.batters.forEach((b) => add(b.player));
    innings.yetToBat.forEach(add);
    innings.bowlers.forEach((b) => add(b.player));
    innings.fallOfWickets.forEach((f) => add(f.batter));
    innings.waivers.forEach((w) => add(w.bowler));
  }
  view.eligibleBatters.forEach((c) => add(c.player));
  view.bowlers.forEach((c) => add(c.player));
  return { player: (id) => players.get(id) ?? 'Player', team: (id) => teams.get(id) ?? 'Team' };
};

const plural = (n: number, word: string): string => `${n} ${word}${n === 1 ? '' : 's'}`;
const oversText = (balls: number, bpo: number): string => `${Math.trunc(balls / bpo)}.${balls % bpo}`;

/** Runs per over to two places, half up, as the Java `BigDecimal` does (integer arithmetic: no floating-point surprise). */
export const economy = (runs: number, legalBalls: number, bpo: number): number => (legalBalls === 0 ? 0 : Math.floor((2 * runs * bpo * 100 + legalBalls) / (2 * legalBalls)) / 100);

// ---- how a batter was out (Scorecard.howOut) -----------------------------------------------------------------------------

function howOut(line: InningsState['batters'][number], name: (id: Uuid) => string): string {
  switch (line.status) {
    case 'NOT_OUT':
      return 'not out';
    case 'RETIRED_HURT':
      return 'retired hurt';
    case 'RETIRED_OUT':
      return 'retired out';
    case 'OUT': {
      const bowler = line.bowlerId === null ? null : name(line.bowlerId);
      const fielder = line.fielderId === null ? null : name(line.fielderId);
      switch (line.dismissal) {
        case 'BOWLED':
          return `b ${bowler}`;
        case 'CAUGHT':
          return line.fielderId === null ? `caught b ${bowler}` : line.fielderId === line.bowlerId ? `c & b ${bowler}` : `c ${fielder} b ${bowler}`;
        case 'STUMPED':
          return line.fielderId === null ? `stumped b ${bowler}` : `st ${fielder} b ${bowler}`;
        case 'LBW':
          return `lbw b ${bowler}`;
        case 'HIT_WICKET':
          return `hit wicket b ${bowler}`;
        case 'RUN_OUT':
          return fielder === null ? 'run out' : `run out (${fielder})`;
        case 'HIT_BALL_TWICE':
          return 'hit the ball twice';
        default:
          return 'obstructing the field';
      }
    }
  }
}

// ---- words for a command (describe) -----------------------------------------------------------------------------------------

type Delivery = Extract<ScoreCommand, { type: 'DELIVER' }>['delivery'];

function ball(d: Delivery, who: (id: Uuid) => string): string {
  let text: string;
  if (d.kind === 'WIDE') {
    text = `wide${d.runs > 0 ? ` + ${d.runs}` : ''}`;
  } else if (d.kind === 'NO_BALL') {
    text = `no ball${d.runs > 0 ? ` + ${d.runs}${d.runsType === 'BAT' ? '' : d.runsType === 'BYE' ? ' byes' : ' leg byes'}` : ''}`;
  } else if (d.runsType === 'BYE') {
    text = plural(d.runs, 'bye');
  } else if (d.runsType === 'LEG_BYE') {
    text = plural(d.runs, 'leg bye');
  } else {
    text = d.runs === 0 ? 'no run' : d.boundary ? (d.runs === 4 ? 'FOUR' : 'SIX') : plural(d.runs, 'run');
  }
  if (d.dismissal === null) {
    return text;
  }
  const type = d.dismissal.type.toLowerCase().replace(/_/g, ' ');
  const batter = d.dismissal.batterOut === null ? '' : `${who(d.dismissal.batterOut)} `;
  return `${text}, wicket: ${batter}${type}`;
}

export function describe(command: ScoreCommand, who: (id: Uuid) => string): string {
  switch (command.type) {
    case 'START_INNINGS':
      return `innings started: ${who(command.striker)} and ${who(command.nonStriker)} open, ${who(command.bowler)} bowls`;
    case 'END_MATCH':
      return command.reason === 'ABANDONED' ? 'match abandoned' : 'no result';
    case 'DELIVER':
      return ball(command.delivery, who);
    case 'SELECT_BATTER':
      return `${who(command.batterId)} comes in`;
    case 'SELECT_BOWLER':
      return `${who(command.bowlerId)} to bowl`;
    case 'CHANGE_BOWLER':
      return `${who(command.bowlerId)} takes over the over`;
    case 'RETIRE_BATTER':
      return `${who(command.batterId)}${command.retirement === 'HURT' ? ' retired hurt' : ' retired out'}`;
    case 'REVISE_TARGET':
      return `target revised to ${command.target}`;
    case 'END_INNINGS':
      return command.reason === 'DECLARED' ? 'innings declared' : 'innings closed: no batters left';
  }
}

/** The short label of a ball on the "this over" strip: a dot, a number, W, Wd, Nb, B, Lb. */
export function chip(d: Delivery): string {
  let label: string;
  if (d.kind === 'WIDE') {
    label = `Wd${d.runs > 0 ? `+${d.runs}` : ''}`;
  } else if (d.kind === 'NO_BALL') {
    label = `Nb${d.runs > 0 ? `+${d.runs}` : ''}`;
  } else if (d.runsType === 'BYE') {
    label = `B${d.runs}`;
  } else if (d.runsType === 'LEG_BYE') {
    label = `Lb${d.runs}`;
  } else {
    label = d.runs === 0 ? '•' : String(d.runs);
  }
  return d.dismissal === null ? label : label === '•' ? 'W' : `${label}W`;
}

function thisOver(live: ReturnType<typeof effective>, state: InningsState): string[] {
  if (state.over.deliveries === 0 && !state.needsBowler) {
    return [];
  }
  const deliveries: Delivery[] = [];
  for (let i = live.length - 1; i >= 0; i--) {
    const command = live[i].command;
    if (command.type === 'START_INNINGS') {
      break;
    }
    if (command.type === 'DELIVER') {
      deliveries.unshift(command.delivery);
    }
  }
  const bpo = state.rules.ballsPerOver;
  const segments: Delivery[][] = [];
  let segment: Delivery[] = [];
  let legal = 0;
  for (const d of deliveries) {
    segment.push(d);
    legal += d.kind === 'NORMAL' ? 1 : 0;
    if (d.kind === 'NORMAL' && legal === bpo) {
      segments.push(segment);
      segment = [];
      legal = 0;
    }
  }
  const chosen = state.needsBowler ? (segments.length === 0 ? [] : segments[segments.length - 1]) : segment;
  return chosen.map(chip);
}

// ---- the view ---------------------------------------------------------------------------------------------------------------------

const refOf = (id: Uuid | null, names: NameBook): Ref | null => (id === null ? null : { id, name: names.player(id) });

function inningsView(number: number, s: InningsState, over: string[], names: NameBook): InningsView {
  const bpo = s.rules.ballsPerOver;
  const who = names.player;
  const extras = s.extras;
  return {
    number,
    battingTeam: { id: s.battingTeamId!, name: names.team(s.battingTeamId!) },
    bowlingTeam: { id: s.bowlingTeamId!, name: names.team(s.bowlingTeamId!) },
    status: s.status as 'IN_PROGRESS' | 'COMPLETE',
    endReason: s.endReason,
    runs: s.runs,
    wickets: s.wickets,
    overs: oversText(s.legalBalls, bpo),
    ballsRemaining: s.overs * bpo - s.legalBalls,
    target: s.target,
    runsNeeded: s.target === null ? null : Math.max(0, s.target - s.runs),
    extras: { wides: extras.wides, noBalls: extras.noBalls, byes: extras.byes, legByes: extras.legByes, total: extras.wides + extras.noBalls + extras.byes + extras.legByes },
    striker: refOf(s.atBattingEnd, names),
    nonStriker: refOf(s.atBowlersEnd, names),
    bowler: refOf(s.currentBowler, names),
    thisOver: over,
    batters: s.batters.map((line) => ({
      player: { id: line.playerId, name: who(line.playerId) },
      runs: line.runs,
      balls: line.balls,
      fours: line.fours,
      sixes: line.sixes,
      howOut: howOut(line, who),
      notOut: line.status === 'NOT_OUT' || line.status === 'RETIRED_HURT',
      atCrease: line.playerId === s.atBattingEnd || line.playerId === s.atBowlersEnd,
    })),
    yetToBat: yetToBat(s).map((id) => ({ id, name: who(id) })),
    bowlers: s.bowlers.map((b) => ({
      player: { id: b.playerId, name: who(b.playerId) },
      overs: oversText(b.legalBalls, bpo),
      creditedOvers: b.creditedOvers,
      maidens: b.maidens,
      runs: b.runs,
      wickets: b.wickets,
      wides: b.wides,
      noBalls: b.noBalls,
      economy: economy(b.runs, b.legalBalls, bpo),
    })),
    fallOfWickets: s.fallOfWickets.map((f) => ({
      number: f.number,
      runs: f.runs,
      batter: { id: f.batterId, name: who(f.batterId) },
      overs: oversText(f.legalBalls, bpo),
      howOut: howOut(s.batters.find((b) => b.playerId === f.batterId)!, who),
    })),
    waivers: s.waivers.map((w) => ({
      over: w.over + 1,
      bowler: { id: w.bowlerId, name: who(w.bowlerId) },
      kind: w.kind,
      text:
        w.kind === 'OVER_LIMIT'
          ? `${who(w.bowlerId)} bowled over ${w.over + 1} beyond the over limit because every other bowler was at it`
          : `${who(w.bowlerId)} bowled two overs in a row in over ${w.over + 1} because no other bowler was left`,
    })),
  };
}

function allowedDismissals(current: InningsState | null): Record<DeliveryKind, DismissalType[]> {
  const freeHit = current !== null && current.freeHit;
  const out = {} as Record<DeliveryKind, DismissalType[]>;
  for (const kind of VOCABULARY.deliveryKinds) {
    out[kind] = VOCABULARY.dismissalTypes.filter((t) => dismissalViolation(kind, freeHit, t) === null);
  }
  return out;
}

function eligibleBatters(current: InningsState | null, names: NameBook): BatterChoice[] {
  if (current === null || !current.needsBatter) {
    return [];
  }
  const choices: BatterChoice[] = [];
  for (const id of current.battingXi) {
    if (id === current.atBattingEnd || id === current.atBowlersEnd) {
      continue;
    }
    const line = current.batters.find((b) => b.playerId === id);
    if (line === undefined) {
      choices.push({ player: { id, name: names.player(id) }, returning: false, note: null });
    } else if (line.status === 'RETIRED_HURT') {
      choices.push({ player: { id, name: names.player(id) }, returning: true, note: `retired hurt, ${line.runs} off ${line.balls}` });
    }
  }
  return choices;
}

function bowlerChoices(current: InningsState | null, limit: number, names: NameBook): BowlerChoice[] {
  if (current === null) {
    return [];
  }
  const inOver = new Set(current.over.shares.map((x) => x.bowlerId));
  const next = current.needsBowler ? eligibleBowlers(current) : [];
  const midOver = !current.needsBowler && !current.needsBatter && current.over.deliveries > 0 && current.status !== 'COMPLETE';
  const replace = midOver ? eligibleBowlers(current, inOver) : [];
  return current.bowlingXi.map((id) => {
    const line = current.bowlers.find((b) => b.playerId === id);
    const canNext = next.includes(id);
    const canReplace = replace.includes(id) && id !== current.currentBowler;
    let why: BowlerChoice['why'] = null;
    if (current.needsBowler && !canNext) {
      why = id === current.previousOverBowler ? 'BOWLED_LAST_OVER' : 'AT_LIMIT';
    }
    return {
      player: { id, name: names.player(id) },
      overs: oversText(line?.legalBalls ?? 0, current.rules.ballsPerOver),
      creditedOvers: line?.creditedOvers ?? 0,
      limit,
      canBowlNext: canNext,
      canReplaceMidOver: canReplace,
      why,
    };
  });
}

function resultOf(score: MatchScore, names: NameBook): ScoringResult | null {
  if (score.endedBy !== null) {
    return { type: score.endedBy, winner: null, margin: 0, ballsRemaining: 0, text: score.endedBy === 'ABANDONED' ? 'Match abandoned' : 'No result' };
  }
  const r = matchResult(score);
  if (r === null) {
    return null;
  }
  const winner = r.winnerTeamId === null ? null : { id: r.winnerTeamId, name: names.team(r.winnerTeamId) };
  const text =
    r.type === 'WON_BY_WICKETS'
      ? `${winner!.name} won by ${plural(r.margin, 'wicket')} (${plural(r.ballsRemaining, 'ball')} left)`
      : r.type === 'WON_BY_RUNS'
        ? `${winner!.name} won by ${plural(r.margin, 'run')}`
        : r.type === 'TIE'
          ? 'Match tied'
          : '';
  return { type: r.type, winner, margin: r.margin, ballsRemaining: r.ballsRemaining, text };
}

function reopens(before: MatchScore, after: MatchScore): boolean {
  if (after.innings.length < before.innings.length) {
    return true;
  }
  const was = matchStatus(before);
  return was !== matchStatus(after) && (was === 'INNINGS_BREAK' || was === 'COMPLETED' || was === 'ABANDONED' || was === 'NO_RESULT');
}

function canUndoOf(entries: readonly LogEntry[], live: ReturnType<typeof effective>, score: MatchScore, setup: MatchSetup, locked: boolean, names: NameBook): CanUndo {
  if (locked) {
    return { allowed: false, describe: null, crossesInnings: false, blockedReason: 'LOCKED' };
  }
  const voids = planUndo(entries, 'BALL');
  if (voids.length === 0) {
    return { allowed: false, describe: null, crossesInnings: false, blockedReason: 'NOTHING_TO_UNDO' };
  }
  const voided = new Set(voids);
  const after = foldMatch(setup, live.filter((e) => !voided.has(e.seq)).map((e) => e.command));
  const anchor = live.find((e) => e.seq === voids[voids.length - 1])!;
  return { allowed: true, describe: `Undo: ${describe(anchor.command, names.player)}`, crossesInnings: reopens(score, after), blockedReason: null };
}

const RECENT_EVENTS = 12;

function eventOf(e: LogEntry, entries: readonly LogEntry[], ctx: ViewContext): ScoringEvent {
  const own = e.clientEventId !== null;
  const by: Ref = own ? { id: 'you', name: ctx.ownLabel } : { id: 'someone', name: 'A scorer' };
  const common = { seq: e.seq, by, at: ctx.eventTimes.get(e.seq) ?? '', mine: own, clientEventId: own ? e.clientEventId : null, tappedAt: null };
  const who = ctx.names.player;
  switch (e.kind) {
    case 'COMMAND':
      return { ...common, kind: e.command.type, text: describe(e.command, who) };
    case 'UNDO': {
      const undone = e.voids.map((seq) => entries.find((x) => x.seq === seq && x.kind === 'COMMAND')).filter((x): x is Extract<LogEntry, { kind: 'COMMAND' }> => x !== undefined).map((x) => describe(x.command, who));
      return { ...common, kind: 'UNDO', text: `Undid: ${undone.join('; ')}` };
    }
    case 'ADMIN_UNLOCK':
      return { ...common, kind: 'ADMIN_UNLOCK', text: 'Reopened by an administrator' };
  }
}

/** The scorer's view of the match, from the phone's own log. The pad, the scoreboard and the undo button read this exactly as they read the server's. */
export function buildLocalView(log: ScoringLog, ctx: ViewContext): ScoringView {
  const { setup, entries, score } = log;
  const live = effective(entries);
  const current = currentInnings(score);
  const innings = score.innings.map((state, i) => inningsView(i + 1, state, i === score.innings.length - 1 ? thisOver(live, state) : [], ctx.names));
  const limit = bowlerLimit(setup.rules, setup.overs);
  const recent = entries.slice(Math.max(0, entries.length - RECENT_EVENTS)).map((e) => eventOf(e, entries, ctx));
  return {
    matchId: ctx.matchId,
    seq: entries.length === 0 ? 0 : entries[entries.length - 1].seq,
    matchStatus: matchStatus(score) as ScoringView['matchStatus'],
    rules: { overs: setup.overs, ballsPerOver: setup.rules.ballsPerOver, playersPerSide: setup.rules.playersPerSide, maxOversPerBowler: limit },
    innings,
    needs: current === null ? null : current.needsBatter ? 'BATTER' : current.needsBowler ? 'BOWLER' : null,
    freeHit: current !== null && current.freeHit,
    allowedDismissals: allowedDismissals(current),
    eligibleBatters: eligibleBatters(current, ctx.names),
    bowlers: bowlerChoices(current, limit, ctx.names),
    waiverRequired: current !== null && current.needsBowler && waiverNeeded(current) !== null,
    result: resultOf(score, ctx.names),
    canUndo: canUndoOf(entries, live, score, setup, ctx.locked, ctx.names),
    editableUntil: ctx.editableUntil,
    locked: ctx.locked,
    recentEvents: recent,
    engineVersion: ENGINE_VERSION,
    stateFingerprint: stateFingerprint(log),
  };
}
