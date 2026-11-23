import type { QueueStorage } from './storage';
import type { EngineStateRecord, JournalEntry, Mutation, QueueItem } from './types';

/**
 * What every storage adapter must do, as plain code with no test framework, so the same checks run against the in-memory adapter in Vitest and against real
 * IndexedDB in a real Chrome (`e2e/idb-contract.mjs`, no `fake-indexeddb`). It returns the list of problems; an empty list is a pass.
 */
const item = (matchId: string, n: number, label = `item ${n}`): QueueItem => ({
  matchId,
  userId: 'u1',
  n,
  kind: 'UNDO',
  clientEventId: `id-${matchId}-${n}`,
  expectedSeq: n - 1,
  command: null,
  mode: 'BALL',
  label,
  tappedAt: 't',
  status: 'WAITING',
  reason: null,
  detail: null,
  attempts: 0,
});

const line = (matchId: string, event: JournalEntry['event'], n: number | null): Mutation => ({
  kind: 'journal',
  entry: { matchId, at: 't', event, n, clientEventId: null, label: null, detail: null },
});

const record = (matchId: string, seq: number): EngineStateRecord => ({
  matchId,
  userId: 'u1',
  setup: { overs: 1, rules: { ballsPerOver: 6, playersPerSide: 2, maxOversPerBowler: null, widePenalty: 1, noBallPenalty: 1, freeHitEnabled: true, lastManStands: false }, teamA: 'a', teamB: 'b', battingFirst: 'a', xiA: ['1', '2'], xiB: ['3', '4'] },
  entries: [],
  seq,
  engineVersion: 1,
  stateFingerprint: '0000000000000000',
  savedAt: 't',
});

const entry = (seq: number) => ({ seq, kind: 'UNDO' as const, voids: [], clientEventId: `e${seq}` });

export async function runStorageContract(make: () => Promise<QueueStorage>): Promise<string[]> {
  const problems: string[] = [];
  const expect = (ok: boolean, what: string) => {
    if (!ok) {
      problems.push(what);
    }
  };
  const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

  {
    const s = await make();
    expect((await s.items('m')).length === 0 && (await s.journal('m')).length === 0 && (await s.engineState('m')) === undefined && (await s.tail('m')).length === 0, 'a new store is empty');
  }

  {
    const s = await make();
    await s.commit([{ kind: 'putItem', item: item('m', 2) }, { kind: 'putItem', item: item('m', 1) }, line('m', 'enqueued', 1), line('m', 'enqueued', 2), line('m', 'confirmed', 1)]);
    const rows = await s.items('m');
    expect(rows.map((r) => r.n).join() === '1,2', 'items come back in order of n, whatever order they were written in');
    expect(same(rows[0].row, item('m', 1)), 'an item comes back exactly as it was stored');
    expect((await s.journal('m')).map((j) => j.event).join() === 'enqueued,enqueued,confirmed', 'the journal keeps the order lines were written in');
    expect(same((await s.journal('m'))[0], line('m', 'enqueued', 1).kind === 'journal' ? (line('m', 'enqueued', 1) as { entry: JournalEntry }).entry : null), 'a journal line comes back without any storage bookkeeping');
  }

  {
    const s = await make();
    await s.commit([{ kind: 'putItem', item: item('m', 1, 'first') }]);
    await s.commit([{ kind: 'putItem', item: item('m', 1, 'changed') }]);
    expect((await s.items('m')).length === 1 && (await s.items('m'))[0].row !== undefined && ((await s.items('m'))[0].row as QueueItem).label === 'changed', 'putting the same key again replaces the item');
    await s.commit([{ kind: 'deleteItem', matchId: 'm', n: 1 }]);
    expect((await s.items('m')).length === 0, 'deleteItem removes it');
    await s.commit([{ kind: 'deleteItem', matchId: 'm', n: 99 }]);
    expect(true, 'deleting what is not there is harmless');
  }

  {
    const s = await make();
    await s.commit([{ kind: 'putItem', item: item('m', 1) }, line('m', 'enqueued', 1)]);
    let refused = false;
    try {
      await s.commit([{ kind: 'putItem', item: item('m', 2) }, line('m', 'enqueued', 2), { kind: 'putItem', item: { ...item('m', 3), n: Number.NaN } }]);
    } catch {
      refused = true;
    }
    expect(refused, 'a commit holding something that cannot be stored is refused');
    expect((await s.items('m')).map((r) => r.n).join() === '1', 'a refused commit wrote nothing, not even the valid items before the bad one');
    expect((await s.journal('m')).length === 1, 'a refused commit wrote no journal line either');
  }

  {
    const s = await make();
    await s.commit([{ kind: 'putItem', item: item('a', 1) }, line('a', 'enqueued', 1), { kind: 'putEngineState', record: record('a', 0) }, { kind: 'appendConfirmed', matchId: 'a', entry: entry(1) }]);
    expect((await s.items('b')).length === 0 && (await s.journal('b')).length === 0 && (await s.engineState('b')) === undefined && (await s.tail('b')).length === 0, 'one match never sees another match\'s rows');
  }

  {
    const s = await make();
    await s.commit([{ kind: 'putEngineState', record: record('m', 0) }, { kind: 'appendConfirmed', matchId: 'm', entry: entry(1) }, { kind: 'appendConfirmed', matchId: 'm', entry: entry(2) }, { kind: 'appendConfirmed', matchId: 'm', entry: entry(3) }]);
    expect((await s.tail('m')).map((e) => e.seq).join() === '1,2,3', 'confirmed entries come back in seq order');
    await s.commit([{ kind: 'putEngineState', record: record('m', 2) }]);
    expect((await s.engineState('m'))?.seq === 2, 'the engine state is replaced');
    expect((await s.tail('m')).map((e) => e.seq).join() === '3', 'saving the engine state forgets the confirmed entries it includes, and only those');
  }

  {
    const s = await make();
    const stored = item('m', 1);
    await s.commit([{ kind: 'putItem', item: stored }]);
    stored.label = 'edited afterwards';
    const back = (await s.items('m'))[0].row as QueueItem;
    expect(back.label === 'item 1', 'what was stored is a copy: editing the original afterwards changes nothing');
    back.label = 'edited too';
    expect(((await s.items('m'))[0].row as QueueItem).label === 'item 1', 'and what was read is a copy too');
  }

  {
    const s = await make();
    await s.commit([]);
    expect(true, 'an empty commit is fine');
  }
  return problems;
}
