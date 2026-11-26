import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CommandBody } from '@/types/cricketScoring';
import { defaultRunOutEnd, nextToFace, runOutPreview } from './runOutEnd';
import { clearOutbox, loadOutbox, saveOutbox, type OutboxItem } from './scoringOutbox';
import { newId } from './newId';
import { ruleMessage } from './ruleMessages';

describe('the run-out end default', () => {
  it('follows the table: striker even -> bowler end, striker odd -> striker end, non-striker even -> striker end, non-striker odd -> bowler end', () => {
    for (const runs of [0, 2, 4]) {
      expect(defaultRunOutEnd('STRIKER', runs)).toBe('BOWLERS_END');
      expect(defaultRunOutEnd('NON_STRIKER', runs)).toBe('BATTING_END');
    }
    for (const runs of [1, 3, 5, 7]) {
      expect(defaultRunOutEnd('STRIKER', runs)).toBe('BATTING_END');
      expect(defaultRunOutEnd('NON_STRIKER', runs)).toBe('BOWLERS_END');
    }
  });

  it('is the end the hand-scored innings used: Arun the striker after one run, out at the striker end', () => {
    expect(defaultRunOutEnd('STRIKER', 1)).toBe('BATTING_END');
  });

  it('says the new batter faces when he takes the striker end, unless the over has just ended, and then the partner does', () => {
    expect(nextToFace('BATTING_END', false)).toBe('NEW_BATTER');
    expect(nextToFace('BOWLERS_END', false)).toBe('PARTNER');
    expect(nextToFace('BATTING_END', true)).toBe('PARTNER');
    expect(nextToFace('BOWLERS_END', true)).toBe('NEW_BATTER');
  });

  it('turns that into a sentence for the scorer', () => {
    expect(runOutPreview('Arun', 'Bala', 'BATTING_END', false)).toBe('Arun is out. The new batter will face.');
    expect(runOutPreview('Arun', 'Bala', 'BOWLERS_END', false)).toBe('Arun is out. Bala will face.');
    expect(runOutPreview('Arun', null, 'BOWLERS_END', false)).toBe('Arun is out. The new batter will face.');
  });
});

describe('the outbox', () => {
  const item: OutboxItem = { kind: 'command', clientEventId: 'e-1', expectedSeq: 4, command: { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 1 } } as CommandBody, label: '1 run', createdAt: '2026-11-20T10:00:00Z' };

  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('keeps a change across a reload and gives it back exactly', () => {
    saveOutbox('m-1', item);
    expect(loadOutbox('m-1')).toEqual(item);
  });

  it('keeps one slot per match', () => {
    saveOutbox('m-1', item);
    expect(loadOutbox('m-2')).toBeNull();
    saveOutbox('m-2', { ...item, clientEventId: 'e-2' });
    expect(loadOutbox('m-1')?.clientEventId).toBe('e-1');
    clearOutbox('m-1');
    expect(loadOutbox('m-1')).toBeNull();
    expect(loadOutbox('m-2')?.clientEventId).toBe('e-2');
  });

  it('keeps an undo too', () => {
    const undo: OutboxItem = { kind: 'undo', clientEventId: 'e-3', expectedSeq: 9, mode: 'BALL', label: 'Undo: 4 runs', createdAt: '2026-11-20T10:00:00Z' };
    saveOutbox('m-1', undo);
    expect(loadOutbox('m-1')).toEqual(undo);
  });

  it('ignores rubbish in storage instead of breaking the screen', () => {
    for (const rubbish of ['not json', '{}', '[]', '{"kind":"command"}', '{"kind":"undo","clientEventId":"x","expectedSeq":1,"label":"l","mode":"SIDEWAYS"}', 'null']) {
      localStorage.setItem('dsports.scoring.outbox.m-1', rubbish);
      expect(loadOutbox('m-1')).toBeNull();
    }
  });

  it('still works when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(() => saveOutbox('m-1', item)).not.toThrow();
    expect(loadOutbox('m-1')).toBeNull();
    expect(() => clearOutbox('m-1')).not.toThrow();
  });
});

describe('newId', () => {
  const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  afterEach(() => vi.unstubAllGlobals());

  it('makes a v4 uuid where the browser can', () => {
    expect(newId()).toMatch(V4);
  });

  it('still makes one on a phone using plain http, where randomUUID does not exist', () => {
    vi.stubGlobal('crypto', { getRandomValues: (a: Uint8Array) => { for (let i = 0; i < a.length; i += 1) { a[i] = (i * 37 + 11) % 256; } return a; } });
    const id = newId();
    expect(id).toMatch(V4);
    expect(newId()).toBe(id);
  });

  it.each([
    ['all bits off', 0x00, '00000000-0000-4000-8000-000000000000'],
    ['all bits on', 0xff, 'ffffffff-ffff-4fff-bfff-ffffffffffff'],
  ])('forces the version 4 and variant bits in the fallback (%s)', (_name, byte, expected) => {
    vi.stubGlobal('crypto', { getRandomValues: (a: Uint8Array) => a.fill(byte) });
    expect(newId()).toBe(expected);
  });

  it('even with no crypto at all, and ids differ', () => {
    vi.stubGlobal('crypto', undefined);
    const ids = new Set(Array.from({ length: 50 }, () => newId()));
    expect(ids.size).toBe(50);
    ids.forEach((id) => expect(id).toMatch(V4));
  });
});

describe('rule messages', () => {
  it('names the rule in plain words and falls back to the server message', () => {
    expect(ruleMessage('DISMISSAL_NOT_ALLOWED_ON_FREE_HIT', 'x')).toBe('That kind of dismissal cannot happen on a free hit.');
    expect(ruleMessage('SOMETHING_NEW', 'The server says so')).toBe('The server says so');
  });
});
