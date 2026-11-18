import type { CommandBody, UndoMode } from '@/types/cricketScoring';

/**
 * A durable one-slot outbox. A scoring change is saved on the phone with its client event id before it is sent and removed only when the server answers,
 * so a dropped connection, a sleeping phone or a reload cannot lose it, and sending it again cannot count it twice (the server knows the id).
 * Only one change waits at a time: the next ball depends on a state the phone cannot work out by itself, so it is entered after the server has answered.
 */
export type OutboxItem =
  | { kind: 'command'; clientEventId: string; expectedSeq: number; command: CommandBody; label: string; createdAt: string }
  | { kind: 'undo'; clientEventId: string; expectedSeq: number; mode: UndoMode; label: string; createdAt: string };

const keyFor = (matchId: string) => `dsports.scoring.outbox.${matchId}`;

const isItem = (value: unknown): value is OutboxItem => {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const v = value as Record<string, unknown>;
  const common = typeof v.clientEventId === 'string' && typeof v.expectedSeq === 'number' && typeof v.label === 'string';
  return common && ((v.kind === 'command' && typeof v.command === 'object' && v.command !== null) || (v.kind === 'undo' && (v.mode === 'BALL' || v.mode === 'COMMAND')));
};

/** Storage can be unavailable (private windows, blocked site data): the screen still works, it just cannot survive a reload. */
export const loadOutbox = (matchId: string): OutboxItem | null => {
  try {
    const raw = localStorage.getItem(keyFor(matchId));
    if (!raw) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    return isItem(parsed) ? parsed : null;
  } catch {
    return null;
  }
};

export const saveOutbox = (matchId: string, item: OutboxItem): void => {
  try {
    localStorage.setItem(keyFor(matchId), JSON.stringify(item));
  } catch {
    // Not saved: nothing else to do.
  }
};

export const clearOutbox = (matchId: string): void => {
  try {
    localStorage.removeItem(keyFor(matchId));
  } catch {
    // Nothing to clear.
  }
};
