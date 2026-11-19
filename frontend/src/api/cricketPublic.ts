import axios from 'axios';
import type { PublicMatchSummary, PublicPage, PublicScorecard } from '@/types/cricketPublic';

/**
 * The two cricket routes that need no sign-in. They use their own client with no stored token, so a signed-out visitor never triggers the shared client's
 * "refresh the token, else go to the login page" behaviour, and nothing about a signed-in user is sent with a public read.
 */
const publicClient = axios.create({ baseURL: '/api', headers: { Accept: 'application/json' } });

export type ScorecardAnswer = { kind: 'changed'; data: PublicScorecard; etag: string | null } | { kind: 'unchanged' };

export const cricketPublicApi = {
  /** Asks for the scorecard; sending the ETag of the one already shown makes an unchanged match a cheap 304 with no body. */
  scorecard: async (matchId: string, etag: string | null): Promise<ScorecardAnswer> => {
    const response = await publicClient.get<PublicScorecard>(`/v1/matches/${encodeURIComponent(matchId)}/scorecard`, {
      headers: etag ? { 'If-None-Match': etag } : {},
      validateStatus: (status) => (status >= 200 && status < 300) || status === 304,
    });
    if (response.status === 304) {
      return { kind: 'unchanged' };
    }
    const header = response.headers['etag'];
    return { kind: 'changed', data: response.data, etag: typeof header === 'string' ? header : null };
  },

  live: (params: { city?: string; page: number; size: number }) =>
    publicClient
      .get<PublicPage<PublicMatchSummary>>('/v1/public/matches/live', { params: { city: params.city || undefined, page: params.page, size: params.size } })
      .then((r) => r.data),
};

/** True when the server said the match does not exist or has not started (they are the same answer on purpose). */
export const isNotFound = (error: unknown): boolean => axios.isAxiosError(error) && error.response?.status === 404;
