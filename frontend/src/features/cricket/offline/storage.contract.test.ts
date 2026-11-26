import { describe, expect, it } from 'vitest';
import { MemoryStorage, StorageError } from './storage';
import { runStorageContract } from './storageContract';

/**
 * The storage contract against the in-memory adapter. The same `runStorageContract` runs against real IndexedDB in Chrome through `e2e/idb-contract.mjs`
 * (jsdom has no IndexedDB, and `fake-indexeddb` would be a new dependency, D12), so both adapters are held to one definition of "all or nothing".
 */
describe('the storage contract, in memory', () => {
  it('is met', async () => {
    expect(await runStorageContract(async () => new MemoryStorage())).toEqual([]);
  });

  it('fails a commit without changing anything when the disk is full, and works again afterwards', async () => {
    const s = new MemoryStorage();
    s.failNext();
    await expect(s.commit([{ kind: 'journal', entry: { matchId: 'm', at: 't', event: 'enqueued', n: 1, clientEventId: null, label: null, detail: null } }])).rejects.toBeInstanceOf(StorageError);
    expect(await s.journal('m')).toEqual([]);
    await s.commit([{ kind: 'journal', entry: { matchId: 'm', at: 't', event: 'enqueued', n: 1, clientEventId: null, label: null, detail: null } }]);
    expect(await s.journal('m')).toHaveLength(1);
  });
});
