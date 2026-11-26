import { Skeleton } from '@mui/material';
import { useParams } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { useOfflineScorer } from '../offline/useOfflineScorer';
import LegacyScorerPage from './LegacyScorerPage';
import OfflineScorerPage from './OfflineScorerPage';
import { useTabLease } from './useTabLease';

/**
 * The scorer's route. A phone that can keep scoring with no signal (IndexedDB is there, the match is ready, the server's rules are the ones this page knows and
 * the self-check passed) gets the offline screen. Everything else gets the screen exactly as it was: one change at a time, online. A phone that has waiting
 * balls always gets the offline screen, even if offline scoring has been switched off, because that screen is where those balls can be sent and reviewed.
 */
const ScorerPage = () => {
  const { id = '' } = useParams();
  const userId = useAuthStore((s) => s.user?.userId ?? null);
  const lease = useTabLease(id);
  const offline = useOfflineScorer(id, userId, lease.owner, lease.recheck);

  if (offline.status.kind === 'loading') {
    return <Skeleton variant="rounded" height={240} />;
  }
  const state = offline.state;
  const holdsBalls = state !== null && state.items.length > 0;
  if (offline.status.kind === 'ready' && state !== null && (state.offline.on || holdsBalls)) {
    return <OfflineScorerPage lease={lease} offline={offline} />;
  }
  return <LegacyScorerPage lease={lease} />;
};

export default ScorerPage;
