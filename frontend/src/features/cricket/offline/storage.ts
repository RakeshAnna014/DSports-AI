import type { LogEntry } from '../engine';
import type { EngineStateRecord, JournalEntry, Mutation } from './types';

/**
 * Where the queue lives. `commit` is all or nothing: a ball is on the screen only after the commit that holds it has succeeded, so a ball can never be shown and
 * lost. Two adapters: this file's in-memory one (tests, and the fallback for nothing) and `idbStorage.ts` (IndexedDB, the real one). Both are held to the same
 * contract by `storageContract.ts`.
 */
export class StorageError extends Error {
  constructor(
    message: string,
    readonly cause?: unknown,
  ) {
    super(message);
    this.name = 'StorageError';
  }
}

export interface StoredRow {
  n: number;
  row: unknown;
}

export interface QueueStorage {
  /** Applies every mutation in one transaction, or none of them (throws `StorageError`). */
  commit(mutations: readonly Mutation[]): Promise<void>;
  /** Every row of the match's queue with the key it is stored under (the item's `n`), whether or not the row can be read. */
  items(matchId: string): Promise<StoredRow[]>;
  journal(matchId: string): Promise<JournalEntry[]>;
  engineState(matchId: string): Promise<EngineStateRecord | undefined>;
  /** Entries confirmed since the engine state was last written, in seq order. */
  tail(matchId: string): Promise<LogEntry[]>;
}

/** A mutation that cannot be stored, found before anything is written. */
export function mutationProblem(m: Mutation): string | null {
  switch (m.kind) {
    case 'putItem':
      return typeof m.item.matchId === 'string' && Number.isSafeInteger(m.item.n) ? null : 'an item needs a match id and a whole-number position';
    case 'deleteItem':
      return typeof m.matchId === 'string' && Number.isSafeInteger(m.n) ? null : 'a delete needs a match id and a whole-number position';
    case 'journal':
      return typeof m.entry.matchId === 'string' ? null : 'a journal line needs a match id';
    case 'putEngineState':
      return typeof m.record.matchId === 'string' ? null : 'the engine state needs a match id';
    case 'appendConfirmed':
      return typeof m.matchId === 'string' && Number.isSafeInteger(m.entry.seq) ? null : 'a confirmed entry needs a match id and a seq';
  }
}

/** The in-memory adapter. `failNext` makes the next commit fail without changing anything, the way a full disk or a blocked database does. */
export class MemoryStorage implements QueueStorage {
  private queue = new Map<string, unknown>();
  private lines: JournalEntry[] = [];
  private engine = new Map<string, EngineStateRecord>();
  private confirmed = new Map<string, LogEntry>();
  private failure: Error | null = null;
  commits = 0;

  failNext(error: Error = new StorageError('The disk is full')): void {
    this.failure = error;
  }

  async commit(mutations: readonly Mutation[]): Promise<void> {
    if (this.failure !== null) {
      const error = this.failure;
      this.failure = null;
      throw error instanceof StorageError ? error : new StorageError(error.message, error);
    }
    for (const m of mutations) {
      const problem = mutationProblem(m);
      if (problem !== null) {
        throw new StorageError(problem);
      }
    }
    // Everything was checked above, so applying cannot fail half way.
    for (const m of mutations) {
      switch (m.kind) {
        case 'putItem':
          this.queue.set(`${m.item.matchId}\u0000${m.item.n}`, structuredClone(m.item));
          break;
        case 'deleteItem':
          this.queue.delete(`${m.matchId}\u0000${m.n}`);
          break;
        case 'journal':
          this.lines.push(structuredClone(m.entry));
          break;
        case 'putEngineState':
          this.engine.set(m.record.matchId, structuredClone(m.record));
          for (const key of [...this.confirmed.keys()]) {
            const [id, seq] = key.split('\u0000');
            if (id === m.record.matchId && Number(seq) <= m.record.seq) {
              this.confirmed.delete(key);
            }
          }
          break;
        case 'appendConfirmed':
          this.confirmed.set(`${m.matchId}\u0000${m.entry.seq}`, structuredClone(m.entry));
          break;
      }
    }
    this.commits++;
  }

  async items(matchId: string): Promise<StoredRow[]> {
    return [...this.queue.entries()]
      .filter(([key]) => key.startsWith(`${matchId}\u0000`))
      .map(([key, row]) => ({ n: Number(key.slice(matchId.length + 1)), row: structuredClone(row) }))
      .sort((x, y) => x.n - y.n);
  }

  async journal(matchId: string): Promise<JournalEntry[]> {
    return this.lines.filter((l) => l.matchId === matchId).map((l) => structuredClone(l));
  }

  async engineState(matchId: string): Promise<EngineStateRecord | undefined> {
    const record = this.engine.get(matchId);
    return record === undefined ? undefined : structuredClone(record);
  }

  async tail(matchId: string): Promise<LogEntry[]> {
    return [...this.confirmed.entries()]
      .filter(([key]) => key.startsWith(`${matchId}\u0000`))
      .map(([, entry]) => structuredClone(entry))
      .sort((a, b) => a.seq - b.seq);
  }

  /** Test helper: puts a raw row in the queue store, the way a bug or an old version might have left one. */
  putRaw(matchId: string, n: number, row: unknown): void {
    this.queue.set(`${matchId}\u0000${n}`, row);
  }
}
