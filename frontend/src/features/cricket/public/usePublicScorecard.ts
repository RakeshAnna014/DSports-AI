import { useCallback, useEffect, useRef, useState } from 'react';
import { cricketPublicApi, isNotFound } from '@/api/cricketPublic';
import type { PublicScorecard } from '@/types/cricketPublic';

/** How often a live scorecard asks the server. An unchanged match answers 304 with no body, so this is cheap. */
export const POLL_MS = 4000;
/** A match that has not started (or a wrong link) is checked less often. */
export const NOT_STARTED_POLL_MS = 10000;
const MAX_BACKOFF_MS = 30000;
const FINISHED = new Set(['COMPLETED', 'ABANDONED', 'NO_RESULT']);

export interface ScorecardFeed {
  card: PublicScorecard | null;
  loading: boolean;
  /** The server says there is no such match or it has not started: both look the same from outside. */
  notFound: boolean;
  /** The last refresh failed; the card (if any) is the last one that arrived. */
  failing: boolean;
  /** The match is over, so polling has stopped. */
  finished: boolean;
  /** When the server last answered, as a time stamp. */
  checkedAt: number | null;
  /** Asks once now, and starts polling again if the match turns out to be live (an undo can reopen a finished match). */
  refresh: () => void;
}

/**
 * Keeps one match's public scorecard fresh. It sends the ETag of what it shows, so most polls are a 304; it pauses while the tab is hidden and asks at once
 * when the tab comes back or the network returns; it backs off while the server is failing; and it stops when the match is over.
 */
export const usePublicScorecard = (matchId: string): ScorecardFeed => {
  const [card, setCard] = useState<PublicScorecard | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [failing, setFailing] = useState(false);
  const [finished, setFinished] = useState(false);
  const [checkedAt, setCheckedAt] = useState<number | null>(null);
  const pollNow = useRef<() => void>(() => undefined);

  useEffect(() => {
    let etag: string | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelled = false;
    let over = false;
    let failures = 0;
    let inFlight = false;
    const hidden = () => document.visibilityState === 'hidden';

    const later = (ms: number) => {
      clearTimeout(timer);
      if (!cancelled && !over && !hidden()) {
        timer = setTimeout(() => void poll(), ms);
      }
    };

    const poll = async (): Promise<void> => {
      clearTimeout(timer);
      if (cancelled || inFlight) {
        return;
      }
      inFlight = true;
      let next = POLL_MS;
      try {
        const answer = await cricketPublicApi.scorecard(matchId, etag);
        if (cancelled) {
          return;
        }
        failures = 0;
        setFailing(false);
        setNotFound(false);
        setCheckedAt(Date.now());
        if (answer.kind === 'changed') {
          etag = answer.etag;
          setCard(answer.data);
          over = FINISHED.has(answer.data.status);
          setFinished(over);
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        setCheckedAt(Date.now());
        if (isNotFound(error)) {
          failures = 0;
          setFailing(false);
          setNotFound(true);
          next = NOT_STARTED_POLL_MS;
        } else {
          failures += 1;
          setFailing(true);
          next = Math.min(POLL_MS * 2 ** failures, MAX_BACKOFF_MS);
        }
      } finally {
        inFlight = false;
        if (!cancelled) {
          setLoading(false);
        }
      }
      later(next);
    };

    pollNow.current = () => {
      over = false;
      setFinished(false);
      void poll();
    };

    const onVisibility = () => {
      if (hidden()) {
        clearTimeout(timer);
      } else if (!over) {
        void poll();
      }
    };
    const onOnline = () => {
      if (!over) {
        void poll();
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('online', onOnline);
    if (!hidden()) {
      void poll();
    }
    return () => {
      cancelled = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('online', onOnline);
    };
  }, [matchId]);

  const refresh = useCallback(() => pollNow.current(), []);
  return { card, loading, notFound, failing, finished, checkedAt, refresh };
};
