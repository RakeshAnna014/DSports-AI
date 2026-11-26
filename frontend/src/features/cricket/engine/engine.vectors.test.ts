import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { canonicalJson, type Json } from './fingerprint';
import { newLog, stepCommand, stepUndo, type ScoringLog } from './log';
import { ENGINE_VERSION } from './match';
import type { MatchSetup, UndoMode } from './types';
import { expectOf } from './vectorExpect';
import { SUPPORTED_ENGINE_VERSIONS } from './vocabulary';
import { parseCommand, toBody } from './wire';

/**
 * The shared engine vectors (`testdata/cricket-engine`, written by the Java build and, for `from-ts`, by `pnpm run vectors:ts`) replayed against the TypeScript
 * engine. Every step must give exactly the recorded answer: accepted or the rule that refused it, the seq, what an undo voided, the waivers, the fingerprint,
 * the status, what the scorer must do next, the end reason, the result type and, where recorded, the whole state. A mismatch names the file and the step.
 */
const ROOT = resolve(process.cwd(), '..', 'testdata', 'cricket-engine');
const manifest = JSON.parse(readFileSync(join(ROOT, 'manifest.json'), 'utf8')) as { engineVersion: number };

interface Step {
  op: 'command' | 'undo';
  command?: Json;
  mode?: UndoMode;
  expect: { [key: string]: Json };
}
interface VectorFile {
  name: string;
  setup: MatchSetup;
  steps: Step[];
}

const filesIn = (dir: string): string[] => {
  try {
    return readdirSync(join(ROOT, dir)).filter((f) => f.endsWith('.json') && f !== 'manifest.json').sort().map((f) => `${dir}/${f}`);
  } catch {
    return [];
  }
};
const load = (path: string): VectorFile => JSON.parse(readFileSync(join(ROOT, path), 'utf8')) as VectorFile;

/** Replays one file; returns the number of steps, or throws with the file, the step and both answers. */
export function replay(path: string): number {
  const file = load(path);
  let log: ScoringLog = newLog(file.setup);
  file.steps.forEach((step, index) => {
    const where = `${path} step ${index + 1}`;
    let result;
    if (step.op === 'command') {
      const command = parseCommand(step.command);
      expect(canonicalJson(toBody(command) as Json), `${where}: the body is the canonical one`).toBe(canonicalJson(step.command as Json));
      ({ log, result } = stepCommand(log, command));
    } else {
      ({ log, result } = stepUndo(log, step.mode!));
    }
    const actual = canonicalJson(expectOf(log, result, 'state' in step.expect));
    const recorded = canonicalJson(step.expect);
    if (actual !== recorded) {
      // Show where the two first differ, so a failure is a pointer and not a wall of text.
      let i = 0;
      while (i < actual.length && actual[i] === recorded[i]) {
        i++;
      }
      throw new Error(`${where} differs at character ${i}\n  TypeScript: …${actual.slice(Math.max(0, i - 80), i + 120)}\n  recorded:   …${recorded.slice(Math.max(0, i - 80), i + 120)}`);
    }
  });
  return file.steps.length;
}

describe('the engine version', () => {
  it('is the one the vectors were written for, and one this build says it supports', () => {
    expect(ENGINE_VERSION).toBe(manifest.engineVersion);
    expect(SUPPORTED_ENGINE_VERSIONS).toContain(ENGINE_VERSION);
  });
});

describe.each([
  ['golden', filesIn('golden')],
  ['random (Java made)', filesIn('random')],
])('vectors: %s', (_label, files) => {
  it('has files to replay', () => {
    expect(files.length).toBeGreaterThan(0);
  });
  it.each(files)('%s gives the recorded answer at every step', (path) => {
    expect(replay(path)).toBeGreaterThan(0);
  });
});

describe('vectors: made by this engine (from-ts)', () => {
  const files = filesIn('from-ts');
  it.each(files)('%s gives the recorded answer at every step', (path) => {
    expect(replay(path)).toBeGreaterThan(0);
  });
});
