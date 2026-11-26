import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The engine must stay pure so the queue can use it, a worker can run it and a test can replay it: no React, no network, no browser storage, no clock, no
 * randomness, and no import from outside this folder. This reads the source and fails on any of them. (Time and ids are passed in; the vector generator has its
 * own seeded generator.) The test files themselves are exempt: they read the vector files from disk.
 */
const DIR = resolve(process.cwd(), 'src', 'features', 'cricket', 'engine');
const sources = readdirSync(DIR).filter((f) => f.endsWith('.ts') && !f.endsWith('.test.ts'));

const FORBIDDEN: [RegExp, string][] = [
  [/Date\.now|new Date|performance\.now/, 'a clock (time must be passed in)'],
  [/Math\.random|crypto\.|randomUUID/, 'unseeded randomness (ids and randomness must be passed in)'],
  [/\bfetch\s*\(|XMLHttpRequest|axios|WebSocket|EventSource|navigator\./, 'the network or the browser'],
  [/localStorage|sessionStorage|indexedDB|IndexedDB/, 'storage (the queue owns storage, the engine does not)'],
  [/\bwindow\b|\bdocument\b|setTimeout|setInterval/, 'the DOM or timers'],
  [/\bprocess\./, 'the process environment'],
  [/from\s+['"](react|@mui|@emotion|react-dom|react-router)/, 'a UI library'],
];

describe('the engine is pure', () => {
  it('has source files to check', () => {
    expect(sources).toEqual(expect.arrayContaining(['innings.ts', 'match.ts', 'log.ts', 'wire.ts', 'rules.ts', 'types.ts', 'index.ts']));
  });

  it.each(sources)('%s uses no clock, randomness, network, storage, DOM or UI library, and imports only from this folder', (file) => {
    const text = readFileSync(join(DIR, file), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '');
    for (const [pattern, what] of FORBIDDEN) {
      expect(pattern.test(text), `${file} uses ${what}`).toBe(false);
    }
    for (const match of text.matchAll(/from\s+'([^']+)'/g)) {
      expect(match[1].startsWith('./'), `${file} imports ${match[1]}: the engine imports only from its own folder`).toBe(true);
    }
  });
});
