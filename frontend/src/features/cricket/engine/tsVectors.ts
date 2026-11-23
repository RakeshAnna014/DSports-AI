import { canonicalJson, type Json } from './fingerprint';
import { eligibleBowlers, yetToBat } from './innings';
import { newLog, stepCommand, stepUndo, type ScoringLog } from './log';
import { currentInnings, ENGINE_VERSION, isFinished } from './match';
import { dismissalViolation, canNameFielder, eitherBatterCanBeOut, keepsCompletedRuns } from './rules';
import { sha256Bytes, toHex } from './sha256';
import type { Delivery, DeliveryKind, Dismissal, DismissalType, InningsCommand, InningsState, MatchRules, MatchSetup, ScoreCommand, UndoMode, Uuid } from './types';
import { VOCABULARY } from './vocabulary';
import { expectOf } from './vectorExpect';
import { toBody } from './wire';

/**
 * The TypeScript half of the two-way engine vectors. A seeded generator (mulberry32, no new dependency) plays random matches through the TypeScript engine and
 * records its answer to every step in `testdata/cricket-engine/from-ts/`. The Java build replays those files through the Java engine (`TsVectorsReplayTest`), so
 * the failure that matters most for offline scoring, "the phone accepted a ball the server refuses", is a failing test and not a stalled queue.
 *
 * The generator is not a port of the Java `RandomScorer`: it leans towards commands the engine will accept (so the Java engine is asked to accept a lot of
 * them) and still sends a share of junk. Only this file's output is ever compared, never its random numbers, so the two languages never have to share a generator.
 */

export const TS_SEEDS = 100;
const STATE_EVERY = 25;
const MIN_STEPS = 90;
const EXTRA_STEPS = 80;
const STEPS_AFTER_THE_END = 4;
const FORMAT = 1;

/** mulberry32: a small, well-known 32-bit generator. The same seed always gives the same numbers on every platform (only integer maths is used). */
export class Rng {
  private a: number;
  constructor(seed: number) {
    this.a = seed >>> 0;
  }
  /** A number in [0, 1). */
  next(): number {
    this.a = (this.a + 0x6d2b79f5) >>> 0;
    let t = this.a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }
  /** A whole number in [0, n). */
  int(n: number): number {
    return Math.floor(this.next() * n);
  }
  bool(): boolean {
    return this.int(2) === 1;
  }
  pick<T>(items: readonly T[]): T {
    return items[this.int(items.length)];
  }
}

// ---- ids (the same small fixed ids the golden match uses) -------------------------------------------------------------

const hex12 = (n: number): string => n.toString(16).padStart(12, '0');
export const LIONS: Uuid = '00000000-0000-0001-0000-000000000001';
export const TIGERS: Uuid = '00000000-0000-0002-0000-000000000002';
export const lion = (n: number): Uuid => `00000000-0000-000a-0000-${hex12(n)}`;
export const tiger = (n: number): Uuid => `00000000-0000-0014-0000-${hex12(n)}`;

// ---- one random match -----------------------------------------------------------------------------------------------

export type GeneratedStep = { op: 'command'; command: ScoreCommand } | { op: 'undo'; mode: UndoMode };

function setupFor(r: Rng): MatchSetup {
  const players = r.int(4) === 0 ? 2 + r.int(2) : 2 + r.int(10);
  const overs = 1 + r.int(5);
  const rules: MatchRules = {
    ballsPerOver: 4 + r.int(5),
    playersPerSide: players,
    maxOversPerBowler: r.int(3) === 0 ? 1 : null,
    widePenalty: r.int(3),
    noBallPenalty: r.int(3),
    freeHitEnabled: r.int(5) !== 0,
    lastManStands: r.int(4) === 0,
  };
  const xiA = Array.from({ length: players }, (_, i) => lion(i + 1));
  const xiB = Array.from({ length: players }, (_, i) => tiger(i + 1));
  return { overs, rules, teamA: LIONS, teamB: TIGERS, battingFirst: r.bool() ? LIONS : TIGERS, xiA, xiB };
}

const atCrease = (s: InningsState): Uuid[] => [s.atBattingEnd, s.atBowlersEnd].filter((p): p is Uuid => p !== null);

function randomDismissal(r: Rng, s: InningsState, kind: DeliveryKind): Dismissal {
  const allowed = VOCABULARY.dismissalTypes.filter((t) => dismissalViolation(kind, s.freeHit, t) === null);
  const type: DismissalType = allowed.length > 0 && r.int(100) < 75 ? r.pick(allowed) : r.pick(VOCABULARY.dismissalTypes);
  const crease = atCrease(s);
  let batterOut: Uuid | null = null;
  if (eitherBatterCanBeOut(type)) {
    batterOut = r.int(10) === 0 ? null : crease.length > 0 ? r.pick(crease) : r.pick(s.battingXi);
  } else if (r.int(4) === 0) {
    batterOut = s.atBattingEnd;
  }
  const end = type === 'RUN_OUT' && r.int(8) !== 0 ? r.pick(VOCABULARY.creaseEnds) : r.int(6) === 0 ? r.pick(VOCABULARY.creaseEnds) : null;
  const fielderId = canNameFielder(type) && r.int(3) === 0 ? r.pick(s.bowlingXi) : r.int(20) === 0 ? r.pick(s.bowlingXi) : null;
  return { type, batterOut, end, fielderId };
}

function randomDelivery(r: Rng, s: InningsState): Delivery {
  const k = r.int(100);
  const kind: DeliveryKind = k < 76 ? 'NORMAL' : k < 88 ? 'WIDE' : 'NO_BALL';
  let runs = r.int(100) < 60 ? r.int(3) : r.int(9);
  const runsType = kind === 'WIDE' ? null : r.int(100) < 60 ? 'BAT' : r.pick(VOCABULARY.runsTypes);
  let boundary = r.int(10) === 0;
  if (boundary) {
    runs = r.bool() ? 4 : 6;
  }
  let dismissal: Dismissal | null = null;
  if (r.int(100) < 12) {
    dismissal = randomDismissal(r, s, kind);
    if (!keepsCompletedRuns(dismissal.type) && r.int(4) !== 0) {
      runs = 0;
    }
    if (r.int(4) !== 0) {
      boundary = false;
    }
  }
  return { kind, runs, runsType, boundary, dismissal };
}

function randomInningsCommand(r: Rng, s: InningsState): InningsCommand {
  if (s.needsBatter && r.int(10) < 9) {
    const waiting = s.battingXi.filter((p) => !atCrease(s).includes(p) && (s.batters.find((b) => b.playerId === p) === undefined || s.batters.find((b) => b.playerId === p)!.status === 'RETIRED_HURT'));
    return { type: 'SELECT_BATTER', batterId: waiting.length > 0 && r.int(10) < 9 ? r.pick(waiting) : r.pick(s.battingXi) };
  }
  if (s.needsBowler && r.int(10) < 9) {
    const ok = eligibleBowlers(s);
    return { type: 'SELECT_BOWLER', bowlerId: ok.length > 0 && r.int(10) < 9 ? r.pick(ok) : r.pick(s.bowlingXi) };
  }
  if (yetToBat(s).length === 0 && r.int(10) < 3) {
    // Everybody has batted but the innings is still going (a hurt batter, or last man stands): the scorer can close it.
    return { type: 'END_INNINGS', reason: 'NO_BATTERS_LEFT' };
  }
  const p = r.int(1000);
  if (p < 820) {
    return { type: 'DELIVER', delivery: randomDelivery(r, s) };
  }
  if (p < 860) {
    const crease = atCrease(s);
    return { type: 'RETIRE_BATTER', batterId: crease.length > 0 && r.int(5) !== 0 ? r.pick(crease) : r.pick(s.battingXi), retirement: r.pick(VOCABULARY.retirementTypes) };
  }
  if (p < 920) {
    const others = eligibleBowlers(s, new Set(s.over.shares.map((x) => x.bowlerId)));
    return { type: 'CHANGE_BOWLER', bowlerId: others.length > 0 && r.int(10) < 8 ? r.pick(others) : r.pick(s.bowlingXi) };
  }
  if (p < 930) {
    // Half of these are a valid declaration; the rest name any reason, most of which the engine refuses.
    return { type: 'END_INNINGS', reason: r.int(2) === 0 ? 'DECLARED' : r.pick(VOCABULARY.endReasons) };
  }
  if (p < 945) {
    return { type: 'REVISE_TARGET', target: r.int(80) };
  }
  return r.bool() ? { type: 'SELECT_BATTER', batterId: r.pick(s.battingXi) } : { type: 'SELECT_BOWLER', bowlerId: r.pick(s.bowlingXi) };
}

function startCommand(r: Rng, setup: MatchSetup, inningsSoFar: number): ScoreCommand {
  const batting = inningsSoFar === 0 ? setup.battingFirst : setup.battingFirst === setup.teamA ? setup.teamB : setup.teamA;
  const bowling = batting === setup.teamA ? setup.teamB : setup.teamA;
  const batters = batting === setup.teamA ? setup.xiA : setup.xiB;
  const bowlers = bowling === setup.teamA ? setup.xiA : setup.xiB;
  const striker = r.pick(batters);
  const nonStriker = r.int(10) === 0 ? striker : r.pick(batters);
  return { type: 'START_INNINGS', striker, nonStriker, bowler: r.pick(bowlers) };
}

function nextStep(r: Rng, log: ScoringLog): GeneratedStep {
  const score = log.score;
  const current = currentInnings(score);
  const p = r.int(100);
  if (p < 5) {
    return { op: 'undo', mode: r.int(10) < 7 ? 'BALL' : 'COMMAND' };
  }
  if (p < 7) {
    return { op: 'command', command: { type: 'END_MATCH', reason: r.pick(VOCABULARY.endMatchReasons) } };
  }
  const needStart = current === null || current.status === 'COMPLETE';
  if ((needStart && r.int(100) < 85) || current === null || r.int(100) < 2) {
    return { op: 'command', command: startCommand(r, log.setup, score.innings.length) };
  }
  return { op: 'command', command: randomInningsCommand(r, current) };
}

export interface GeneratedMatch {
  seed: number;
  setup: MatchSetup;
  steps: GeneratedStep[];
}

export function generateMatch(seed: number): GeneratedMatch {
  const r = new Rng(Math.imul(seed, 7919) + 13);
  const setup = setupFor(r);
  let log = newLog(setup);
  const steps: GeneratedStep[] = [];
  const limit = MIN_STEPS + r.int(EXTRA_STEPS);
  let afterEnd = 0;
  while (steps.length < limit && afterEnd < STEPS_AFTER_THE_END) {
    const step = nextStep(r, log);
    steps.push(step);
    log = step.op === 'command' ? stepCommand(log, step.command).log : stepUndo(log, step.mode).log;
    if (isFinished(log.score)) {
      afterEnd++;
    }
  }
  return { seed, setup, steps };
}

// ---- the files --------------------------------------------------------------------------------------------------------

/** One vector file's text, laid out the way the Java writer lays out its files (one compact step per line). */
export function fileText(name: string, description: string, setup: MatchSetup, steps: GeneratedStep[], stateEvery: number): { text: string; steps: number } {
  let log = newLog(setup);
  let accepted = 0;
  const lines = steps.map((step, i) => {
    let result;
    const node: { [key: string]: Json } = {};
    if (step.op === 'command') {
      node.op = 'command';
      node.command = toBody(step.command) as Json;
      ({ log, result } = stepCommand(log, step.command));
    } else {
      node.op = 'undo';
      node.mode = step.mode;
      ({ log, result } = stepUndo(log, step.mode));
    }
    if (result.accepted) {
      accepted++;
    }
    const last = i === steps.length - 1;
    node.expect = expectOf(log, result, result.accepted && (stateEvery === 1 || accepted % stateEvery === 0 || last));
    return `    ${canonicalJson(node)}`;
  });
  const text = `{\n  "format": ${FORMAT},\n  "name": "${name}",\n  "description": "${description}",\n  "setup": ${canonicalJson(setup as unknown as Json)},\n  "steps": [\n${lines.join(',\n')}\n  ]\n}\n`;
  return { text, steps: steps.length };
}

/** Every file of `from-ts/`, by its path inside that folder, manifest last. */
export function buildTsVectors(): Map<string, string> {
  const files = new Map<string, string>();
  const entries: string[] = [];
  for (let seed = 1; seed <= TS_SEEDS; seed++) {
    const match = generateMatch(seed);
    const path = `seed-${String(seed).padStart(4, '0')}.json`;
    const { text, steps } = fileText(`ts-${String(seed).padStart(4, '0')}`, 'A seeded random match made by the TypeScript engine: mostly sensible commands, some refused ones, some undos.', match.setup, match.steps, STATE_EVERY);
    files.set(path, text);
    entries.push(`    {"path": "${path}", "sha256": "${toHex(sha256Bytes(text))}", "steps": ${steps}}`);
  }
  files.set('manifest.json', `{\n  "vectorFormat": ${FORMAT},\n  "engineVersion": ${ENGINE_VERSION},\n  "generator": "typescript",\n  "files": [\n${entries.join(',\n')}\n  ]\n}\n`);
  return files;
}
