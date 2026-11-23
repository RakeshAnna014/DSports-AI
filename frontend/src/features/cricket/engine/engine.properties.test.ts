import { describe, expect, it } from 'vitest';
import { eligibleBowlers, yetToBat } from './innings';
import { commandsOf, logFrom, newLog, stepCommand, stepUndo, type ScoringLog } from './log';
import { currentInnings, foldMatch } from './match';
import type { InningsState } from './types';
import { stateFingerprint } from './vectorExpect';
import { generateMatch, type GeneratedMatch } from './tsVectors';

/**
 * Properties of the engine that must hold for any match, checked over seeded random matches (a failure names the seed and reproduces). They are the ones the
 * offline queue stands on: undo gives back exactly the earlier state, a log replayed from scratch is the state reached step by step, a refused command changes
 * nothing, and every accepted command leaves a scorecard that adds up (a port of the Java `EngineInvariantsTest`).
 */
const SEEDS = [...Array.from({ length: 150 }, (_, i) => i + 1), ...Array.from({ length: 50 }, (_, i) => 5000 + i)];
const matches: GeneratedMatch[] = SEEDS.map(generateMatch);

/** Plays a generated match step by step, calling `visit` with the log before and after every step. */
function play(match: GeneratedMatch, visit: (before: ScoringLog, after: ScoringLog, accepted: boolean, step: GeneratedMatch['steps'][number], index: number) => void): ScoringLog {
  let log = newLog(match.setup);
  match.steps.forEach((step, index) => {
    const before = log;
    const { log: after, result } = step.op === 'command' ? stepCommand(log, step.command) : stepUndo(log, step.mode);
    visit(before, after, result.accepted, step, index);
    log = after;
  });
  return log;
}

const waitingBatters = (s: InningsState): string[] =>
  s.battingXi.filter((p) => p !== s.atBattingEnd && p !== s.atBowlersEnd && (s.batters.find((b) => b.playerId === p) === undefined || s.batters.find((b) => b.playerId === p)!.status === 'RETIRED_HURT'));

describe('every accepted command leaves a scorecard that adds up', () => {
  it('holds the invariants after every accepted step of every match', () => {
    let checked = 0;
    for (const match of matches) {
      play(match, (_before, after, accepted) => {
        if (!accepted) {
          return;
        }
        // Only the innings in play can have changed (the finished one is the same object), so it is the only one to check again.
        const current = currentInnings(after.score);
        if (current !== null) {
          check(current, match.seed);
          checked++;
        }
      });
    }
    expect(checked).toBeGreaterThan(4000);
  });
});

function check(s: InningsState, seed: number): void {
  const why = `seed ${seed}`;
  const bpo = s.rules.ballsPerOver;
  const batRuns = s.batters.reduce((n, b) => n + b.runs, 0);
  const extras = s.extras.wides + s.extras.noBalls + s.extras.byes + s.extras.legByes;
  expect(s.runs, `${why}: total = bat runs + extras`).toBe(batRuns + extras);
  expect(s.bowlers.reduce((n, b) => n + b.runs, 0) + s.extras.byes + s.extras.legByes, `${why}: bowlers + byes + leg byes = total`).toBe(s.runs);
  expect(s.bowlers.reduce((n, b) => n + b.legalBalls, 0), `${why}: legal balls add up`).toBe(s.legalBalls);
  expect(s.bowlers.reduce((n, b) => n + b.creditedOvers, 0), `${why}: every finished over is credited once`).toBe(Math.trunc(s.legalBalls / bpo));
  expect(s.legalBalls).toBeLessThanOrEqual(s.overs * bpo);
  expect(s.over.legalBalls, `${why}: legal balls in the over`).toBe(s.legalBalls % bpo);
  expect(s.over.shares.reduce((n, x) => n + x.legalBalls, 0)).toBe(s.over.legalBalls);
  expect(s.over.deliveries).toBeGreaterThanOrEqual(s.over.legalBalls);
  const out = s.batters.filter((b) => b.status === 'OUT' || b.status === 'RETIRED_OUT').length;
  expect(s.wickets, `${why}: wickets = batters out = fall of wickets`).toBe(out);
  expect(s.fallOfWickets).toHaveLength(out);
  s.fallOfWickets.forEach((f, i) => {
    expect(f.number).toBe(i + 1);
    expect(f.runs).toBeLessThanOrEqual(s.runs);
  });
  expect(s.wickets).toBeLessThanOrEqual(s.rules.lastManStands ? s.battingXi.length : s.battingXi.length - 1);
  const lines = new Set<string>();
  for (const b of s.batters) {
    expect(lines.has(b.playerId), `${why}: one line per batter`).toBe(false);
    lines.add(b.playerId);
    expect(s.battingXi).toContain(b.playerId);
  }
  expect(yetToBat(s).some((p) => lines.has(p))).toBe(false);
  for (const end of [s.atBattingEnd, s.atBowlersEnd]) {
    if (end !== null) {
      expect(s.batters.find((b) => b.playerId === end)!.status, `${why}: a batter at the crease is not out`).toBe('NOT_OUT');
    }
  }
  if (s.atBattingEnd !== null && s.atBowlersEnd !== null) {
    expect(s.atBattingEnd).not.toBe(s.atBowlersEnd);
  }
  if (s.status !== 'NOT_STARTED') {
    expect(s.bowlingXi).toContain(s.currentBowler);
    s.bowlers.forEach((b) => expect(s.bowlingXi).toContain(b.playerId));
  }
  if (!s.rules.freeHitEnabled) {
    expect(s.freeHit).toBe(false);
  }
  if (s.status === 'IN_PROGRESS') {
    expect(s.legalBalls).toBeLessThan(s.overs * bpo);
    if (s.target !== null) {
      expect(s.runs, `${why}: a chase still going has not reached its target`).toBeLessThan(s.target);
    }
    if (s.needsBatter) {
      expect(s.atBattingEnd === null || s.atBowlersEnd === null, `${why}: a batter is needed, so an end is empty`).toBe(true);
    } else {
      expect(s.atBattingEnd, `${why}: someone is on strike`).not.toBeNull();
      if (!s.rules.lastManStands) {
        expect(s.atBowlersEnd, `${why}: two batters without last man stands`).not.toBeNull();
      }
    }
  } else if (s.status === 'COMPLETE') {
    expect(s.needsBatter || s.needsBowler, `${why}: nothing is needed once the innings is over`).toBe(false);
    expect(s.endReason).not.toBeNull();
  }
}

describe('a refused command changes nothing, and an accepted one never changes the state it started from', () => {
  it('returns the very same log for a refusal and leaves earlier states untouched', () => {
    let refused = 0;
    let accepted = 0;
    for (const match of matches) {
      play(match, (before, after, ok, step) => {
        const snapshot = JSON.stringify(before);
        if (!ok) {
          refused++;
          expect(after, `seed ${match.seed}: a refusal returns the same log`).toBe(before);
        } else {
          accepted++;
          expect(after).not.toBe(before);
        }
        // Whatever the step was, the log it started from is unchanged: the engine builds new objects and never edits old ones.
        expect(JSON.stringify(before), `seed ${match.seed}: the earlier log was edited`).toBe(snapshot);
        if (!ok && step.op === 'command') {
          expect(stateFingerprint(after)).toBe(stateFingerprint(before));
        }
      });
    }
    expect(refused).toBeGreaterThan(500);
    expect(accepted).toBeGreaterThan(4000);
  });

  it('gives the same answer to the same command on the same state', () => {
    for (const match of matches.slice(0, 60)) {
      play(match, (before, _after, _ok, step) => {
        if (step.op !== 'command') {
          return;
        }
        const a = stepCommand(before, step.command);
        const b = stepCommand(before, step.command);
        expect(JSON.stringify(a), `seed ${match.seed}`).toBe(JSON.stringify(b));
      });
    }
  });
});

describe('undo gives back exactly the state before', () => {
  it('undoing the latest command restores the earlier fingerprint, after any accepted command', () => {
    let undone = 0;
    for (const match of matches) {
      play(match, (before, after, accepted, step) => {
        if (!accepted || step.op !== 'command') {
          return;
        }
        const { log, result } = stepUndo(after, 'COMMAND');
        expect(result.accepted).toBe(true);
        expect(stateFingerprint(log), `seed ${match.seed}: undo of ${JSON.stringify(step.command)}`).toBe(stateFingerprint(before));
        expect(JSON.stringify(log.score), `seed ${match.seed}: the whole score, not just its fingerprint`).toBe(JSON.stringify(before.score));
        undone++;
      });
    }
    expect(undone).toBeGreaterThan(2500);
  });

  it('undoing a ball and the choices made after it restores the state before the ball, across over and innings boundaries', () => {
    let balls = 0;
    let withChoices = 0;
    for (const match of matches) {
      play(match, (before, after, accepted, step) => {
        if (!accepted || step.op !== 'command' || step.command.type !== 'DELIVER') {
          return;
        }
        let log = after;
        let followed = 0;
        for (let guard = 0; guard < 4; guard++) {
          const innings = currentInnings(log.score)!;
          if (innings.status !== 'IN_PROGRESS') {
            break;
          }
          let choice;
          if (innings.needsBatter) {
            choice = stepCommand(log, { type: 'SELECT_BATTER', batterId: waitingBatters(innings)[0] });
          } else if (innings.needsBowler) {
            choice = stepCommand(log, { type: 'SELECT_BOWLER', bowlerId: eligibleBowlers(innings)[0] });
          } else {
            break;
          }
          expect(choice.result.accepted, `seed ${match.seed}: the engine asked for a choice it then refused`).toBe(true);
          log = choice.log;
          followed++;
        }
        const { log: undone, result } = stepUndo(log, 'BALL');
        expect(result.accepted, `seed ${match.seed}`).toBe(true);
        if (result.accepted) {
          expect(result.voids, `seed ${match.seed}: the ball and what followed it`).toHaveLength(1 + followed);
        }
        expect(stateFingerprint(undone), `seed ${match.seed}: undo of a ball plus ${followed} choices`).toBe(stateFingerprint(before));
        balls++;
        withChoices += followed > 0 ? 1 : 0;
      });
    }
    expect(balls).toBeGreaterThan(1500);
    expect(withChoices, 'balls followed by a new batter or bowler').toBeGreaterThan(100);
  });

  it('undoes all the way back to the empty match, then says there is nothing to undo, and the match can be played again', () => {
    for (const match of matches.slice(0, 80)) {
      let log = play(match, () => undefined);
      const empty = stateFingerprint(newLog(match.setup));
      for (let i = 0; i < 2000; i++) {
        const { log: next, result } = stepUndo(log, 'COMMAND');
        if (!result.accepted) {
          expect(result.violation).toBe('NOTHING_TO_UNDO');
          expect(next, 'nothing is written').toBe(log);
          break;
        }
        log = next;
      }
      expect(stateFingerprint(log), `seed ${match.seed}`).toBe(empty);
      expect(log.score.innings).toHaveLength(0);
      expect(stepUndo(log, 'BALL').result.accepted).toBe(false);
    }
  });

  it('puts the command back when it is sent again after an undo (an undo is an event, never a deletion)', () => {
    for (const match of matches.slice(0, 60)) {
      play(match, (before, after, accepted, step) => {
        if (!accepted || step.op !== 'command') {
          return;
        }
        const { log: undone } = stepUndo(after, 'COMMAND');
        const { log: again, result } = stepCommand(undone, step.command);
        expect(result.accepted, `seed ${match.seed}`).toBe(true);
        expect(stateFingerprint(again)).toBe(stateFingerprint(after));
        expect(again.entries.length, 'the log only ever grows').toBe(before.entries.length + 3);
      });
    }
  });
});

describe('a log replayed from scratch is the state reached step by step', () => {
  it('gives the same score for the whole log and for every tenth prefix', () => {
    let prefixes = 0;
    for (const match of matches) {
      const final = play(match, (_before, after, accepted, _step, index) => {
        if (accepted && index % 10 === 0) {
          const rebuilt = logFrom(after.setup, after.entries);
          expect(stateFingerprint(rebuilt), `seed ${match.seed}, step ${index + 1}`).toBe(stateFingerprint(after));
          prefixes++;
        }
      });
      const rebuilt = logFrom(final.setup, final.entries);
      expect(JSON.stringify(rebuilt.score), `seed ${match.seed}: the whole log`).toBe(JSON.stringify(final.score));
      expect(JSON.stringify(foldMatch(final.setup, commandsOf(final.entries))), `seed ${match.seed}: fold of the commands in force`).toBe(JSON.stringify(final.score));
    }
    expect(prefixes).toBeGreaterThan(500);
  });

  it('gives the same score when the effective commands are applied from a shuffled start in two halves (fold is the only way state is made)', () => {
    for (const match of matches.slice(0, 40)) {
      const final = play(match, () => undefined);
      const commands = commandsOf(final.entries);
      const half = Math.trunc(commands.length / 2);
      let log = newLog(match.setup);
      for (const command of commands.slice(0, half)) {
        log = stepCommand(log, command).log;
      }
      for (const command of commands.slice(half)) {
        const { log: next, result } = stepCommand(log, command);
        expect(result.accepted, `seed ${match.seed}: a command in force is accepted again`).toBe(true);
        log = next;
      }
      expect(JSON.stringify(log.score)).toBe(JSON.stringify(final.score));
    }
  });
});
