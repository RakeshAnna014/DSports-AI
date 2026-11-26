import { AxiosError } from 'axios';
import { scoringApi } from '../../../api/cricketScoring';
import type { CommandBody } from '../../../types/cricketScoring';
import { toBody } from '../engine';
import type { SenderApi } from './sender';
import { SendError, classifyHttp } from './sendErrors';

/**
 * The real network side of the sender: the two scoring routes and the engine-state route, with every failure turned into a `SendError` (the sender decides what
 * each one means). This file is the only place in the offline code that knows about HTTP; it is checked by the emulated browser run (`e2e/cricket-offline.mjs`).
 */
export function toSendError(error: unknown): SendError {
  if (error instanceof SendError) {
    return error;
  }
  if (error instanceof AxiosError && error.response) {
    const header = error.response.headers?.['retry-after'] as string | undefined;
    return new SendError(classifyHttp(error.response.status, error.response.data, header, Date.now()));
  }
  // No answer at all (airplane mode, a dropped connection, a timeout): nothing is known about the item.
  return new SendError({ kind: 'NETWORK' });
}

export const httpSendApi: SenderApi = {
  async send(item) {
    try {
      const out =
        item.kind === 'COMMAND' && item.command !== null
          ? await scoringApi.command(item.matchId, { clientEventId: item.clientEventId, expectedSeq: item.expectedSeq, command: toBody(item.command) as unknown as CommandBody, tappedAt: item.tappedAt || undefined })
          : await scoringApi.undo(item.matchId, { clientEventId: item.clientEventId, expectedSeq: item.expectedSeq, mode: item.mode ?? 'BALL', tappedAt: item.tappedAt || undefined });
      return { seq: out.eventSeq, replayed: out.replayed };
    } catch (error) {
      throw toSendError(error);
    }
  },
  async engineState(matchId) {
    try {
      return await scoringApi.engineState(matchId);
    } catch (error) {
      throw toSendError(error);
    }
  },
};
