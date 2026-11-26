import { describe, expect, it } from 'vitest';
import { logFrom, stateFingerprint } from '../engine';
import { Rng } from '../engine/tsVectors';
import { countsFrom } from './load';
import * as q from './queue';
import { FakeServer, MATCH, Phone, plausible, SETUP } from './testHarness';
import { isInChain } from './types';

/**
 * Nothing is lost silently, as a test (plan sections 2.5 and 4). Random sequences of tap, undo, send (with a lost network, a lost answer), another scorer scoring,
 * the screen opening, a conflict being settled either way, the 24 hour lock, the scorer being removed, "send again", a discard and a page reload run against a fake
 * server that keeps the real server's rules. After EVERY step: enqueued = confirmed + discarded + held; the storage holds exactly what the state holds; the chain
 * is contiguous; ids are unique and in order; the server has no id twice; and every ball ever taken is on the server, still held, or was discarded by name.
 */
const SEEDS = 30;
const OPS = 120;

interface Tally {
  steps: number;
  taps: number;
  conflicts: number;
  rebases: number;
  keepTheirs: number;
  replays: number;
  locks: number;
  forbids: number;
  reloads: number;
  discards: number;
  sendAgains: number;
  confirmed: number;
}

async function check(phone: Phone, seed: number, opIndex: number, discardedIds: Set<string>, deep: boolean, trail: string[]): Promise<void> {
  const s = phone.state;
  const why = `seed ${seed}, op ${opIndex} (after ${trail.slice(-6).join(', ')}; blocked ${s.blocked?.kind ?? 'no'})`;
  expect(s.counts.enqueued, `${why}: enqueued = confirmed + discarded + held`).toBe(s.counts.confirmed + s.counts.discarded + s.items.length);

  const journal = await phone.storage.journal(MATCH);
  expect(countsFrom(journal), `${why}: the journal says the same`).toEqual(s.counts);

  const rows = (await phone.storage.items(MATCH)).map((r) => r.row as { status: string });
  const held = s.items.map((i) => ({ ...i }));
  expect(rows.map((r) => (r.status === 'IN_FLIGHT' ? { ...r, status: 'WAITING' } : r)), `${why}: storage holds exactly the items the state holds`).toEqual(
    held.map((i) => ({ ...i, status: i.status === 'IN_FLIGHT' ? 'WAITING' : i.status })),
  );

  const ids = s.items.map((i) => i.clientEventId);
  expect(new Set(ids).size, `${why}: ids are unique`).toBe(ids.length);
  expect(s.items.every((item, i) => i === 0 || s.items[i - 1].n < item.n), `${why}: items are in order`).toBe(true);
  expect(new Set(phone.server.applied).size, `${why}: the server never applied an id twice`).toBe(phone.server.applied.length);

  if (s.blocked === null) {
    const chain = q.chainOf(s.items);
    chain.forEach((item, i) => expect(item.expectedSeq, `${why}: chain item ${i} expects the seq after the one before it`).toBe(s.confirmedSeq + i));
    expect(s.local.entries.length, `${why}: the screen shows the confirmed log plus the chain`).toBe(s.confirmedSeq + chain.length);
  }
  expect(s.confirmedEntries.length, `${why}: the confirmed log has a seq for every entry`).toBe(s.confirmedSeq);
  s.confirmedEntries.forEach((e, i) => {
    expect(e.seq).toBe(i + 1);
    expect(phone.server.log.entries[i].clientEventId, `${why}: the confirmed log is a prefix of the server's`).toBe(e.clientEventId);
  });

  const serverIds = new Set(phone.server.applied);
  for (const id of phone.taken) {
    expect(serverIds.has(id) || ids.includes(id) || discardedIds.has(id), `${why}: ball ${id} was lost`).toBe(true);
  }
  if (deep && s.blocked === null) {
    const rebuilt = q.chainLog(logFrom(s.setup, s.confirmedEntries), q.chainOf(s.items));
    expect(rebuilt.refusal, `${why}: the chain still applies`).toBeNull();
    expect(stateFingerprint(rebuilt.log), `${why}: the screen's state is the fold of what it holds`).toBe(stateFingerprint(s.local));
  }
}

async function run(seed: number, tally: Tally): Promise<void> {
  const r = new Rng(seed * 104729 + 7);
  const phone = new Phone(new FakeServer(SETUP));
  await phone.open();
  const discardedIds = new Set<string>();
  const trail: string[] = [];
  for (let op = 0; op < OPS; op++) {
    tally.steps++;
    const p = r.int(100);
    if (p < 30) {
      const step = r.int(100) < 12 ? await phone.undo(r.bool() ? 'BALL' : 'COMMAND') : await phone.tap(plausible(r, phone.state.local));
      trail.push(step.refused === null ? 'tap' : `refused/${step.refused.code}`);
      if (step.refused === null) {
        tally.taps++;
      }
    } else if (p < 62) {
      const fault = r.int(100) < 70 ? 'none' : r.bool() ? 'network' : 'lostAnswer';
      const outcome = await phone.sendNext(fault);
      trail.push(`send/${fault}=${outcome}`);
      tally.conflicts += outcome === 'conflict' ? 1 : 0;
      tally.replays += outcome === 'replayed' ? 1 : 0;
      tally.locks += outcome === 'locked' ? 1 : 0;
      tally.forbids += outcome === 'forbidden' ? 1 : 0;
      tally.confirmed += outcome === 'confirmed' ? 1 : 0;
    } else if (p < 69) {
      phone.server.foreign(plausible(r, phone.server.log));
      trail.push('foreign');
    } else if (p < 74) {
      await phone.reload();
      trail.push('reload');
      tally.reloads++;
    } else if (p < 78) {
      await phone.sync();
      trail.push('sync');
    } else if (p < 84) {
      if (phone.state.blocked?.kind === 'CONFLICT') {
        if (r.int(100) < 65) {
          await phone.commit(q.rebase(phone.state, phone.now()));
          trail.push('rebase');
          tally.rebases++;
        } else {
          await phone.commit(q.keepTheirs(phone.state, phone.now()));
          trail.push('keepTheirs');
          tally.keepTheirs++;
        }
      }
    } else if (p < 87) {
      phone.server.locked = !phone.server.locked && r.int(3) === 0;
      trail.push(`locked=${phone.server.locked}`);
    } else if (p < 89) {
      phone.server.forbidden = !phone.server.forbidden && r.int(4) === 0;
      trail.push(`forbidden=${phone.server.forbidden}`);
    } else if (p < 93) {
      const asideBefore = phone.state.items.filter((i) => i.status === 'SET_ASIDE').length;
      await phone.commit(q.sendAgain(phone.state, phone.now()));
      trail.push('sendAgain');
      tally.sendAgains += phone.state.items.filter((i) => i.status === 'SET_ASIDE').length < asideBefore ? 1 : 0;
    } else if (p < 96) {
      const aside = phone.state.items.find((i) => i.status === 'SET_ASIDE');
      if (aside !== undefined) {
        discardedIds.add(aside.clientEventId);
        await phone.commit(q.discard(phone.state, aside.n, phone.now()));
        trail.push('discard');
        tally.discards++;
      }
    } else {
      // Only the sender sends; here nothing else to do: let a quiet moment pass.
    }
    await check(phone, seed, op, discardedIds, op % 6 === 0, trail);
  }

  // Settle: everything allowed again, then carry every held ball through to the server where the rules let it.
  phone.server.locked = false;
  phone.server.forbidden = false;
  for (let round = 0; round < 6; round++) {
    await phone.reload();
    await phone.sync();
    if (phone.state.blocked?.kind === 'CONFLICT') {
      await phone.commit(q.rebase(phone.state, phone.now()));
    }
    await phone.commit(q.sendAgain(phone.state, phone.now()));
    for (let i = 0; i < 1000 && q.nextToSend(phone.state) !== null; i++) {
      await phone.sendNext();
    }
    await check(phone, seed, OPS + round, discardedIds, true, ['settle']);
  }
  expect(q.chainOf(phone.state.items), `seed ${seed}: the chain is empty at the end`).toEqual([]);
  expect(phone.state.items.every((i) => !isInChain(i)), `seed ${seed}: what is left is set aside, with a reason`).toBe(true);
  expect(phone.state.items.every((i) => i.reason !== null)).toBe(true);
  expect(stateFingerprint(phone.state.local), `seed ${seed}: the phone ends where the server is`).toBe(stateFingerprint(phone.server.log));
}

describe('nothing is lost silently', () => {
  it(
    'holds after every step of many random sequences, including conflicts, locks, lost answers and reloads',
    async () => {
      const tally: Tally = { steps: 0, taps: 0, conflicts: 0, rebases: 0, keepTheirs: 0, replays: 0, locks: 0, forbids: 0, reloads: 0, discards: 0, sendAgains: 0, confirmed: 0 };
      for (let seed = 1; seed <= SEEDS; seed++) {
        await run(seed, tally);
      }
      const summary = JSON.stringify(tally);
      expect(tally.steps, summary).toBe(SEEDS * OPS);
      // The run must really have exercised the hard paths, or passing means little.
      expect(tally.taps, summary).toBeGreaterThan(600);
      expect(tally.confirmed, summary).toBeGreaterThan(150);
      expect(tally.conflicts, summary).toBeGreaterThan(25);
      expect(tally.rebases, summary).toBeGreaterThan(8);
      expect(tally.keepTheirs, summary).toBeGreaterThan(2);
      expect(tally.replays, summary).toBeGreaterThan(10);
      expect(tally.locks, summary).toBeGreaterThan(10);
      expect(tally.forbids, summary).toBeGreaterThan(10);
      expect(tally.reloads, summary).toBeGreaterThan(100);
      expect(tally.discards, summary).toBeGreaterThan(20);
      expect(tally.sendAgains, summary).toBeGreaterThan(1);
    },
    120_000,
  );
});
