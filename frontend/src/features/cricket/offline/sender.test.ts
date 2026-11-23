import { describe, expect, it } from 'vitest';
import { stateFingerprint, toBody, type ScoreCommand } from '../engine';
import { Rng } from '../engine/tsVectors';
import * as q from './queue';
import { drain, PACE_MS, type SenderApi, type SenderDeps } from './sender';
import { SendError, backoffMs, classifyHttp, retryAfterMs } from './sendErrors';
import { FakeServer, MATCH, Phone, plausible, SETUP, START, type Answer } from './testHarness';
import type { QueueItem } from './types';

/**
 * The sender against the fake server (which keeps the real server's rules). Every answer the plan lists in section 3.2 is checked for what the queue did with it,
 * and a chaos run injects lost answers, dropped connections and other scorers while the sender drains.
 */
const wide = (): ScoreCommand => ({ type: 'DELIVER', delivery: { kind: 'WIDE', runs: 0, runsType: null, boundary: false, dismissal: null } });

/** The Java route's answer for the fake server's log (own events carry their client event id, the others do not). */
function engineStateJson(server: FakeServer): unknown {
  return {
    matchId: MATCH,
    engineVersion: 1,
    seq: server.log.entries.length,
    stateFingerprint: stateFingerprint(server.log),
    ready: true,
    setup: SETUP,
    entries: server.log.entries.map((e) => ({
      seq: e.seq,
      kind: e.kind,
      command: e.kind === 'COMMAND' ? toBody(e.command) : null,
      voids: e.kind === 'UNDO' ? e.voids : [],
      clientEventId: e.clientEventId !== null && e.clientEventId.startsWith('id-') ? e.clientEventId : null,
      waivers: e.kind === 'COMMAND' ? e.waivers : [],
    })),
  };
}

interface Script {
  /** Called with each item before it is sent; may throw a SendError or return 'LOSE_ANSWER' / 'DROP' to simulate faults. */
  before?: (item: QueueItem, sent: number) => 'LOSE_ANSWER' | 'DROP' | SendError | null;
  engineState?: () => unknown;
}

function harness(phone: Phone, script: Script = {}, opts: { lease?: () => boolean; refresh?: () => Promise<boolean> } = {}) {
  const log = { sent: 0, sleeps: [] as number[], refreshes: 0 };
  const api: SenderApi = {
    async send(item) {
      const fault = script.before?.(item, log.sent) ?? null;
      log.sent++;
      if (fault instanceof SendError) {
        throw fault;
      }
      if (fault === 'DROP') {
        throw new SendError({ kind: 'NETWORK' });
      }
      const answer: Answer = phone.server.receive(item);
      if (fault === 'LOSE_ANSWER') {
        throw new SendError({ kind: 'NETWORK' });
      }
      switch (answer.status) {
        case 200:
          return { seq: answer.seq, replayed: answer.replayed };
        case 409:
          throw new SendError(answer.kind === 'CONFLICT' ? { kind: 'CONFLICT' } : { kind: 'LOCKED', message: 'locked', editableUntil: answer.editableUntil });
        case 422:
          throw new SendError({ kind: 'REJECTED', violation: answer.violation, message: answer.message });
        case 403:
          throw new SendError({ kind: 'FORBIDDEN' });
      }
    },
    async engineState() {
      return script.engineState ? script.engineState() : engineStateJson(phone.server);
    },
  };
  const deps: SenderDeps = {
    getState: () => phone.state,
    commit: async (step) => {
      await phone.commit(step);
    },
    api,
    now: () => phone.now(),
    hasLease: opts.lease ?? (() => true),
    refreshAuth: opts.refresh ?? (async () => (log.refreshes++, true)),
    sleep: async (ms) => {
      log.sleeps.push(ms);
    },
  };
  return { deps, log };
}

/** A phone holding exactly `items` queued items: the start of the innings and then balls and choices the engine accepts, whatever the over or the wickets need next. */
async function phoneWith(items: number, seed = items * 31 + 7): Promise<Phone> {
  const phone = new Phone(new FakeServer(SETUP));
  await phone.open();
  await phone.tap(START);
  const r = new Rng(seed);
  for (let tries = 0; phone.state.items.length < items && tries < items * 20; tries++) {
    await phone.tap(plausible(r, phone.state.local));
  }
  expect(phone.state.items, 'the helper could not queue that many items').toHaveLength(items);
  return phone;
}

describe('draining the queue', () => {
  it('sends everything one at a time in order, at about five a second, and stops when nothing waits', async () => {
    const phone = await phoneWith(20);
    const ids = phone.state.items.map((i) => i.clientEventId);
    const { deps, log } = harness(phone);
    expect(await drain(deps)).toEqual({ reason: 'IDLE' });
    expect(phone.state.items).toEqual([]);
    expect(phone.server.applied).toEqual(ids);
    expect(log.sleeps).toEqual(Array(20).fill(PACE_MS));
    expect(1000 / PACE_MS).toBeLessThanOrEqual(5);
    expect(phone.state.counts).toEqual({ enqueued: 20, confirmed: 20, discarded: 0 });
  });

  it('keeps the item and its id when the network drops, reports a retry for the caller\'s backoff, and carries on from the same item', async () => {
    const phone = await phoneWith(6);
    const ids = phone.state.items.map((i) => i.clientEventId);
    const { deps } = harness(phone, { before: (_i, sent) => (sent === 3 ? 'DROP' : null) });
    expect(await drain(deps)).toEqual({ reason: 'RETRY', afterMs: null });
    expect(phone.state.items).toHaveLength(3);
    expect(phone.state.items[0]).toMatchObject({ status: 'WAITING', attempts: 1, clientEventId: ids[3] });
    expect(await drain(deps)).toEqual({ reason: 'IDLE' });
    expect(phone.server.applied).toEqual(ids);
  });

  it('treats a lost answer as a replay: the server has it, the phone is told so, and nothing is applied twice', async () => {
    const phone = await phoneWith(5);
    const ids = phone.state.items.map((i) => i.clientEventId);
    const { deps } = harness(phone, { before: (_i, sent) => (sent === 2 ? 'LOSE_ANSWER' : null) });
    expect((await drain(deps)).reason).toBe('RETRY');
    expect(phone.server.log.entries).toHaveLength(3);
    expect(await drain(deps)).toEqual({ reason: 'IDLE' });
    expect(phone.server.applied).toEqual(ids);
    expect((await phone.storage.journal(MATCH)).filter((j) => j.event === 'replayed')).toHaveLength(1);
  });

  it('refreshes the sign-in once on a 401 and carries on; if that fails the queue waits for a sign-in and keeps everything', async () => {
    const phone = await phoneWith(4);
    let failures = 1;
    const a = harness(phone, { before: () => (failures-- > 0 ? new SendError({ kind: 'AUTH' }) : null) });
    expect(await drain(a.deps)).toEqual({ reason: 'IDLE' });
    expect(a.log.refreshes).toBe(1);
    expect(phone.server.log.entries).toHaveLength(4);

    const other = await phoneWith(4);
    const b = harness(other, { before: () => new SendError({ kind: 'AUTH' }) }, { refresh: async () => false });
    expect(await drain(b.deps)).toEqual({ reason: 'NEEDS_SIGN_IN' });
    expect(other.state.items).toHaveLength(4);
    expect(other.state.items[0].status).toBe('WAITING');
    expect(other.server.log.entries).toHaveLength(0);
  });

  it('does not refresh forever: a second 401 right after a refresh asks for a sign-in', async () => {
    const phone = await phoneWith(3);
    const { deps, log } = harness(phone, { before: () => new SendError({ kind: 'AUTH' }) });
    expect(await drain(deps)).toEqual({ reason: 'NEEDS_SIGN_IN' });
    expect(log.refreshes).toBe(1);
  });

  it('honours a 429 with the time the server asked for', async () => {
    const phone = await phoneWith(3);
    const { deps } = harness(phone, { before: (_i, sent) => (sent === 1 ? new SendError({ kind: 'RATE_LIMITED', retryAfterMs: 12_000 }) : null) });
    expect(await drain(deps)).toEqual({ reason: 'RETRY', afterMs: 12_000 });
    expect(phone.state.items).toHaveLength(2);
  });

  it('stops at once when the tab no longer holds the lease, with the balls it did send confirmed and the rest waiting', async () => {
    const phone = await phoneWith(8);
    let sends = 0;
    const { deps } = harness(phone, { before: () => (sends++, null) }, { lease: () => sends < 3 });
    expect(await drain(deps)).toEqual({ reason: 'LEASE_LOST' });
    expect(phone.server.log.entries).toHaveLength(3);
    expect(phone.state.items).toHaveLength(5);
  });

  it('stops with nothing sent when the in-flight mark cannot be saved: a ball is never sent that the phone could not remember sending', async () => {
    const phone = await phoneWith(3);
    const { deps } = harness(phone);
    phone.storage.failNext();
    const stop = await drain(deps);
    expect(stop.reason).toBe('STORAGE_FAILED');
    expect(phone.server.received).toBe(0);
    expect(phone.state.items.every((i) => i.status === 'WAITING')).toBe(true);
  });
});

describe('the answers that need a decision', () => {
  it('reads the server\'s log after a conflict, holds the balls, and after a rebase the balls go in with their ids', async () => {
    const phone = await phoneWith(6);
    const { deps } = harness(phone);
    await drain(deps); // the whole queue first, to have a confirmed log
    const r = new Rng(99);
    for (let i = 0; i < 4; i++) {
      await phone.tap(plausible(r, phone.state.local));
    }
    expect(phone.state.items).toHaveLength(4);
    const mine = phone.state.items.map((i) => i.clientEventId);
    phone.server.foreign(wide());
    phone.server.foreign(wide());
    expect(await drain(deps)).toEqual({ reason: 'BLOCKED', blocked: 'CONFLICT' });
    expect(phone.state.blocked).toMatchObject({ kind: 'CONFLICT' });
    expect(phone.state.items).toHaveLength(4);
    expect(phone.server.log.entries).toHaveLength(8);
    expect(q.previewRebase(phone.state)).toMatchObject({ total: 4, applicable: 4 });
    await phone.commit(q.rebase(phone.state, phone.now()));
    expect(await drain(deps)).toEqual({ reason: 'IDLE' });
    expect(phone.server.applied.slice(-4)).toEqual(mine);
    expect(new Set(phone.server.applied).size).toBe(phone.server.applied.length);
  });

  it('is not a conflict when the "other" entries are the phone\'s own (a lost answer, then somebody else scored): its balls are recognised and the rest carries on', async () => {
    const phone = await phoneWith(4);
    const { deps } = harness(phone, { before: (_i, sent) => (sent === 0 ? 'LOSE_ANSWER' : null) });
    await drain(deps); // item 1 applied, answer lost
    expect(phone.server.log.entries).toHaveLength(1);
    expect(await drain(deps)).toEqual({ reason: 'IDLE' });
    expect(phone.server.log.entries).toHaveLength(4);
  });

  it('sets the rest aside as locked when the window closes, and sends them again with the same ids after an unlock', async () => {
    const phone = await phoneWith(6);
    const ids = phone.state.items.map((i) => i.clientEventId);
    const { deps } = harness(phone, { before: (_i, sent) => (sent === 2 ? (phone.server.locked = true, null) : null) });
    expect(await drain(deps)).toEqual({ reason: 'BLOCKED', blocked: 'LOCKED' });
    expect(phone.server.log.entries).toHaveLength(2);
    expect(phone.state.items.map((i) => i.reason)).toEqual(Array(4).fill('LOCKED'));
    phone.server.locked = false;
    await phone.commit(q.sendAgain(phone.state, phone.now()));
    expect(await drain(deps)).toEqual({ reason: 'IDLE' });
    expect(phone.server.applied).toEqual(ids);
  });

  it('needs no decision for an unlock by an administrator: it takes a seq but changes no score, so the balls just get their seqs again and go in with their ids', async () => {
    const phone = await phoneWith(5);
    const ids = phone.state.items.map((i) => i.clientEventId);
    const { deps } = harness(phone, { before: (_i, sent) => (sent === 2 ? (phone.server.locked = true, null) : null) });
    expect(await drain(deps)).toEqual({ reason: 'BLOCKED', blocked: 'LOCKED' });
    phone.server.unlock();
    await phone.commit(q.sendAgain(phone.state, phone.now()));
    expect(await drain(deps)).toEqual({ reason: 'IDLE' });
    expect(phone.state.blocked).toBeNull();
    expect(phone.server.applied.filter((id) => !id.startsWith('unlock'))).toEqual(ids);
    expect(phone.server.log.entries.map((e) => e.seq)).toEqual(Array.from({ length: 6 }, (_, i) => i + 1));
  });

  it('sets everything aside, saved, when the scorer is no longer allowed', async () => {
    const phone = await phoneWith(5);
    phone.server.forbidden = true;
    const { deps } = harness(phone);
    expect(await drain(deps)).toEqual({ reason: 'BLOCKED', blocked: 'FORBIDDEN' });
    expect(phone.state.items.map((i) => i.reason)).toEqual(Array(5).fill('FORBIDDEN'));
    expect(await phone.storage.items(MATCH)).toHaveLength(5);
  });

  it('stops on a refusal of a ball the phone accepted, turns offline scoring off and keeps every ball', async () => {
    const phone = await phoneWith(4);
    const { deps } = harness(phone, { before: (_i, sent) => (sent === 1 ? new SendError({ kind: 'REJECTED', violation: 'INNINGS_COMPLETE', message: 'The innings is complete' }) : null) });
    expect(await drain(deps)).toEqual({ reason: 'BLOCKED', blocked: 'REJECTED' });
    expect(phone.state.offline).toEqual({ on: false, reason: 'ENGINE_MISMATCH' });
    expect(phone.state.items).toHaveLength(3);
    expect(phone.state.items[0].detail).toContain('INNINGS_COMPLETE');
  });

  it('treats a bad request or a reused id as a bug: stop, keep everything, show the code', async () => {
    const phone = await phoneWith(3);
    const { deps } = harness(phone, { before: () => new SendError({ kind: 'BAD_REQUEST', code: 'CLIENT_EVENT_REUSED', message: 'That client event id was already used' }) });
    expect(await drain(deps)).toEqual({ reason: 'BLOCKED', blocked: 'REJECTED' });
    expect(phone.state.blocked).toMatchObject({ kind: 'REJECTED', violation: 'CLIENT_EVENT_REUSED' });
    expect(phone.state.items).toHaveLength(3);
  });

  it('does not trust a server log it cannot read: offline scoring goes off and nothing waiting is touched', async () => {
    const phone = await phoneWith(3);
    expect(phone.server.foreign(START)).toBe(true);   // somebody else started the innings first: the phone's first item meets a conflict
    const { deps } = harness(phone, { engineState: () => ({ nonsense: true }) });
    expect(await drain(deps)).toEqual({ reason: 'RETRY', afterMs: null });
    expect(phone.state.offline).toEqual({ on: false, reason: 'LOG_UNREADABLE' });
    expect(phone.state.items).toHaveLength(3);
    expect(phone.state.items[0].status).toBe('WAITING');
  });

  it('switches offline scoring off, keeping the queue, when the server\'s fingerprint is not the phone\'s', async () => {
    const phone = await phoneWith(3);
    expect(phone.server.foreign(START)).toBe(true);
    const { deps } = harness(phone, { engineState: () => ({ ...(engineStateJson(phone.server) as object), stateFingerprint: '0000000000000000' }) });
    await drain(deps);
    expect(phone.state.offline).toEqual({ on: false, reason: 'FINGERPRINT_MISMATCH' });
    expect(phone.state.items.length).toBeGreaterThan(0);
  });
});

describe('what the answers mean (the real adapter uses this)', () => {
  const at = Date.parse('2026-11-23T10:00:00Z');
  it('reads every status the plan lists', () => {
    expect(classifyHttp(401, {}, null, at)).toEqual({ kind: 'AUTH' });
    expect(classifyHttp(403, {}, null, at)).toEqual({ kind: 'FORBIDDEN' });
    expect(classifyHttp(404, {}, null, at)).toEqual({ kind: 'FORBIDDEN' });
    expect(classifyHttp(502, null, null, at)).toEqual({ kind: 'NETWORK' });
    expect(classifyHttp(503, undefined, null, at)).toEqual({ kind: 'NETWORK' });
    expect(classifyHttp(409, { code: 'SCORE_CONFLICT' }, null, at)).toEqual({ kind: 'CONFLICT' });
    expect(classifyHttp(409, { code: 'MATCH_LOCKED', message: 'closed' }, null, at)).toEqual({ kind: 'LOCKED', message: 'closed', editableUntil: null });
    expect(classifyHttp(422, { code: 'SCORING_REJECTED', message: 'no', details: { violation: 'NEEDS_NEW_BATTER' } }, null, at)).toEqual({ kind: 'REJECTED', violation: 'NEEDS_NEW_BATTER', message: 'no' });
    expect(classifyHttp(409, { code: 'CLIENT_EVENT_REUSED', message: 'x' }, null, at)).toEqual({ kind: 'BAD_REQUEST', code: 'CLIENT_EVENT_REUSED', message: 'x' });
    expect(classifyHttp(400, { code: 'VALIDATION_ERROR' }, null, at)).toMatchObject({ kind: 'BAD_REQUEST', code: 'VALIDATION_ERROR' });
    expect(classifyHttp(418, 'text', null, at)).toMatchObject({ kind: 'BAD_REQUEST', code: 'HTTP_418' });
  });

  it('reads Retry-After as seconds or as a date, within sensible limits', () => {
    expect(retryAfterMs('7', at)).toBe(7000);
    expect(retryAfterMs('0', at)).toBe(1000);
    expect(retryAfterMs('99999', at)).toBe(300_000);
    expect(retryAfterMs('Mon, 23 Nov 2026 10:00:20 GMT', at)).toBe(20_000);
    expect(retryAfterMs('soon', at)).toBe(5000);
    expect(retryAfterMs(null, at)).toBe(5000);
    expect(classifyHttp(429, {}, '3', at)).toEqual({ kind: 'RATE_LIMITED', retryAfterMs: 3000 });
  });

  it('backs off 2, 4, 8, 16, then 30 seconds', () => {
    expect([1, 2, 3, 4, 5, 6, 20].map(backoffMs)).toEqual([2000, 4000, 8000, 16_000, 30_000, 30_000, 30_000]);
  });
});

describe('chaos: dropped connections, lost answers and other scorers while the sender drains', () => {
  it('ends with every ball on the server once, or set aside, whatever happened on the way', async () => {
    let drained = 0;
    for (let seed = 1; seed <= 25; seed++) {
      const r = new Rng(seed * 7717 + 3);
      const phone = new Phone(new FakeServer(SETUP));
      await phone.open();
      await phone.tap(START);
      const { deps } = harness(phone, {
        before: () => {
          const p = r.int(100);
          if (p < 12) {
            return 'DROP';
          }
          if (p < 22) {
            return 'LOSE_ANSWER';
          }
          if (p < 26) {
            phone.server.foreign(plausible(r, phone.server.log));
          }
          return null;
        },
      });
      for (let round = 0; round < 60; round++) {
        if (r.int(100) < 60) {
          await phone.tap(plausible(r, phone.state.local));
        }
        if (r.int(100) < 40) {
          const stop = await drain(deps);
          drained++;
          if (stop.reason === 'BLOCKED' && phone.state.blocked?.kind === 'CONFLICT') {
            await phone.commit(r.int(100) < 70 ? q.rebase(phone.state, phone.now()) : q.keepTheirs(phone.state, phone.now()));
          }
        }
      }
      // Settle: a quiet network, then everything that can go in goes in.
      const calm = harness(phone);
      for (let i = 0; i < 20; i++) {
        const stop = await drain(calm.deps);
        if (stop.reason === 'BLOCKED' && phone.state.blocked?.kind === 'CONFLICT') {
          await phone.commit(q.rebase(phone.state, phone.now()));
        }
        if (stop.reason === 'IDLE') {
          break;
        }
      }
      expect(new Set(phone.server.applied).size, `seed ${seed}: no id twice`).toBe(phone.server.applied.length);
      expect(q.chainOf(phone.state.items), `seed ${seed}: nothing is still waiting`).toEqual([]);
      for (const id of phone.taken) {
        const onServer = phone.server.applied.includes(id);
        const kept = phone.state.items.some((i) => i.clientEventId === id);
        expect(onServer || kept, `seed ${seed}: ball ${id} was lost`).toBe(true);
      }
      expect(phone.state.counts.enqueued).toBe(phone.state.counts.confirmed + phone.state.counts.discarded + phone.state.items.length);
      expect(stateFingerprint(phone.state.local), `seed ${seed}: the phone ends where the server is`).toBe(stateFingerprint(phone.server.log));
    }
    expect(drained).toBeGreaterThan(300);
  }, 60_000);
});
