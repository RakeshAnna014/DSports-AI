/**
 * What a failed send means for the queue (plan section 3.2), separated from any HTTP library so the sender can be tested with a fake server and the real adapter
 * only has to translate an answer into one of these.
 */
export type SendFailure =
  /** No network, a timeout, or a 5xx: nothing is known about the item, so it waits and is tried again with the same id. */
  | { kind: 'NETWORK' }
  /** 401: the access token is not accepted. Refresh once, then ask the scorer to sign in; the queue waits. */
  | { kind: 'AUTH' }
  /** 403 or 404: no longer allowed to score this match, or it is gone. */
  | { kind: 'FORBIDDEN' }
  /** 409 SCORE_CONFLICT: somebody else scored since. The phone reads the server's log and the scorer decides. */
  | { kind: 'CONFLICT' }
  /** 409 MATCH_LOCKED: the correction window has closed. */
  | { kind: 'LOCKED'; message: string; editableUntil: string | null }
  /** 422 SCORING_REJECTED: the server's engine refused a ball the phone accepted. */
  | { kind: 'REJECTED'; violation: string; message: string }
  /** 429: too many requests; honour Retry-After. */
  | { kind: 'RATE_LIMITED'; retryAfterMs: number }
  /** 400 or `CLIENT_EVENT_REUSED`: a bug or corrupted storage. Stop and keep everything. */
  | { kind: 'BAD_REQUEST'; code: string; message: string };

export class SendError extends Error {
  constructor(readonly failure: SendFailure) {
    super(`send failed: ${failure.kind}`);
    this.name = 'SendError';
  }
}

interface Body {
  code?: unknown;
  message?: unknown;
  details?: unknown;
}

const text = (v: unknown, fallback: string): string => (typeof v === 'string' && v.length > 0 ? v : fallback);

/** How long to wait for a 429: the `Retry-After` header in seconds (or an HTTP date), at least one second, at most five minutes. */
export function retryAfterMs(header: string | null | undefined, nowMs: number): number {
  const fallback = 5000;
  if (header === null || header === undefined || header.trim() === '') {
    return fallback;
  }
  const seconds = Number(header);
  const ms = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(header) - nowMs;
  return Number.isFinite(ms) ? Math.min(300_000, Math.max(1000, ms)) : fallback;
}

/** Translates an HTTP answer (status, body, `Retry-After`) into what it means for the queue. 2xx is not a failure and is not passed here. */
export function classifyHttp(status: number, data: unknown, retryAfter: string | null | undefined, nowMs: number): SendFailure {
  const body: Body = typeof data === 'object' && data !== null ? (data as Body) : {};
  const code = typeof body.code === 'string' ? body.code : '';
  if (status === 401) {
    return { kind: 'AUTH' };
  }
  if (status === 403 || status === 404) {
    return { kind: 'FORBIDDEN' };
  }
  if (status === 429) {
    return { kind: 'RATE_LIMITED', retryAfterMs: retryAfterMs(retryAfter, nowMs) };
  }
  if (status >= 500) {
    return { kind: 'NETWORK' };
  }
  if (status === 409 && code === 'SCORE_CONFLICT') {
    return { kind: 'CONFLICT' };
  }
  if (status === 409 && code === 'MATCH_LOCKED') {
    return { kind: 'LOCKED', message: text(body.message, 'This match is locked.'), editableUntil: null };
  }
  if (status === 422 && code === 'SCORING_REJECTED') {
    const violation = typeof body.details === 'object' && body.details !== null && typeof (body.details as { violation?: unknown }).violation === 'string' ? (body.details as { violation: string }).violation : 'UNKNOWN';
    return { kind: 'REJECTED', violation, message: text(body.message, 'That is not allowed.') };
  }
  // Anything else (400, 409 CLIENT_EVENT_REUSED, an unexpected 4xx): the request itself is wrong. Keep everything and say so.
  return { kind: 'BAD_REQUEST', code: code || `HTTP_${status}`, message: text(body.message, 'The server did not accept this change.') };
}

/** Exponential backoff for a retry: 2, 4, 8, 16, then 30 seconds. `attempt` counts failures in a row, starting at 1. */
export const backoffMs = (attempt: number): number => Math.min(30_000, 2000 * 2 ** Math.max(0, attempt - 1));
