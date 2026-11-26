import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { describeCommand, describeUndo } from './queue';

/**
 * The queue's logic must stay testable without a browser: `queue`, `load`, `selfCheck`, `exportText` and `types` read no clock, make no ids, touch no storage
 * and use no network (time and ids are passed in; storage is an interface). Only `idbStorage.ts` may touch IndexedDB. This reads the source and fails otherwise.
 */
const DIR = resolve(process.cwd(), 'src', 'features', 'cricket', 'offline');
const PURE = ['queue.ts', 'load.ts', 'selfCheck.ts', 'exportText.ts', 'types.ts', 'storage.ts'];
const FORBIDDEN: [RegExp, string][] = [
  [/Date\.now|new Date|performance\.now/, 'a clock'],
  [/Math\.random|crypto\.|randomUUID/, 'randomness'],
  [/\bfetch\s*\(|XMLHttpRequest|axios|WebSocket|navigator\./, 'the network or the browser'],
  [/localStorage|sessionStorage|indexedDB|IndexedDB/, 'browser storage'],
  [/\bwindow\.|\bdocument\.|setTimeout|setInterval/, 'the DOM or timers'],
  [/from\s+['"](react|@mui|@emotion|react-dom|react-router|axios)/, 'a UI or network library'],
];

describe('the queue logic is pure', () => {
  it.each(PURE)('%s reads no clock, makes no ids, touches no browser API', (file) => {
    const text = readFileSync(join(DIR, file), 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/^\s*\/\/.*$/gm, '');
    for (const [pattern, what] of FORBIDDEN) {
      expect(pattern.test(text), `${file} uses ${what}`).toBe(false);
    }
  });
});

describe('the words for a queued ball', () => {
  const deliver = (over: object) => describeCommand({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 0, runsType: 'BAT', boundary: false, dismissal: null, ...over } } as never);
  it('reads like the scorebook, without a player name', () => {
    expect(deliver({})).toBe('Dot ball');
    expect(deliver({ runs: 1 })).toBe('1 run');
    expect(deliver({ runs: 3 })).toBe('3 runs');
    expect(deliver({ runs: 4, boundary: true })).toBe('4 (boundary)');
    expect(deliver({ kind: 'WIDE', runsType: null })).toBe('Wide');
    expect(deliver({ kind: 'WIDE', runs: 2, runsType: null })).toBe('Wide + 2 runs');
    expect(deliver({ kind: 'NO_BALL' })).toBe('No ball');
    expect(deliver({ kind: 'NO_BALL', runs: 1, runsType: 'BYE' })).toBe('No ball + 1 run as bye');
    expect(deliver({ runs: 2, runsType: 'LEG_BYE' })).toBe('2 runs as leg bye');
    expect(deliver({ dismissal: { type: 'RUN_OUT', batterOut: null, end: 'BATTING_END', fielderId: null } })).toBe('Dot ball + wicket (run out)');
    expect(describeCommand({ type: 'RETIRE_BATTER', batterId: 'x', retirement: 'HURT' })).toBe('Batter retired hurt');
    expect(describeCommand({ type: 'REVISE_TARGET', target: 120 })).toBe('Revised target 120');
    expect(describeCommand({ type: 'END_INNINGS', reason: 'NO_BATTERS_LEFT' })).toBe('End innings (no batters left)');
    expect(describeUndo('BALL')).toBe('Undo last ball');
    expect(describeUndo('COMMAND')).toBe('Undo last entry');
  });
});
