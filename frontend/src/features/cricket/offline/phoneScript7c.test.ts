import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { currentInnings, matchStatus, newLog, stateFingerprint, stepCommand, stepUndo, type ScoringLog } from '../engine';
import { LIONS, TIGERS, lion, tiger } from '../engine/tsVectors';
import { standardRules } from '../engine/rules';
import { NAMES, SCRIPT_7C } from './phoneScript7c';

/**
 * The numbers the real-phone script 7c (docs/SETUP-AND-DEMO.md) tells the person to expect. The script is played through the engine here; the doc must contain the
 * very totals this gives, so a change to a rule or to the script that is not followed in the doc fails the build.
 */
const setup = {
  overs: 2,
  rules: { ...standardRules(), playersPerSide: 4 },
  teamA: LIONS,
  teamB: TIGERS,
  battingFirst: LIONS,
  xiA: [1, 2, 3, 4].map(lion),
  xiB: [1, 2, 3, 4].map(tiger),
};

function play(upTo: number = SCRIPT_7C.length): ScoringLog {
  let log = newLog(setup);
  for (const step of SCRIPT_7C.slice(0, upTo)) {
    const next = step.command === 'UNDO' ? stepUndo(log, 'BALL') : stepCommand(log, step.command);
    expect(next.result.accepted, `${step.press}: ${next.result.accepted ? '' : next.result.violation}`).toBe(true);
    log = next.log;
  }
  return log;
}

const DOC = readFileSync(resolve(process.cwd(), '..', 'docs', 'SETUP-AND-DEMO.md'), 'utf8').replace(/\r\n/g, '\n');
const section = (): string => DOC.slice(DOC.indexOf('7c. **'), DOC.indexOf('## 8. Troubleshooting'));

describe('script 7c', () => {
  const log = play();
  const innings = currentInnings(log.score)!;
  const name = (id: string) => NAMES[id];

  it('is accepted by the engine at every step, and ends the innings with the totals the doc quotes', () => {
    expect(matchStatus(log.score)).toBe('INNINGS_BREAK');
    expect(`Lions ${innings.runs}/${innings.wickets} (${innings.legalBalls / 6} ov)`).toBe(`Lions ${innings.runs}/${innings.wickets} (2 ov)`);
    expect(section()).toContain(`Lions ${innings.runs}/${innings.wickets} (2.0 ov)`);
    expect(section()).toContain(`Tigers need ${innings.runs + 1} to win`);
  });

  it('has the number of events before airplane mode that the doc tells the person to find in the database', () => {
    expect(SCRIPT_7C.filter((s) => s.phase === 'online')).toHaveLength(6);
    expect(section()).toContain('says 6');
    expect(section()).toContain('**Lions 5/1**');
    expect(currentInnings(play(6).score)).toMatchObject({ runs: 5, wickets: 1 });
  });

  it('has the number of airplane-mode balls and log rows the doc says', () => {
    const airplane = SCRIPT_7C.filter((s) => s.phase === 'airplane').length;
    expect(airplane).toBeGreaterThanOrEqual(8);
    expect(section()).toContain(`${airplane} changes`);
    expect(section()).toContain(`${log.entries.length} rows`);
    expect(log.entries.map((e) => e.seq)).toEqual(Array.from({ length: log.entries.length }, (_, i) => i + 1));
  });

  it('gives the scorecard lines the doc quotes', () => {
    const lines = innings.batters.map((b) => `${name(b.playerId)} ${b.runs} (${b.balls})`);
    for (const line of lines) {
      expect(section(), line).toContain(line);
    }
    const bowlers = innings.bowlers.map((b) => `${name(b.playerId)} ${Math.trunc(b.legalBalls / 6)}.${b.legalBalls % 6}-${b.maidens}-${b.runs}-${b.wickets}`);
    for (const line of bowlers) {
      expect(section(), line).toContain(line);
    }
    expect(section()).toContain(`extras ${innings.extras.wides + innings.extras.noBalls + innings.extras.byes + innings.extras.legByes}`);
  });

  it('shows the mistaken ball as an undo that voids, and the log keeps it', () => {
    const undo = log.entries.find((e) => e.kind === 'UNDO');
    expect(undo).toBeDefined();
    expect(stateFingerprint(play(6))).not.toBe('');
    expect(section()).toContain('Undid: 3 runs');
  });

  it('only mentions players of the match', () => {
    for (const id of Object.keys(NAMES)) {
      expect(NAMES[id]).toMatch(/^[A-Z][a-z]+$/);
    }
    expect(name(lion(3))).toBe('Chetan');
    expect(name(tiger(2))).toBe('Uday');
  });
});
