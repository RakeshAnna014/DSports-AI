import { useEffect, useState } from 'react';
import { cricketPublicApi } from '@/api/cricketPublic';
import type { PublicAnalytics } from '@/types/cricketPublic';

/**
 * The charts and commentary for a match, asked for again only when the scorecard's seq changes (a new ball, an undo): a quiet match costs one request, not one every
 * few seconds. If it cannot be loaded the scorecard still works and the panel says so; the last answer that arrived stays on screen.
 */
export const usePublicAnalytics = (matchId: string, seq: number | null): { data: PublicAnalytics | null; failing: boolean; loading: boolean } => {
  const [data, setData] = useState<PublicAnalytics | null>(null);
  const [failing, setFailing] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (seq === null) {
      return undefined;
    }
    let cancelled = false;
    setLoading(true);
    cricketPublicApi
      .analytics(matchId)
      .then((answer) => {
        if (!cancelled) {
          setData(answer);
          setFailing(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFailing(true);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [matchId, seq]);

  return { data, failing, loading };
};
