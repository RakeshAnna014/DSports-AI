import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { commandsOf, effective, planUndo } from '../engine';
import { EngineStateFormatError, ownFingerprint, parseEngineState, toServerView } from './engineStateApi';
import { selfCheck } from './selfCheck';

/**
 * The route's answer, as the Java build writes it (`testdata/cricket-engine/server/`), read with the phone's own parser, folded with the phone's own engine: the
 * fingerprint the server computed must be the one this phone computes, for a log with undos too, and for an asker whose events are somebody else's.
 */
const DIR = resolve(process.cwd(), '..', 'testdata', 'cricket-engine', 'server');
const names = readdirSync(DIR).filter((f) => f.startsWith('engine-state-') && f.endsWith('.json')).sort();
const sample = (name: string): unknown => JSON.parse(readFileSync(join(DIR, name), 'utf8'));

describe('the engine-state answer written by the Java route', () => {
  it('has the samples to read', () => {
    expect(names).toEqual(['engine-state-golden-match-own.json', 'engine-state-golden-match-with-undos-foreign.json']);
  });

  it.each(names)('%s: the phone folds the log to the fingerprint the server computed, and the self-check agrees', (name) => {
    const answer = parseEngineState(sample(name));
    expect(answer.engineVersion).toBe(1);
    expect(answer.ready).toBe(true);
    expect(answer.seq).toBe(answer.entries.length);
    expect(ownFingerprint(answer)).toBe(answer.stateFingerprint);
    expect(selfCheck(answer)).toEqual({ on: true });
    expect(toServerView(answer).seq).toBe(answer.seq);
  });

  it('keeps client event ids for the asker\'s own events only, and reads the whole of a golden match', () => {
    const own = parseEngineState(sample('engine-state-golden-match-own.json'));
    expect(own.entries.every((e) => e.clientEventId !== null && e.kind === 'COMMAND')).toBe(true);
    expect(own.entries.length).toBeGreaterThan(30);
    expect(own.setup.xiA).toHaveLength(11);
    expect(own.setup.overs).toBe(2);
    const foreign = parseEngineState(sample('engine-state-golden-match-with-undos-foreign.json'));
    expect(foreign.entries.every((e) => e.clientEventId === null)).toBe(true);
  });

  it('reads undos with what they voided, so planUndo and the effective log work on the answer exactly as on a log made on the phone', () => {
    const foreign = parseEngineState(sample('engine-state-golden-match-with-undos-foreign.json'));
    const undos = foreign.entries.filter((e) => e.kind === 'UNDO');
    expect(undos.length).toBeGreaterThan(0);
    expect(effective(foreign.entries).length).toBeLessThan(foreign.entries.filter((e) => e.kind === 'COMMAND').length);
    expect(commandsOf(foreign.entries).length).toBe(effective(foreign.entries).length);
    expect(planUndo(foreign.entries, 'COMMAND')).toHaveLength(1);
  });

  it('refuses an answer that is not in the shape the route writes, with the reason', () => {
    const good = sample('engine-state-golden-match-own.json') as Record<string, unknown>;
    const bad = (change: (o: Record<string, unknown>) => void): string => {
      const copy = structuredClone(good);
      change(copy);
      try {
        parseEngineState(copy);
        return 'ACCEPTED';
      } catch (e) {
        expect(e).toBeInstanceOf(EngineStateFormatError);
        return (e as Error).message;
      }
    };
    expect(bad((o) => (o.seq = 3))).toContain('seq is 3');
    expect(bad((o) => ((o.entries as { seq: number }[])[2].seq = 9))).toContain('without gaps');
    expect(bad((o) => ((o.entries as { kind: string }[])[0].kind = 'SOMETHING'))).toContain('unknown kind');
    expect(bad((o) => delete o.setup)).toContain('setup is missing');
    expect(bad((o) => ((o.setup as { xiA: unknown }).xiA = 'nope'))).toContain('xiA is not a list');
    expect(bad((o) => ((o.entries as { command: { type: string } }[])[1].command.type = 'FLY'))).not.toBe('ACCEPTED');
    expect(bad((o) => (o.engineVersion = 1.5))).toContain('engineVersion');
    expect(() => parseEngineState('text')).toThrow(EngineStateFormatError);
    expect(() => parseEngineState(null)).toThrow(EngineStateFormatError);
  });

  it('is not fooled by a fingerprint that does not match the log: the self-check turns offline scoring off', () => {
    const answer = parseEngineState(sample('engine-state-golden-match-own.json'));
    expect(selfCheck({ ...answer, stateFingerprint: '0123456789abcdef' })).toEqual({ on: false, reason: 'FINGERPRINT_MISMATCH' });
    expect(selfCheck({ ...answer, engineVersion: 2 })).toEqual({ on: false, reason: 'ENGINE_VERSION_UNSUPPORTED' });
  });
});
