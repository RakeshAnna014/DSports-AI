import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { currentInnings, matchResult, matchStatus, nextAction } from './match';
import { newLog, stepCommand, stepUndo, type ScoringLog } from './log';
import { standardRules } from './rules';
import { LIONS, TIGERS, lion, tiger } from './tsVectors';
import type { Delivery, Dismissal, InningsState, MatchRules, MatchSetup, ScoreCommand } from './types';
import { VOCABULARY } from './vocabulary';

/**
 * Rule scenarios written by hand, one per situation a scorer meets, in a three-a-side two-over match (small enough to read). The shared vectors prove the two
 * engines agree; these prove the rules say what cricket says, so a mistake copied into both engines does not pass.
 */
const l = [lion(1), lion(2), lion(3)];
const t = [tiger(1), tiger(2), tiger(3)];

const setup = (rules: Partial<MatchRules> = {}, overs = 2): MatchSetup => ({
  overs,
  rules: { ...standardRules(), playersPerSide: 3, ...rules },
  teamA: LIONS,
  teamB: TIGERS,
  battingFirst: LIONS,
  xiA: l,
  xiB: t,
});

const ball = (over: Partial<Delivery> = {}): ScoreCommand => ({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 0, runsType: 'BAT', boundary: false, dismissal: null, ...over } });
const out = (type: Dismissal['type'], rest: Partial<Dismissal> = {}): Dismissal => ({ type, batterOut: null, end: null, fielderId: null, ...rest });
const start = (striker = l[0], nonStriker = l[1], bowler = t[0]): ScoreCommand => ({ type: 'START_INNINGS', striker, nonStriker, bowler });

function run(log: ScoringLog, ...commands: ScoreCommand[]): ScoringLog {
  for (const command of commands) {
    const step = stepCommand(log, command);
    if (!step.result.accepted) {
      throw new Error(`${JSON.stringify(command)} was refused: ${step.result.violation}`);
    }
    log = step.log;
  }
  return log;
}
const refusal = (log: ScoringLog, command: ScoreCommand): string => {
  const step = stepCommand(log, command);
  if (step.result.accepted) {
    return 'ACCEPTED';
  }
  expect(step.log, 'a refusal changes nothing').toBe(log);
  return step.result.violation;
};
const innings = (log: ScoringLog): InningsState => currentInnings(log.score)!;
const begin = (rules: Partial<MatchRules> = {}, overs = 2): ScoringLog => run(newLog(setup(rules, overs)), start());

describe('dismissals on wides, no-balls and free hits', () => {
  it('allows stumped, run out, hit wicket and obstructing the field off a wide, and refuses the rest', () => {
    const log = begin();
    expect(refusal(log, ball({ kind: 'WIDE', runsType: null, dismissal: out('BOWLED') }))).toBe('DISMISSAL_NOT_ALLOWED_ON_WIDE');
    expect(refusal(log, ball({ kind: 'WIDE', runsType: null, dismissal: out('CAUGHT') }))).toBe('DISMISSAL_NOT_ALLOWED_ON_WIDE');
    expect(refusal(log, ball({ kind: 'WIDE', runsType: null, dismissal: out('LBW') }))).toBe('DISMISSAL_NOT_ALLOWED_ON_WIDE');
    const stumped = run(log, ball({ kind: 'WIDE', runsType: null, dismissal: out('STUMPED', { fielderId: t[1] }) }));
    const s = innings(stumped);
    expect(s.runs, 'the penalty run still counts').toBe(1);
    expect(s.wickets).toBe(1);
    expect(s.legalBalls, 'a wide is not a legal ball').toBe(0);
    expect(s.extras.wides).toBe(1);
    expect(s.batters[0]).toMatchObject({ status: 'OUT', dismissal: 'STUMPED', bowlerId: t[0], fielderId: t[1], balls: 0 });
    expect(refusal(stumped, ball())).toBe('NEEDS_NEW_BATTER');
    expect(nextAction(stumped.score)).toBe('SELECT_BATTER');
  });

  it('allows only run out, obstructing and hit ball twice off a no-ball', () => {
    const log = begin();
    for (const type of ['BOWLED', 'CAUGHT', 'LBW', 'STUMPED', 'HIT_WICKET'] as const) {
      expect(refusal(log, ball({ kind: 'NO_BALL', dismissal: out(type) })), type).toBe('DISMISSAL_NOT_ALLOWED_ON_NO_BALL');
    }
    expect(refusal(log, ball({ kind: 'NO_BALL', dismissal: out('HIT_BALL_TWICE') }))).toBe('ACCEPTED');
  });

  it('gives a free hit after a no-ball, where only a run out, obstruction or a double hit can get the batter out, and ends it with the next legal ball', () => {
    const afterNoBall = run(begin(), ball({ kind: 'NO_BALL' }));
    expect(innings(afterNoBall).freeHit).toBe(true);
    expect(refusal(afterNoBall, ball({ dismissal: out('BOWLED') }))).toBe('DISMISSAL_NOT_ALLOWED_ON_FREE_HIT');
    expect(refusal(afterNoBall, ball({ dismissal: out('CAUGHT') }))).toBe('DISMISSAL_NOT_ALLOWED_ON_FREE_HIT');
    expect(refusal(afterNoBall, ball({ dismissal: out('RUN_OUT', { batterOut: l[0], end: 'BATTING_END' }) }))).toBe('ACCEPTED');
    const afterFreeHit = run(afterNoBall, ball());
    expect(innings(afterFreeHit).freeHit).toBe(false);
    expect(refusal(afterFreeHit, ball({ dismissal: out('BOWLED') }))).toBe('ACCEPTED');
  });

  it('keeps the free hit through a wide, and does not give one when the rules switch it off', () => {
    const throughWide = run(begin(), ball({ kind: 'NO_BALL' }), ball({ kind: 'WIDE', runsType: null }));
    expect(innings(throughWide).freeHit, 'a wide is not the free hit being taken').toBe(true);
    const noFreeHit = run(begin({ freeHitEnabled: false }), ball({ kind: 'NO_BALL' }));
    expect(innings(noFreeHit).freeHit).toBe(false);
    expect(refusal(noFreeHit, ball({ dismissal: out('BOWLED') }))).toBe('ACCEPTED');
  });

  it('needs the dismissed batter and the end for a run out, and lets the non-striker be out only that way', () => {
    const log = begin();
    expect(refusal(log, ball({ dismissal: out('RUN_OUT', { end: 'BATTING_END' }) }))).toBe('DISMISSED_BATTER_REQUIRED');
    expect(refusal(log, ball({ dismissal: out('RUN_OUT', { batterOut: l[0] }) }))).toBe('RUN_OUT_END_REQUIRED');
    expect(refusal(log, ball({ dismissal: out('RUN_OUT', { batterOut: l[2], end: 'BATTING_END' }) }))).toBe('BATTER_NOT_AT_CREASE');
    expect(refusal(log, ball({ dismissal: out('BOWLED', { batterOut: l[1] }) }))).toBe('ONLY_STRIKER_CAN_BE_OUT');
    expect(refusal(log, ball({ dismissal: out('CAUGHT', { fielderId: l[2] }) }))).toBe('FIELDER_NOT_IN_XI');
    expect(refusal(log, ball({ dismissal: out('BOWLED', { fielderId: t[1] }) }))).toBe('FIELDER_NOT_ALLOWED');
    expect(refusal(log, ball({ runs: 1, dismissal: out('CAUGHT') }))).toBe('DISMISSAL_RUNS_NOT_ALLOWED');
    expect(refusal(log, ball({ runs: 2, dismissal: out('RUN_OUT', { batterOut: l[1], end: 'BOWLERS_END', fielderId: t[2] }) }))).toBe('ACCEPTED');
  });

  it('decides who is on strike after a run out from the end the batter was run out at', () => {
    // The striker is out at the batting end: the new batter takes the striker's place.
    const strikerOut = run(begin(), ball({ runs: 1, dismissal: out('RUN_OUT', { batterOut: l[0], end: 'BATTING_END' }) }), { type: 'SELECT_BATTER', batterId: l[2] });
    expect(innings(strikerOut)).toMatchObject({ atBattingEnd: l[2], atBowlersEnd: l[1] });
    // The non-striker is out at the bowler's end: the striker stays on strike.
    const nonStrikerOut = run(begin(), ball({ runs: 1, dismissal: out('RUN_OUT', { batterOut: l[1], end: 'BOWLERS_END' }) }), { type: 'SELECT_BATTER', batterId: l[2] });
    expect(innings(nonStrikerOut)).toMatchObject({ atBattingEnd: l[0], atBowlersEnd: l[2] });
    expect(innings(nonStrikerOut).batters.find((b) => b.playerId === l[1])).toMatchObject({ status: 'OUT', dismissal: 'RUN_OUT', bowlerId: null });
  });
});

describe('extras, strike and the over', () => {
  it('counts a wide with runs, a no-ball with byes and leg byes the way the scorebook does', () => {
    const wide = innings(run(begin(), ball({ kind: 'WIDE', runs: 2, runsType: null })));
    expect(wide).toMatchObject({ runs: 3, legalBalls: 0 });
    expect(wide.extras).toEqual({ wides: 3, noBalls: 0, byes: 0, legByes: 0 });
    expect(wide.atBattingEnd, 'an even number of runs run, so no change of strike').toBe(l[0]);

    const noBallBye = innings(run(begin(), ball({ kind: 'NO_BALL', runs: 1, runsType: 'BYE' })));
    expect(noBallBye).toMatchObject({ runs: 2, legalBalls: 0, atBattingEnd: l[1] });
    expect(noBallBye.extras).toEqual({ wides: 0, noBalls: 1, byes: 1, legByes: 0 });
    expect(noBallBye.batters[0].balls, 'the batter faced a no-ball').toBe(1);
    expect(noBallBye.bowlers[0].runs, 'byes are not charged to the bowler').toBe(1);

    const legBye = innings(run(begin(), ball({ runs: 3, runsType: 'LEG_BYE' })));
    expect(legBye).toMatchObject({ runs: 3, legalBalls: 1, atBattingEnd: l[1] });
    expect(legBye.batters[0]).toMatchObject({ runs: 0, balls: 1 });
  });

  it('keeps a boundary from changing strike, counts fours and sixes, and refuses a bye or wide six', () => {
    const log = run(begin(), ball({ runs: 4, boundary: true }), ball({ runs: 6, boundary: true }));
    expect(innings(log)).toMatchObject({ runs: 10, atBattingEnd: l[0] });
    expect(innings(log).batters[0]).toMatchObject({ fours: 1, sixes: 1, runs: 10 });
    expect(refusal(begin(), ball({ runs: 6, runsType: 'BYE', boundary: true }))).toBe('INVALID_BOUNDARY');
    expect(refusal(begin(), ball({ kind: 'WIDE', runs: 6, runsType: null, boundary: true }))).toBe('INVALID_BOUNDARY');
    expect(refusal(begin(), ball({ runs: 5, boundary: true }))).toBe('INVALID_BOUNDARY');
    expect(refusal(begin(), ball({ runs: 8 }))).toBe('RUNS_OUT_OF_RANGE');
  });

  it('ends the over on the sixth legal ball, swaps the batters, asks for a new bowler and refuses the same bowler again', () => {
    const six = run(begin(), ...Array.from({ length: 6 }, () => ball()));
    const s = innings(six);
    expect(s).toMatchObject({ legalBalls: 6, needsBowler: true, atBattingEnd: l[1], atBowlersEnd: l[0] });
    expect(s.bowlers[0]).toMatchObject({ creditedOvers: 1, maidens: 1 });
    expect(nextAction(six.score)).toBe('SELECT_BOWLER');
    expect(refusal(six, ball())).toBe('NEEDS_NEW_BOWLER');
    expect(refusal(six, { type: 'SELECT_BOWLER', bowlerId: t[0] })).toBe('BOWLER_BOWLED_PREVIOUS_OVER');
    expect(refusal(six, { type: 'SELECT_BOWLER', bowlerId: l[0] })).toBe('BOWLER_NOT_IN_XI');
    expect(refusal(six, { type: 'SELECT_BOWLER', bowlerId: t[1] })).toBe('ACCEPTED');
  });

  it('does not make a maiden of an over with a wide in it, and does not count wides as balls of the over', () => {
    const log = run(begin(), ball({ kind: 'WIDE', runsType: null }), ...Array.from({ length: 6 }, () => ball()));
    expect(innings(log).bowlers[0]).toMatchObject({ creditedOvers: 1, maidens: 0, runs: 1, wides: 1 });
    expect(innings(log).legalBalls).toBe(6);
  });

  it('changes the bowler in the middle of an over, gives the over to the bowler of most balls, and refuses the wrong choices', () => {
    const log = run(begin(), ball(), ball());
    expect(refusal(begin(), { type: 'CHANGE_BOWLER', bowlerId: t[1] })).toBe('NO_DELIVERY_IN_OVER');
    expect(refusal(log, { type: 'CHANGE_BOWLER', bowlerId: t[0] })).toBe('SAME_BOWLER');
    const changed = run(log, { type: 'CHANGE_BOWLER', bowlerId: t[1] }, ball(), ball(), ball(), ball());
    expect(innings(changed).needsBowler).toBe(true);
    expect(innings(changed).bowlers.find((b) => b.playerId === t[1])).toMatchObject({ creditedOvers: 1, legalBalls: 4 });
    expect(innings(changed).bowlers.find((b) => b.playerId === t[0])).toMatchObject({ creditedOvers: 0, legalBalls: 2 });
    expect(refusal(run(log, { type: 'CHANGE_BOWLER', bowlerId: t[1] }), { type: 'CHANGE_BOWLER', bowlerId: t[0] })).toBe('BOWLER_ALREADY_BOWLED_THIS_OVER');
  });

  it('sets the bowler limit aside only as a last resort, and records that it did', () => {
    // Three overs with a limit of one over each, but only two bowlers: the third over must go to someone who has already bowled his one over.
    const waived = run(
      newLog({ ...setup({ maxOversPerBowler: 1 }, 3), xiB: [t[0], t[1]] }),
      start(),
      ...Array.from({ length: 6 }, () => ball()),
      { type: 'SELECT_BOWLER', bowlerId: t[1] },
      ...Array.from({ length: 6 }, () => ball()),
    );
    expect(refusal(waived, { type: 'SELECT_BOWLER', bowlerId: t[1] })).toBe('BOWLER_BOWLED_PREVIOUS_OVER');
    const next = stepCommand(waived, { type: 'SELECT_BOWLER', bowlerId: t[0] });
    expect(next.result.accepted && next.result.waivers).toEqual([{ over: 2, bowlerId: t[0], kind: 'OVER_LIMIT' }]);
    expect(innings(next.log).waivers).toHaveLength(1);
  });
});

describe('the end of an innings', () => {
  it('ends the innings all out when the side runs out of batters, with fewer than eleven players', () => {
    const log = run(begin(), ball({ dismissal: out('BOWLED') }), { type: 'SELECT_BATTER', batterId: l[2] }, ball({ dismissal: out('LBW') }));
    expect(innings(log)).toMatchObject({ status: 'COMPLETE', endReason: 'ALL_OUT', wickets: 2, needsBatter: false });
    expect(refusal(log, ball())).toBe('INNINGS_COMPLETE');
    expect(nextAction(log.score)).toBe('START_INNINGS');
  });

  it('lets the last batter carry on with last man stands, and ends the innings when that batter is out', () => {
    const mid = run(begin({ lastManStands: true }), ball({ dismissal: out('BOWLED') }), { type: 'SELECT_BATTER', batterId: l[2] }, ball({ dismissal: out('LBW') }));
    expect(innings(mid)).toMatchObject({ status: 'IN_PROGRESS', wickets: 2, needsBatter: false, atBattingEnd: l[1], atBowlersEnd: null });
    const lone = run(mid, ball({ runs: 1 }));
    expect(innings(lone), 'a lone batter stays on strike after an odd run').toMatchObject({ atBattingEnd: l[1], atBowlersEnd: null });
    const done = run(lone, ball({ dismissal: out('BOWLED') }));
    expect(innings(done)).toMatchObject({ status: 'COMPLETE', endReason: 'ALL_OUT', wickets: 3 });
  });

  it('completes the innings on the last legal ball of the last over', () => {
    const log = run(begin({}, 1), ...Array.from({ length: 5 }, () => ball()));
    expect(innings(log).status).toBe('IN_PROGRESS');
    const last = run(log, ball({ runs: 1 }));
    expect(innings(last)).toMatchObject({ status: 'COMPLETE', endReason: 'OVERS_COMPLETE', needsBowler: false });
    expect(matchStatus(last.score)).toBe('INNINGS_BREAK');
  });

  it('can be declared, and can be closed with no batters left only when everybody has batted', () => {
    const log = begin();
    expect(refusal(log, { type: 'END_INNINGS', reason: 'ALL_OUT' })).toBe('CANNOT_END_YET');
    expect(refusal(log, { type: 'END_INNINGS', reason: 'NO_BATTERS_LEFT' })).toBe('CANNOT_END_YET');
    expect(innings(run(log, { type: 'END_INNINGS', reason: 'DECLARED' }))).toMatchObject({ status: 'COMPLETE', endReason: 'DECLARED' });
  });

  it('retires a batter hurt (not a wicket, may return) or out (a wicket, may not)', () => {
    const hurt = run(begin(), { type: 'RETIRE_BATTER', batterId: l[0], retirement: 'HURT' });
    expect(innings(hurt)).toMatchObject({ wickets: 0, needsBatter: true });
    expect(refusal(hurt, { type: 'SELECT_BATTER', batterId: l[0] }), 'a hurt batter may come back').toBe('ACCEPTED');
    const outRetired = run(begin(), { type: 'RETIRE_BATTER', batterId: l[0], retirement: 'OUT' });
    expect(innings(outRetired)).toMatchObject({ wickets: 1, needsBatter: true });
    expect(refusal(outRetired, { type: 'SELECT_BATTER', batterId: l[0] })).toBe('BATTER_ALREADY_OUT');
    expect(refusal(outRetired, { type: 'SELECT_BATTER', batterId: l[1] })).toBe('BATTER_ALREADY_AT_CREASE');
    expect(refusal(begin(), { type: 'SELECT_BATTER', batterId: l[2] })).toBe('NO_BATTER_NEEDED');
  });
});

describe('the chase and the result', () => {
  const hits: Record<number, Partial<Delivery>[]> = { 1: [{ runs: 1 }], 2: [{ runs: 2 }], 4: [{ runs: 4, boundary: true }], 10: [{ runs: 6, boundary: true }, { runs: 4, boundary: true }] };
  const firstInnings = (runs: number): ScoringLog => run(begin(), ...(hits[runs] ?? []).map((h) => ball(h)), { type: 'END_INNINGS', reason: 'DECLARED' });

  it('sets the target from the first innings and wins mid-over by wickets in hand with the balls left', () => {
    const log = run(firstInnings(4), start(t[0], t[1], l[0]), ball({ runs: 4, boundary: true }));
    expect(innings(log).target).toBe(5);
    expect(innings(log).status).toBe('IN_PROGRESS');
    const won = run(log, ball({ runs: 1 }));
    expect(innings(won)).toMatchObject({ status: 'COMPLETE', endReason: 'TARGET_REACHED', legalBalls: 2 });
    expect(matchStatus(won.score)).toBe('COMPLETED');
    expect(matchResult(won.score)).toEqual({ type: 'WON_BY_WICKETS', winnerTeamId: TIGERS, margin: 2, ballsRemaining: 10 });
  });

  it('counts the win even when the winning ball also bowls the side out', () => {
    const log = run(firstInnings(1), start(t[0], t[1], l[0]), ball({ dismissal: out('BOWLED') }), { type: 'SELECT_BATTER', batterId: t[2] });
    const won = run(log, ball({ runs: 2 }));
    expect(innings(won).endReason).toBe('TARGET_REACHED');
    expect(matchResult(won.score)).toMatchObject({ type: 'WON_BY_WICKETS', winnerTeamId: TIGERS, margin: 1 });
  });

  it('is a tie when the chase finishes one short, and a win by runs when it finishes further short', () => {
    const tie = run(firstInnings(4), start(t[0], t[1], l[0]), ball({ runs: 4, boundary: true }), { type: 'END_INNINGS', reason: 'DECLARED' });
    expect(matchResult(tie.score)).toEqual({ type: 'TIE', winnerTeamId: null, margin: 0, ballsRemaining: 0 });
    const short = run(firstInnings(4), start(t[0], t[1], l[0]), ball({ runs: 1 }), { type: 'END_INNINGS', reason: 'DECLARED' });
    expect(matchResult(short.score)).toEqual({ type: 'WON_BY_RUNS', winnerTeamId: LIONS, margin: 3, ballsRemaining: 0 });
  });

  it('takes a revised target from the scorer, and refuses one that makes no sense', () => {
    const chase = run(firstInnings(10), start(t[0], t[1], l[0]));
    expect(refusal(begin(), { type: 'REVISE_TARGET', target: 5 })).toBe('NO_TARGET_TO_REVISE');
    expect(refusal(chase, { type: 'REVISE_TARGET', target: 0 })).toBe('INVALID_TARGET');
    const revised = run(chase, { type: 'REVISE_TARGET', target: 3 }, ball({ runs: 3 }));
    expect(innings(revised)).toMatchObject({ target: 3, endReason: 'TARGET_REACHED' });
    expect(refusal(revised, { type: 'REVISE_TARGET', target: 9 }), 'the chase was already won').toBe('TARGET_ALREADY_REACHED');
  });

  it('refuses a third innings, and a second one before the first is finished', () => {
    expect(refusal(begin(), start(t[0], t[1], l[0]))).toBe('PREVIOUS_INNINGS_NOT_COMPLETE');
    const finished = run(firstInnings(2), start(t[0], t[1], l[0]), { type: 'END_INNINGS', reason: 'DECLARED' });
    expect(refusal(finished, start(l[0], l[1], t[0]))).toBe('ALL_INNINGS_PLAYED');
  });

  it('refuses openers who are not two different players of the batting side, and a bowler of the wrong side', () => {
    const fresh = newLog(setup());
    expect(refusal(fresh, start(l[0], l[0]))).toBe('INVALID_OPENERS');
    expect(refusal(fresh, start(l[0], t[0]))).toBe('INVALID_OPENERS');
    expect(refusal(fresh, start(l[0], l[1], l[2]))).toBe('BOWLER_NOT_IN_XI');
    expect(refusal(fresh, ball())).toBe('INNINGS_NOT_STARTED');
  });
});

describe('ending a match by hand, and undo across boundaries', () => {
  it('ends an abandoned match, refuses everything after, and brings it back when the end is undone', () => {
    const live = run(begin(), ball({ runs: 2 }));
    const abandoned = run(live, { type: 'END_MATCH', reason: 'ABANDONED' });
    expect(matchStatus(abandoned.score)).toBe('ABANDONED');
    expect(nextAction(abandoned.score)).toBe('NONE');
    expect(refusal(abandoned, ball())).toBe('MATCH_ENDED');
    expect(refusal(abandoned, { type: 'END_MATCH', reason: 'NO_RESULT' })).toBe('MATCH_ENDED');
    const back = stepUndo(abandoned, 'COMMAND').log;
    expect(matchStatus(back.score)).toBe('IN_PROGRESS');
    expect(innings(back).runs).toBe(2);
  });

  it('does not let a completed match be ended by hand', () => {
    const done = run(begin(), { type: 'END_INNINGS', reason: 'DECLARED' }, start(t[0], t[1], l[0]), { type: 'END_INNINGS', reason: 'DECLARED' });
    expect(matchStatus(done.score)).toBe('COMPLETED');
    expect(refusal(done, { type: 'END_MATCH', reason: 'ABANDONED' })).toBe('MATCH_COMPLETE');
  });

  it('undoes across the end of an over, a wicket that forced a new batter, and the start of the second innings', () => {
    const six = run(begin(), ...Array.from({ length: 6 }, () => ball()));
    const afterUndo = stepUndo(six, 'BALL').log;
    expect(innings(afterUndo)).toMatchObject({ legalBalls: 5, needsBowler: false, atBattingEnd: l[0] });

    const wicket = run(begin(), ball({ dismissal: out('BOWLED') }), { type: 'SELECT_BATTER', batterId: l[2] });
    const { log: back, result } = stepUndo(wicket, 'BALL');
    expect(result.accepted && result.voids).toHaveLength(2);
    expect(innings(back)).toMatchObject({ wickets: 0, needsBatter: false, atBattingEnd: l[0], atBowlersEnd: l[1] });

    const second = run(begin(), { type: 'END_INNINGS', reason: 'DECLARED' }, start(t[0], t[1], l[0]));
    expect(matchStatus(second.score)).toBe('IN_PROGRESS');
    const first = stepUndo(second, 'COMMAND').log;
    expect(first.score.innings).toHaveLength(1);
    expect(matchStatus(first.score)).toBe('INNINGS_BREAK');
    expect(stepUndo(newLog(setup()), 'BALL').result).toMatchObject({ accepted: false, violation: 'NOTHING_TO_UNDO' });
  });
});

describe('the shared vectors cover every rule', () => {
  const root = resolve(process.cwd(), '..', 'testdata', 'cricket-engine');
  const manifest = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8')) as { notCoverable: { violations: Record<string, string> } };

  it('refuses at least once, in the Java-made and the TypeScript-made files together, every rule the vocabulary names (apart from the few the manifest says cannot be reached)', () => {
    const seen = new Set<string>();
    for (const dir of ['golden', 'random', 'from-ts']) {
      for (const name of readdirSync(join(root, dir)).filter((f) => f.startsWith(dir === 'golden' ? '' : 'seed-') && f.endsWith('.json'))) {
        const file = JSON.parse(readFileSync(join(root, dir, name), 'utf8')) as { steps: { expect: { violation?: string } }[] };
        file.steps.forEach((s) => s.expect.violation && seen.add(s.expect.violation));
      }
    }
    const missing = VOCABULARY.violations.filter((v) => !seen.has(v) && !(v in manifest.notCoverable.violations));
    expect(missing, 'rules no vector refuses').toEqual([]);
  });
});
