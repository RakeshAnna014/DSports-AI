import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildTsVectors, generateMatch, Rng, TS_SEEDS } from './tsVectors';

/**
 * The TypeScript-made vectors (`testdata/cricket-engine/from-ts`). Writing them is deliberate: run
 * `VECTORS_WRITE=1 pnpm exec vitest run src/features/cricket/engine/tsVectors.test.ts` (or `pnpm run vectors:ts`) and review the diff. Without that variable this
 * file only checks that what is committed is exactly what the generator and the TypeScript engine produce now, byte for byte, and that the set is varied enough
 * to be worth the Java engine's time.
 */
const DIR = resolve(process.cwd(), '..', 'testdata', 'cricket-engine', 'from-ts');
const WRITE = process.env.VECTORS_WRITE === '1';

describe('the seeded generator', () => {
  it('gives the same numbers for the same seed, whatever the platform (integer maths only)', () => {
    const r = new Rng(12345);
    expect([r.int(1000), r.int(1000), r.int(1000), r.int(1000), r.int(1000)]).toEqual([979, 306, 484, 817, 509]);
    const again = new Rng(12345);
    expect([again.int(1000), again.int(1000)]).toEqual([979, 306]);
  });

  it('plays the same match for the same seed', () => {
    expect(JSON.stringify(generateMatch(7))).toBe(JSON.stringify(generateMatch(7)));
    expect(JSON.stringify(generateMatch(7))).not.toBe(JSON.stringify(generateMatch(8)));
  });
});

describe('the committed from-ts vectors', () => {
  const built = buildTsVectors();

  if (WRITE) {
    it('are written (VECTORS_WRITE=1)', () => {
      mkdirSync(DIR, { recursive: true });
      for (const name of readdirSync(DIR)) {
        rmSync(join(DIR, name));
      }
      for (const [name, text] of built) {
        writeFileSync(join(DIR, name), text, { encoding: 'utf8' });
      }
      expect(built.size).toBe(TS_SEEDS + 1);
    });
    return;
  }

  it('are exactly what the generator and the TypeScript engine produce now (regenerate with VECTORS_WRITE=1 and review the diff)', () => {
    const onDisk = readdirSync(DIR).sort();
    expect(onDisk, 'no stray or missing files in from-ts/').toEqual([...built.keys()].sort());
    const different = [...built].filter(([name, text]) => !readFileSync(join(DIR, name)).equals(Buffer.from(text, 'utf8'))).map(([name]) => name);
    expect(different).toEqual([]);
  });

  it('have no carriage returns (a checkout must not convert them, see .gitattributes)', () => {
    for (const name of readdirSync(DIR)) {
      expect(readFileSync(join(DIR, name), 'utf8'), name).not.toContain('\r');
    }
  });

  it('are varied: every command, dismissal and end appears accepted at least once, and most steps are accepted so the Java engine is really exercised', () => {
    const acceptedCommands = new Set<string>();
    const dismissals = new Set<string>();
    const ends = new Set<string>();
    const statuses = new Set<string>();
    let steps = 0;
    let accepted = 0;
    let undos = 0;
    for (const [name, text] of built) {
      if (name === 'manifest.json') {
        continue;
      }
      const file = JSON.parse(text) as { steps: { op: string; command?: { type: string; delivery?: { dismissal?: { type: string } } }; mode?: string; expect: { result: string; end?: string | null; status?: string } }[] };
      for (const step of file.steps) {
        steps++;
        if (step.expect.result !== 'accepted') {
          continue;
        }
        accepted++;
        if (step.op === 'undo') {
          undos++;
          continue;
        }
        acceptedCommands.add(step.command!.type);
        const dismissal = step.command!.delivery?.dismissal?.type;
        if (dismissal) {
          dismissals.add(dismissal);
        }
        if (step.expect.end) {
          ends.add(step.expect.end);
        }
        statuses.add(step.expect.status!);
      }
    }
    expect([...acceptedCommands].sort()).toEqual(['DELIVER', 'END_INNINGS', 'END_MATCH', 'REVISE_TARGET', 'RETIRE_BATTER', 'SELECT_BATTER', 'SELECT_BOWLER', 'CHANGE_BOWLER', 'START_INNINGS'].sort());
    expect([...dismissals].sort()).toEqual(['BOWLED', 'CAUGHT', 'HIT_BALL_TWICE', 'HIT_WICKET', 'LBW', 'OBSTRUCTING_FIELD', 'RUN_OUT', 'STUMPED']);
    expect([...ends].sort()).toEqual(['ALL_OUT', 'DECLARED', 'NO_BATTERS_LEFT', 'OVERS_COMPLETE', 'TARGET_REACHED']);
    expect([...statuses]).toEqual(expect.arrayContaining(['IN_PROGRESS', 'INNINGS_BREAK', 'COMPLETED', 'ABANDONED', 'NO_RESULT']));
    expect(undos).toBeGreaterThan(100);
    expect(steps).toBeGreaterThan(3000);
    expect(accepted / steps, 'share of accepted steps').toBeGreaterThan(0.55);
  });
});
