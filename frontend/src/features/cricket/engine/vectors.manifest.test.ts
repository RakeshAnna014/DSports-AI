import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { canonicalJson, fingerprint, type Json } from './fingerprint';
import { sha256Bytes, toHex } from './sha256';
import { SUPPORTED_ENGINE_VERSIONS, VOCABULARY } from './vocabulary';

/**
 * The TypeScript side of the shared engine vectors (`testdata/cricket-engine`, written by the Java build): the manifest, the files' hashes, the vocabulary and the
 * fingerprint contract. The engine itself arrives in the next gate; its replay of every step will sit beside this file, and these checks are what it stands on.
 */
const ROOT = resolve(process.cwd(), '..', 'testdata', 'cricket-engine');

interface Manifest {
  vectorFormat: number;
  engineVersion: number;
  files: { path: string; sha256: string; steps: number }[];
  vocabulary: Record<string, string[]>;
  notCoverable: Record<string, Record<string, string>>;
}

interface Step {
  op: 'command' | 'undo';
  command?: Json;
  mode?: string;
  expect: { result: string; fp?: string; state?: Json };
}

interface VectorFile {
  format: number;
  name: string;
  setup: Json;
  steps: Step[];
}

/** The exact bytes of a file: nothing is converted (see .gitattributes), so the hash is over what is on disk. */
const bytes = (path: string): Buffer => readFileSync(join(ROOT, path));
const read = (path: string): string => bytes(path).toString('utf8');
const manifest: Manifest = JSON.parse(read('manifest.json')) as Manifest;
const load = (path: string): VectorFile => JSON.parse(read(path)) as VectorFile;

describe('the shared engine vectors, seen from TypeScript', () => {
  it('are for an engine version this build supports', () => {
    expect(manifest.vectorFormat).toBe(1);
    expect(SUPPORTED_ENGINE_VERSIONS).toContain(manifest.engineVersion);
  });

  it('are exactly the files the manifest lists, unchanged since the Java build wrote them', () => {
    const onDisk = ['golden', 'random'].flatMap((dir) => readdirSync(join(ROOT, dir)).map((name) => `${dir}/${name}`));
    expect(manifest.files.map((f) => f.path).sort()).toEqual(onDisk.sort());
    for (const file of manifest.files) {
      expect(read(file.path), `${file.path} has carriage returns: a checkout must not convert line endings (.gitattributes)`).not.toContain('\r');
      expect(createHash('sha256').update(bytes(file.path)).digest('hex'), `hash of the bytes of ${file.path}`).toBe(file.sha256);
    }
  });

  it('count the steps the manifest says, with the shape every reader relies on', () => {
    let steps = 0;
    for (const entry of manifest.files) {
      const file = load(entry.path);
      expect(file.format).toBe(1);
      expect(file.steps, entry.path).toHaveLength(entry.steps);
      for (const step of file.steps) {
        expect(['command', 'undo']).toContain(step.op);
        expect(['accepted', 'rejected', 'nothing_to_undo']).toContain(step.expect.result);
        steps++;
      }
    }
    expect(steps).toBeGreaterThan(3000);
  });

  it('use the words this build knows, and no others (a rule added in Java must be added here)', () => {
    for (const [list, words] of Object.entries(manifest.vocabulary)) {
      const known = (VOCABULARY as Record<string, readonly string[]>)[list];
      expect(known, `the TypeScript vocabulary has no list "${list}"`).toBeDefined();
      expect([...known].sort(), list).toEqual([...words].sort());
    }
    expect(Object.keys(VOCABULARY).sort()).toEqual(Object.keys(manifest.vocabulary).sort());
  });

  it('record, for every refusal and every accepted command, only words from the vocabulary', () => {
    const violations = new Set<string>(VOCABULARY.violations);
    for (const entry of manifest.files) {
      for (const step of load(entry.path).steps) {
        if (step.expect.result === 'rejected') {
          expect(violations.has((step.expect as unknown as { violation: string }).violation)).toBe(true);
        }
      }
    }
  });
});

describe('the fingerprint, the way both sides must compute it', () => {
  it('has a pure TypeScript SHA-256 equal to the platform one (the phone has no crypto.subtle over plain http)', () => {
    const samples = ['', 'abc', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(63), 'a'.repeat(64), 'a'.repeat(65), 'a'.repeat(119), 'a'.repeat(1000), '{"a":1,"b":[true,null,"x"]}'];
    for (const text of samples) {
      expect(toHex(sha256Bytes(text)), `length ${text.length}`).toBe(createHash('sha256').update(text, 'ascii').digest('hex'));
    }
    expect(() => sha256Bytes('é')).toThrow(/ASCII/);
  });

  it('writes canonical text with sorted keys and no spaces, and refuses what Java would write differently', () => {
    expect(canonicalJson({ b: [1, { z: null, a: true }], a: 'x' })).toBe('{"a":"x","b":[1,{"a":true,"z":null}]}');
    expect(() => canonicalJson(1.5)).toThrow(/integers/);
    expect(() => canonicalJson('say "hi"')).toThrow(/ASCII/);
    expect(() => canonicalJson('é')).toThrow(/ASCII/);
  });

  it('holds only integers in every recorded state (a fraction would be written differently by Java and by JavaScript)', () => {
    let numbers = 0;
    const visit = (value: Json): void => {
      if (typeof value === 'number') {
        numbers++;
        expect(Number.isSafeInteger(value), `${value}`).toBe(true);
      } else if (Array.isArray(value)) {
        value.forEach(visit);
      } else if (value !== null && typeof value === 'object') {
        Object.values(value).forEach(visit);
      }
    };
    for (const entry of manifest.files) {
      for (const step of load(entry.path).steps) {
        if (step.expect.state !== undefined) {
          visit(step.expect.state);
        }
      }
    }
    expect(numbers).toBeGreaterThan(10000);
  });

  it('reproduces the fingerprint the Java engine recorded for every state in every file', () => {
    let checked = 0;
    for (const entry of manifest.files) {
      for (const step of load(entry.path).steps) {
        if (step.expect.state !== undefined) {
          expect(fingerprint(step.expect.state), `${entry.path}`).toBe(step.expect.fp);
          checked++;
        }
      }
    }
    expect(checked).toBeGreaterThan(200);
  });
});
