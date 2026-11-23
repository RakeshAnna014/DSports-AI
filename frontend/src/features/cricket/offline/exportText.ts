import { toBody } from '../engine';
import type { QueueItem, QueueState } from './types';

/**
 * "Copy unsent balls": everything the phone holds that the server does not, as text a person can read and keep and as JSON a developer can re-send. It is the
 * escape hatch for the one thing storage cannot promise (the browser clearing site data), so it never leaves anything out: waiting, in flight and set aside.
 */
export function exportItems(items: readonly QueueItem[]): { text: string; json: string } {
  const lines = items.map((i) => {
    const state = i.status === 'SET_ASIDE' ? `set aside (${i.reason ?? 'no reason recorded'}${i.detail ? `: ${i.detail}` : ''})` : i.status === 'IN_FLIGHT' ? 'being sent' : 'waiting';
    return `${String(i.n).padStart(3, ' ')}. ${i.label} | ${state} | tapped ${i.tappedAt} | id ${i.clientEventId}`;
  });
  const json = JSON.stringify(
    items.map((i) => ({
      n: i.n,
      matchId: i.matchId,
      clientEventId: i.clientEventId,
      expectedSeq: i.expectedSeq,
      kind: i.kind,
      command: i.command === null ? null : toBody(i.command),
      mode: i.mode,
      status: i.status,
      reason: i.reason,
      tappedAt: i.tappedAt,
    })),
    null,
    2,
  );
  return { text: lines.join('\n'), json };
}

export const exportText = (state: QueueState): { text: string; json: string } => exportItems(state.items);
