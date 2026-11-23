import type { LogEntry } from '../engine';
import { StorageError, mutationProblem, type QueueStorage, type StoredRow } from './storage';
import type { EngineStateRecord, JournalEntry, Mutation } from './types';

/**
 * The real store: IndexedDB, written by hand (no library). IndexedDB works over plain http, which is how a scorer's phone reaches the laptop on a local network;
 * the secure-context-only APIs (service workers, Cache Storage, Web Locks, `storage.persist`) are deliberately not used.
 *
 * One `commit` is one readwrite transaction over every store it touches, so it is all or nothing: if any request fails (a full disk, a bad key, a blocked
 * database) the transaction is aborted and nothing was written. That is what lets the screen show a ball only after its commit has succeeded.
 *
 * Stores: `queue` (key [matchId, n]), `journal` (auto id, index on matchId), `engineState` (key matchId), `confirmed` (key [matchId, seq]).
 */
export const DATABASE_NAME = 'dsports-scoring';
const VERSION = 1;

const failure = (what: string, cause: unknown): StorageError => new StorageError(`${what}: ${cause instanceof Error ? cause.message : String(cause)}`, cause);

function open(name: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new StorageError('This browser has no IndexedDB'));
      return;
    }
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(name, VERSION);
    } catch (e) {
      reject(failure('IndexedDB could not be opened', e));
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      db.createObjectStore('queue', { keyPath: ['matchId', 'n'] });
      db.createObjectStore('journal', { keyPath: 'id', autoIncrement: true }).createIndex('byMatch', 'matchId');
      db.createObjectStore('engineState', { keyPath: 'matchId' });
      db.createObjectStore('confirmed', { keyPath: ['matchId', 'seq'] });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(failure('IndexedDB could not be opened', request.error));
    request.onblocked = () => reject(new StorageError('IndexedDB is blocked by another tab holding an older version'));
  });
}

const done = <T>(request: IDBRequest<T>): Promise<T> =>
  new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(failure('IndexedDB request failed', request.error));
  });

export class IdbStorage implements QueueStorage {
  constructor(private readonly db: IDBDatabase) {}

  commit(mutations: readonly Mutation[]): Promise<void> {
    for (const m of mutations) {
      const problem = mutationProblem(m);
      if (problem !== null) {
        return Promise.reject(new StorageError(problem));
      }
    }
    if (mutations.length === 0) {
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      let tx: IDBTransaction;
      try {
        tx = this.db.transaction(['queue', 'journal', 'engineState', 'confirmed'], 'readwrite');
      } catch (e) {
        reject(failure('IndexedDB transaction could not start', e));
        return;
      }
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(failure('IndexedDB write failed', tx.error));
      tx.onabort = () => reject(failure('IndexedDB write was aborted, nothing was saved', tx.error));
      try {
        for (const m of mutations) {
          switch (m.kind) {
            case 'putItem':
              tx.objectStore('queue').put(m.item);
              break;
            case 'deleteItem':
              tx.objectStore('queue').delete([m.matchId, m.n]);
              break;
            case 'journal':
              tx.objectStore('journal').add(m.entry);
              break;
            case 'putEngineState':
              tx.objectStore('engineState').put(m.record);
              tx.objectStore('confirmed').delete(IDBKeyRange.bound([m.record.matchId, -Infinity], [m.record.matchId, m.record.seq]));
              break;
            case 'appendConfirmed':
              tx.objectStore('confirmed').put({ matchId: m.matchId, seq: m.entry.seq, entry: m.entry });
              break;
          }
        }
      } catch (e) {
        // A request that cannot even be made (a value that cannot be stored): undo everything in this transaction.
        try {
          tx.abort();
        } catch {
          // Already finished.
        }
        reject(failure('IndexedDB could not store a value, nothing was saved', e));
      }
    });
  }

  items(matchId: string): Promise<StoredRow[]> {
    return new Promise((resolve, reject) => {
      const rows: StoredRow[] = [];
      const request = this.db.transaction('queue', 'readonly').objectStore('queue').openCursor(IDBKeyRange.bound([matchId, -Infinity], [matchId, Infinity]));
      request.onsuccess = () => {
        const cursor = request.result;
        if (cursor === null) {
          resolve(rows);
          return;
        }
        rows.push({ n: (cursor.key as [string, number])[1], row: cursor.value as unknown });
        cursor.continue();
      };
      request.onerror = () => reject(failure('IndexedDB read failed', request.error));
    });
  }

  async journal(matchId: string): Promise<JournalEntry[]> {
    const rows = await done(this.db.transaction('journal', 'readonly').objectStore('journal').index('byMatch').getAll(matchId));
    return rows.map((r: JournalEntry & { id?: number }) => {
      const { id: _id, ...entry } = r;
      return entry;
    });
  }

  async engineState(matchId: string): Promise<EngineStateRecord | undefined> {
    return (await done(this.db.transaction('engineState', 'readonly').objectStore('engineState').get(matchId))) as EngineStateRecord | undefined;
  }

  async tail(matchId: string): Promise<LogEntry[]> {
    const rows = (await done(
      this.db.transaction('confirmed', 'readonly').objectStore('confirmed').getAll(IDBKeyRange.bound([matchId, -Infinity], [matchId, Infinity])),
    )) as { entry: LogEntry }[];
    return rows.map((r) => r.entry);
  }

  close(): void {
    this.db.close();
  }
}

/** Opens (and creates) the database. Throws `StorageError` when IndexedDB is missing, blocked or refused (some private modes): the screen then falls back to online, one-slot scoring. */
export async function openIdbStorage(name: string = DATABASE_NAME): Promise<IdbStorage> {
  return new IdbStorage(await open(name));
}
