import { describe, expect, it } from 'vitest';
import { stateFingerprint, type ScoreCommand } from '../engine';
import { lion, tiger } from '../engine/tsVectors';
import { exportText } from './exportText';
import { loadQueue, newQueueState } from './load';
import * as q from './queue';
import { selfCheck } from './selfCheck';
import { MemoryStorage, StorageError } from './storage';
import { FakeServer, MATCH, Phone, SETUP, START, USER } from './testHarness';
import { QUEUE_CAP } from './types';

const ball = (runs = 0, extra: object = {}): ScoreCommand => ({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs, runsType: 'BAT', boundary: false, dismissal: null, ...extra } });
const wide = (): ScoreCommand => ({ type: 'DELIVER', delivery: { kind: 'WIDE', runs: 0, runsType: null, boundary: false, dismissal: null } });
const bowled = (): ScoreCommand => ball(0, { dismissal: { type: 'BOWLED', batterOut: null, end: null, fielderId: null } });

async function started(): Promise<Phone> {
  const phone = new Phone(new FakeServer(SETUP));
  await phone.tap(START);
  return phone;
}
const flush = async (phone: Phone): Promise<void> => {
  for (let i = 0; i < 2000 && q.nextToSend(phone.state) !== null; i++) {
    await phone.sendNext();
  }
};
const storedItems = async (phone: Phone) => (await phone.storage.items(MATCH)).map((r) => r.row);

describe('taking a tap', () => {
  it('queues a legal ball with its own id and the seq the server will give it, and saves it before the new state is adopted', async () => {
    const phone = await started();
    const step = await phone.tap(ball(1));
    expect(step.refused).toBeNull();
    const [first, second] = phone.state.items;
    expect([first.expectedSeq, second.expectedSeq]).toEqual([0, 1]);
    expect(first.clientEventId).not.toBe(second.clientEventId);
    expect(second).toMatchObject({ status: 'WAITING', kind: 'COMMAND', label: '1 run', attempts: 0, userId: USER });
    expect(await storedItems(phone), 'storage holds exactly what the state holds').toEqual(phone.state.items);
    expect(phone.state.local.entries).toHaveLength(2);
    expect(phone.state.counts).toEqual({ enqueued: 2, confirmed: 0, discarded: 0 });
    expect((await phone.storage.journal(MATCH)).map((j) => j.event)).toEqual(['enqueued', 'enqueued']);
  });

  it('does not queue what the engine already knows is illegal, and says which rule', async () => {
    const phone = new Phone(new FakeServer(SETUP));
    const early = await phone.tap(ball(1));
    expect(early.refused).toMatchObject({ code: 'RULE', violation: 'INNINGS_NOT_STARTED' });
    expect(phone.state.items).toHaveLength(0);
    expect(await storedItems(phone)).toEqual([]);
    await phone.tap(START);
    expect((await phone.tap(ball(9))).refused).toMatchObject({ code: 'RULE', violation: 'RUNS_OUT_OF_RANGE' });
    expect((await phone.undo()).refused, 'the start of the innings is the one entry, so undo is possible').toBeNull();
    expect((await phone.undo()).refused).toMatchObject({ code: 'NOTHING_TO_UNDO' });
    expect(phone.state.counts.enqueued).toBe(2);
  });

  it('shows no ball when storage fails: the state, the storage and the screen stay as they were', async () => {
    const phone = await started();
    const before = JSON.stringify(phone.state);
    phone.storage.failNext(new StorageError('quota exceeded'));
    await expect(phone.tap(ball(4))).rejects.toThrow(/quota/);
    expect(JSON.stringify(phone.state)).toBe(before);
    expect(await storedItems(phone)).toHaveLength(1);
    expect((await phone.tap(ball(4))).refused, 'the next tap works once storage does').toBeNull();
    expect(phone.state.items).toHaveLength(2);
  });

  it('refuses the 601st change, never drops one, and takes taps again as soon as a send frees space', async () => {
    const big = { ...SETUP, overs: 100 };
    const view = new FakeServer(big).view();
    const phone = new Phone(new FakeServer(big), newQueueState({ matchId: MATCH, userId: USER, setup: big, entries: [], engineVersion: 1, stateFingerprint: view.stateFingerprint }));
    await phone.tap(START);
    for (let i = 1; i < QUEUE_CAP; i++) {
      expect((await phone.tap(wide())).refused, `item ${i + 1}`).toBeNull();
    }
    expect(phone.state.items).toHaveLength(QUEUE_CAP);
    expect(q.queueLevel(phone.state)).toBe('FULL');
    const full = await phone.tap(wide());
    expect(full.refused).toMatchObject({ code: 'QUEUE_FULL' });
    expect(full.refused!.message).toContain('600');
    expect(phone.state.items).toHaveLength(QUEUE_CAP);
    expect(await storedItems(phone)).toHaveLength(QUEUE_CAP);
    await phone.sendNext();
    expect((await phone.tap(wide())).refused, 'one send freed one place').toBeNull();
    expect(phone.state.items).toHaveLength(QUEUE_CAP);
  });

  it('warns from 150 items', async () => {
    const big = { ...SETUP, overs: 100 };
    const phone = new Phone(new FakeServer(big), newQueueState({ matchId: MATCH, userId: USER, setup: big, entries: [], engineVersion: 1, stateFingerprint: new FakeServer(big).view().stateFingerprint }));
    await phone.tap(START);
    for (let i = 0; i < 148; i++) {
      await phone.tap(wide());
    }
    expect(q.queueLevel(phone.state)).toBe('OK');
    await phone.tap(wide());
    expect(q.queueLevel(phone.state)).toBe('WARN');
  });
});

describe('sending', () => {
  it('sends one at a time, in order, and each 200 moves the item into the confirmed log and out of storage', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    await phone.tap(wide());
    expect(q.nextToSend(phone.state)!.n).toBe(1);
    await phone.sendNext();
    expect(phone.state.items.map((i) => i.n)).toEqual([2, 3]);
    expect(phone.state.confirmedSeq).toBe(1);
    expect(phone.server.log.entries).toHaveLength(1);
    await flush(phone);
    expect(phone.state.items).toEqual([]);
    expect(phone.state.confirmedSeq).toBe(3);
    expect(stateFingerprint(phone.state.local)).toBe(stateFingerprint(phone.server.log));
    expect(await storedItems(phone)).toEqual([]);
    expect(phone.state.counts).toEqual({ enqueued: 3, confirmed: 3, discarded: 0 });
    expect(phone.server.applied).toEqual(phone.taken);
  });

  it('keeps one in flight and refuses to send a second, or to confirm one that is not the first', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    const first = q.nextToSend(phone.state)!;
    await phone.commit(q.sendStarted(phone.state, first.n, 't'));
    expect(q.nextToSend(phone.state), 'one in flight blocks the next').toBeNull();
    expect(() => q.sendStarted(phone.state, 2, 't')).toThrow();
    expect(() => q.confirm(phone.state, 2, 2, false, 't'), 'an acknowledgement out of order is a bug, never applied').toThrow();
    expect(phone.state.items).toHaveLength(2);
  });

  it('keeps the item and its id after a network failure, and counts the attempt', async () => {
    const phone = await started();
    const id = phone.state.items[0].clientEventId;
    expect(await phone.sendNext('network')).toBe('network');
    expect(phone.state.items[0]).toMatchObject({ clientEventId: id, status: 'WAITING', attempts: 1, detail: 'no network' });
    expect(phone.server.log.entries).toHaveLength(0);
    expect(await phone.sendNext()).toBe('confirmed');
    expect(phone.server.applied).toEqual([id]);
  });

  it('treats a lost answer as a harmless replay: the server applied it once, the second send is answered "replayed"', async () => {
    const phone = await started();
    await phone.tap(ball(2));
    expect(await phone.sendNext('lostAnswer')).toBe('lost');
    expect(phone.server.log.entries).toHaveLength(1);
    expect(await phone.sendNext()).toBe('replayed');
    expect(await phone.sendNext()).toBe('confirmed');
    expect(phone.server.log.entries).toHaveLength(2);
    expect(new Set(phone.server.applied).size).toBe(phone.server.applied.length);
    expect(phone.state.counts).toEqual({ enqueued: 2, confirmed: 2, discarded: 0 });
    expect((await phone.storage.journal(MATCH)).map((j) => j.event)).toContain('replayed');
  });

  it('survives a reload at every point: an item that was in flight waits again with the same id and nothing is duplicated', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    await phone.tap(bowled());
    const ids = phone.state.items.map((i) => i.clientEventId);
    await phone.sync();
    await phone.commit(q.sendStarted(phone.state, 1, 't'));
    phone.server.receive(phone.state.items[0]);
    const reloaded = await phone.reload();
    expect(reloaded.items.map((i) => [i.clientEventId, i.status])).toEqual(ids.map((id) => [id, 'WAITING']));
    expect(reloaded.items.map((i) => i.expectedSeq)).toEqual([0, 1, 2]);
    await phone.sync();
    await flush(phone);
    expect(phone.state.items).toEqual([]);
    expect(phone.server.applied).toEqual(ids);
  });

  it('notices items the server already holds when the screen opens, and resumes with the right seqs', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    await phone.sendNext('lostAnswer');
    await phone.sync();
    expect(phone.state.blocked).toBeNull();
    expect(phone.state.items.map((i) => [i.label, i.expectedSeq])).toEqual([['1 run', 1]]);
    expect(phone.state.counts).toEqual({ enqueued: 2, confirmed: 1, discarded: 0 });
    expect(phone.state.confirmedSeq).toBe(1);
    await flush(phone);
    expect(phone.server.log.entries).toHaveLength(2);
  });
});

describe('another scorer, or an administrator, changed the match meanwhile', () => {
  async function conflicted(): Promise<Phone> {
    const phone = await started();
    await phone.sendNext();
    await phone.tap(ball(1));
    await phone.tap(ball(2));
    await phone.tap(wide());
    expect(phone.server.foreign(ball(3))).toBe(true);
    expect(phone.server.foreign(ball(1))).toBe(true);
    expect(await phone.sendNext()).toBe('conflict');
    return phone;
  }

  it('stops, holds every waiting ball, shows the server log, and turns the pad off', async () => {
    const phone = await conflicted();
    expect(phone.state.blocked).toMatchObject({ kind: 'CONFLICT' });
    expect(phone.state.items.map((i) => i.status)).toEqual(['WAITING', 'WAITING', 'WAITING']);
    expect(q.nextToSend(phone.state)).toBeNull();
    expect((await phone.tap(ball(1))).refused).toMatchObject({ code: 'BLOCKED' });
    expect(phone.state.confirmedSeq).toBe(3);
    expect(phone.server.log.entries).toHaveLength(3);
  });

  it('previews adding the balls after theirs, and a rebase keeps the ids, gives new seqs, and the server accepts each in turn', async () => {
    const phone = await conflicted();
    const preview = q.previewRebase(phone.state);
    expect(preview).toMatchObject({ total: 3, applicable: 3, refusal: null });
    const ids = phone.state.items.map((i) => i.clientEventId);
    await phone.commit(q.rebase(phone.state, 't'));
    expect(phone.state.blocked).toBeNull();
    expect(phone.state.items.map((i) => i.clientEventId)).toEqual(ids);
    expect(phone.state.items.map((i) => i.expectedSeq)).toEqual([3, 4, 5]);
    await flush(phone);
    expect(phone.server.log.entries).toHaveLength(6);
    expect(phone.server.applied.slice(-3)).toEqual(ids);
    expect(new Set(phone.server.applied).size).toBe(phone.server.applied.length);
    expect(phone.state.items).toEqual([]);
    expect((await phone.storage.journal(MATCH)).filter((j) => j.event === 'rebased')).toHaveLength(3);
  });

  it('sets aside the balls that cannot be added after theirs, with the rule that stops them, and loses none', async () => {
    const phone = await started();
    await phone.sendNext();
    await phone.tap(ball(1));
    await phone.tap(ball(1));
    // The other scorer ends the match by hand: nothing after that can be added.
    phone.server.foreign({ type: 'END_MATCH', reason: 'ABANDONED' });
    expect(await phone.sendNext()).toBe('conflict');
    const preview = q.previewRebase(phone.state);
    expect(preview).toMatchObject({ total: 2, applicable: 0, refusal: { violation: 'MATCH_ENDED' } });
    await phone.commit(q.rebase(phone.state, 't'));
    expect(phone.state.items.map((i) => [i.status, i.reason])).toEqual([
      ['SET_ASIDE', 'CONFLICT_NOT_APPLICABLE'],
      ['SET_ASIDE', 'CONFLICT_NOT_APPLICABLE'],
    ]);
    expect(phone.state.items[0].detail).toContain('ended');
    expect(phone.state.counts).toEqual({ enqueued: 3, confirmed: 1, discarded: 0 });
    expect(await storedItems(phone)).toHaveLength(2);
  });

  it('keeps their version and sets mine aside, saved and exportable, when the scorer chooses so', async () => {
    const phone = await conflicted();
    await phone.commit(q.keepTheirs(phone.state, 't'));
    expect(phone.state.blocked).toBeNull();
    expect(phone.state.items.every((i) => i.status === 'SET_ASIDE' && i.reason === 'CONFLICT_KEPT_THEIRS')).toBe(true);
    expect(q.nextToSend(phone.state)).toBeNull();
    expect(exportText(phone.state).text.split('\n')).toHaveLength(3);
    expect(phone.state.local.entries, 'the screen follows the server again').toHaveLength(3);
    expect((await phone.tap(ball(1))).refused, 'scoring carries on from their version').toBeNull();
  });

  it('can discard a set-aside ball only, one at a time, and the discard is journaled', async () => {
    const phone = await conflicted();
    await phone.commit(q.keepTheirs(phone.state, 't'));
    expect(() => q.discard({ ...phone.state, items: [] }, 99, 't')).toThrow();
    await phone.commit(q.discard(phone.state, phone.state.items[0].n, 't'));
    expect(phone.state.items).toHaveLength(2);
    expect(phone.state.counts.discarded).toBe(1);
    expect((await phone.storage.journal(MATCH)).filter((j) => j.event === 'discarded')).toHaveLength(1);
    const waiting = await started();
    expect(() => q.discard(waiting.state, waiting.state.items[0].n, 't'), 'a waiting item can never be discarded').toThrow();
  });
});

describe('the 24 hour window, and being removed', () => {
  it('lets the balls before the deadline in, sets the rest aside as locked, and sends them again with the same ids once an administrator unlocks', async () => {
    const phone = await started();
    for (let i = 0; i < 4; i++) {
      await phone.tap(ball(1));
    }
    await phone.sendNext();
    await phone.sendNext();
    phone.server.locked = true;
    expect(await phone.sendNext()).toBe('locked');
    expect(phone.state.confirmedSeq).toBe(2);
    expect(phone.state.items.map((i) => [i.n, i.status, i.reason])).toEqual([
      [3, 'SET_ASIDE', 'LOCKED'],
      [4, 'SET_ASIDE', 'LOCKED'],
      [5, 'SET_ASIDE', 'LOCKED'],
    ]);
    expect(phone.state.blocked).toMatchObject({ kind: 'LOCKED' });
    expect(phone.state.items[0].detail).toContain('2026-11-24');
    const ids = phone.state.items.map((i) => i.clientEventId);
    phone.server.locked = false;
    await phone.commit(q.sendAgain(phone.state, 't'));
    expect(phone.state.blocked).toBeNull();
    expect(phone.state.items.map((i) => i.status)).toEqual(['WAITING', 'WAITING', 'WAITING']);
    await flush(phone);
    expect(phone.server.applied.slice(-3)).toEqual(ids);
    expect(phone.server.log.entries.map((e) => e.seq)).toEqual([1, 2, 3, 4, 5]);
  });

  it('sets everything aside when the scorer is no longer allowed, keeps it, and can send it again once allowed', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    phone.server.forbidden = true;
    expect(await phone.sendNext()).toBe('forbidden');
    expect(phone.state.items.every((i) => i.reason === 'FORBIDDEN')).toBe(true);
    expect(await storedItems(phone)).toHaveLength(2);
    phone.server.forbidden = false;
    await phone.commit(q.sendAgain(phone.state, 't'));
    await flush(phone);
    expect(phone.server.log.entries).toHaveLength(2);
  });
});

describe('the engines disagreeing, and the self-check', () => {
  it('stops on a refusal of a ball the phone accepted, turns offline scoring off, and keeps every ball sendable', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    const first = q.nextToSend(phone.state)!;
    await phone.commit(q.sendStarted(phone.state, first.n, 't'));
    await phone.commit(q.rejectedByServer(phone.state, first.n, 'INNINGS_COMPLETE', 'The innings is complete', 't'));
    expect(phone.state.offline).toEqual({ on: false, reason: 'ENGINE_MISMATCH' });
    expect(phone.state.blocked).toMatchObject({ kind: 'REJECTED', violation: 'INNINGS_COMPLETE' });
    expect(phone.state.items).toHaveLength(2);
    expect((await phone.tap(ball(1))).refused).toMatchObject({ code: 'OFFLINE_SCORING_OFF' });
    await phone.commit(q.setAsideFrom(phone.state, first.n, 'REJECTED_BY_SERVER', 't'));
    expect(phone.state.items.every((i) => i.status === 'SET_ASIDE')).toBe(true);
    expect(await storedItems(phone)).toHaveLength(2);
  });

  it('switches offline scoring off for a different engine version or fingerprint, without touching one waiting ball, which can still be sent', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    const view = phone.server.view();
    const input = { setup: SETUP, entries: view.entries, engineVersion: 1, stateFingerprint: view.stateFingerprint };
    expect(selfCheck(input)).toEqual({ on: true });
    expect(selfCheck({ ...input, engineVersion: 2 })).toEqual({ on: false, reason: 'ENGINE_VERSION_UNSUPPORTED' });
    expect(selfCheck({ ...input, stateFingerprint: '0000000000000000' })).toEqual({ on: false, reason: 'FINGERPRINT_MISMATCH' });
    expect(selfCheck({ ...input, entries: [{ seq: 1, kind: 'COMMAND', command: ball(1), clientEventId: null, waivers: [] }] })).toEqual({ on: false, reason: 'LOG_UNREADABLE' });
    const before = JSON.stringify(phone.state.items);
    await phone.commit(q.setOfflineScoring(phone.state, { on: false, reason: 'FINGERPRINT_MISMATCH' }, 't'));
    expect(JSON.stringify(phone.state.items)).toBe(before);
    expect((await phone.tap(ball(1))).refused).toMatchObject({ code: 'OFFLINE_SCORING_OFF' });
    expect(await phone.sendNext()).toBe('confirmed');
    expect(await phone.sendNext()).toBe('confirmed');
    expect(phone.server.log.entries).toHaveLength(2);
  });

  it('does not accept a 200 whose seq is not the predicted one as agreement: the item is confirmed (the server has it) but offline scoring goes off', async () => {
    const phone = await started();
    await phone.commit(q.sendStarted(phone.state, 1, 't'));
    await phone.commit(q.confirm(phone.state, 1, 7, false, 't'));
    expect(phone.state.offline).toEqual({ on: false, reason: 'ENGINE_MISMATCH' });
    expect(phone.state.counts.confirmed).toBe(1);
  });
});

describe('what survives, and whose it is', () => {
  it('rebuilds the same state from storage alone, including the log confirmed since the last full save', async () => {
    const phone = await started();
    await phone.sync();
    await phone.tap(ball(1));
    await phone.tap(wide());
    await phone.sendNext();
    await phone.sendNext();
    const before = phone.state;
    const after = await phone.reload();
    expect(after.items).toEqual(before.items);
    expect(after.counts).toEqual(before.counts);
    expect(after.confirmedSeq).toBe(before.confirmedSeq);
    expect(stateFingerprint(after.local)).toBe(stateFingerprint(before.local));
    expect(after.nextN).toBe(before.nextN);
  });

  it('shows another account\'s balls as set aside, never sends them, never changes them, and gives them back to their owner', async () => {
    const phone = await started();
    await phone.sync();
    await phone.tap(ball(1));
    const rows = JSON.stringify(await storedItems(phone));
    const other = await loadQueue(phone.storage, { matchId: MATCH, userId: 'someone-else' }, 't');
    expect(other.ok && other.state.items.map((i) => [i.status, i.reason])).toEqual([
      ['SET_ASIDE', 'OTHER_ACCOUNT'],
      ['SET_ASIDE', 'OTHER_ACCOUNT'],
    ]);
    expect(other.ok && q.nextToSend(other.state)).toBeNull();
    expect(JSON.stringify(await storedItems(phone)), 'storage untouched').toBe(rows);
    const owner = await loadQueue(phone.storage, { matchId: MATCH, userId: USER }, 't');
    expect(owner.ok && owner.state.items.map((i) => i.status)).toEqual(['WAITING', 'WAITING']);
  });

  it('keeps a row it cannot read, as a set-aside item with the raw row, and carries on with the rest', async () => {
    const phone = await started();
    await phone.sync();
    await phone.tap(ball(1));
    phone.storage.putRaw(MATCH, 50, { n: 50, matchId: MATCH, surprise: true });
    phone.storage.putRaw(MATCH, 51, 'not even an object');
    const result = await loadQueue(phone.storage, { matchId: MATCH, userId: USER }, 't');
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.state.items).toHaveLength(4);
      const bad = result.state.items.filter((i) => i.reason === 'CORRUPTED');
      expect(bad).toHaveLength(2);
      expect(bad.map((b) => b.detail).join(' ')).toContain('surprise');
      expect(result.state.items.filter((i) => i.status === 'WAITING')).toHaveLength(2);
      expect(result.notes.join(' ')).toContain('could not be read');
      expect(result.state.nextN).toBe(52);
    }
    expect(await storedItems(phone), 'nothing was removed from storage').toHaveLength(4);
  });

  it('sets aside, not drops, a saved ball that no longer fits the saved log', async () => {
    const phone = await started();
    await phone.sync();
    await phone.tap(ball(1));
    const rows = (await storedItems(phone)) as { clientEventId: string; command: { delivery: { runs: number } } }[];
    phone.storage.putRaw(MATCH, 2, { ...rows[1], command: { type: 'SELECT_BATTER', batterId: lion(3) } });
    const result = await loadQueue(phone.storage, { matchId: MATCH, userId: USER }, 't');
    expect(result.ok && result.state.items.find((i) => i.n === 2)).toMatchObject({ status: 'SET_ASIDE', reason: 'CORRUPTED' });
    expect(result.ok && result.mutations.length).toBeGreaterThan(0);
  });

  it('asks for the engine state when there is none, and shows what is saved', async () => {
    const storage = new MemoryStorage();
    storage.putRaw(MATCH, 1, { n: 1 });
    const result = await loadQueue(storage, { matchId: MATCH, userId: USER }, 't');
    expect(result).toMatchObject({ ok: false, reason: 'NO_ENGINE_STATE' });
    expect(!result.ok && result.items).toHaveLength(1);
  });
});

describe('undo while offline', () => {
  it('queues an undo of a ball that is still on the phone, as an event of its own, and the server ends up with both', async () => {
    const phone = await started();
    await phone.tap(ball(4, { boundary: true }));
    await phone.tap(wide());
    const undo = await phone.undo('BALL');
    expect(undo.refused).toBeNull();
    expect(phone.state.items.map((i) => i.kind)).toEqual(['COMMAND', 'COMMAND', 'COMMAND', 'UNDO']);
    expect(phone.state.items[3]).toMatchObject({ label: 'Undo last ball', mode: 'BALL', expectedSeq: 3 });
    expect(phone.state.local.score.innings[0].runs, 'the wide is taken off, the four stays').toBe(4);
    await flush(phone);
    expect(phone.server.log.entries.map((e) => e.kind)).toEqual(['COMMAND', 'COMMAND', 'COMMAND', 'UNDO']);
    expect(stateFingerprint(phone.server.log)).toBe(stateFingerprint(phone.state.local));
  });

  it('undoes a ball the server already has the same way, and the counts add up', async () => {
    const phone = await started();
    await phone.tap(ball(2));
    await flush(phone);
    await phone.undo('COMMAND');
    expect(phone.state.items[0]).toMatchObject({ kind: 'UNDO', expectedSeq: 2 });
    await flush(phone);
    expect(phone.server.log.entries).toHaveLength(3);
    expect(phone.server.log.score.innings[0].runs).toBe(0);
  });
});

describe('copy unsent balls', () => {
  it('lists every ball the phone holds, waiting or set aside, readable, with the ids and the exact bodies', async () => {
    const phone = await started();
    await phone.tap(ball(1));
    await phone.tap(bowled());
    await phone.commit(q.setAsideFrom(phone.state, 3, 'SCORER_CHOICE', 't'));
    const { text, json } = exportText(phone.state);
    expect(text.split('\n')).toHaveLength(3);
    expect(text).toContain('Start innings | waiting');
    expect(text).toContain('wicket (bowled) | set aside (SCORER_CHOICE');
    const parsed = JSON.parse(json) as { clientEventId: string; command: { type: string }; status: string }[];
    expect(parsed.map((p) => p.command.type)).toEqual(['START_INNINGS', 'DELIVER', 'DELIVER']);
    expect(parsed.map((p) => p.clientEventId)).toEqual(phone.state.items.map((i) => i.clientEventId));
    expect(lion(1) && tiger(1)).toBeTruthy();
  });
});
