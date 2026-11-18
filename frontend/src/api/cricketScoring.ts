import { AxiosError } from 'axios';
import apiClient from './client';
import type { CommandBody, ScoringConflict, ScoringOutcome, ScoringView, UndoMode } from '@/types/cricketScoring';

/** Scoring calls. Every change carries a client event id (a retry is harmless) and the seq the scorer last saw (a stale one is a conflict). */
export const scoringApi = {
  state: (matchId: string) => apiClient.get<ScoringView>(`/v1/matches/${matchId}/scoring`).then((r) => r.data),
  command: (matchId: string, body: { clientEventId: string; expectedSeq: number; command: CommandBody }) =>
    apiClient.post<ScoringOutcome>(`/v1/matches/${matchId}/scoring/commands`, body).then((r) => r.data),
  undo: (matchId: string, body: { clientEventId: string; expectedSeq: number; mode: UndoMode }) =>
    apiClient.post<ScoringOutcome>(`/v1/matches/${matchId}/scoring/undo`, body).then((r) => r.data),
};

/** What a failed scoring call means for the scorer. */
export type ScoringFailure =
  | { kind: 'OFFLINE' }
  | { kind: 'CONFLICT'; conflict: ScoringConflict }
  | { kind: 'REJECTED'; violation: string; message: string }
  | { kind: 'LOCKED'; message: string }
  | { kind: 'OTHER'; message: string };

export const classifyScoringError = (err: unknown): ScoringFailure => {
  if (!(err instanceof AxiosError) || !err.response) {
    return { kind: 'OFFLINE' };
  }
  const { status, data } = err.response as { status: number; data?: { code?: string; message?: string; details?: unknown } };
  if (status >= 500) {
    return { kind: 'OFFLINE' };
  }
  if (data?.code === 'SCORE_CONFLICT' && data.details) {
    return { kind: 'CONFLICT', conflict: data.details as ScoringConflict };
  }
  if (data?.code === 'SCORING_REJECTED') {
    return { kind: 'REJECTED', violation: (data.details as { violation?: string } | undefined)?.violation ?? 'UNKNOWN', message: data.message ?? 'That is not allowed.' };
  }
  if (data?.code === 'MATCH_LOCKED') {
    return { kind: 'LOCKED', message: data.message ?? 'This match is locked.' };
  }
  return { kind: 'OTHER', message: data?.message ?? 'The change could not be saved.' };
};
