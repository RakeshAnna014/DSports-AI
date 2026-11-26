import { useEffect, useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Box, Button, Card, CardActionArea, CardContent, Chip, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { Link as RouterLink } from 'react-router-dom';
import { cricketPublicApi } from '@/api/cricketPublic';
import { EmptyState, ErrorState } from '@/components/common/States';
import { STATUS_LABEL } from './ScorecardView';

const PAGE_SIZE = 20;
const REFRESH_MS = 15000;
const TYPING_PAUSE_MS = 400;

/** Matches being played now, for anyone, filterable by city. It refreshes itself while the tab is visible. */
const LiveMatchesPage = () => {
  const [typed, setTyped] = useState('');
  const [city, setCity] = useState('');
  useEffect(() => {
    const wait = setTimeout(() => setCity(typed.trim()), TYPING_PAUSE_MS);
    return () => clearTimeout(wait);
  }, [typed]);

  const live = useInfiniteQuery({
    queryKey: ['live-matches', city],
    queryFn: ({ pageParam }) => cricketPublicApi.live({ city, page: pageParam, size: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
    refetchInterval: REFRESH_MS,
  });
  const matches = live.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <Box sx={{ maxWidth: 640, mx: 'auto' }} data-testid="live-matches">
      <Typography variant="h4" component="h1" sx={{ mb: 0.5 }}>Live cricket</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Matches being played right now. Open one to follow the scorecard ball by ball.</Typography>
      <TextField size="small" fullWidth label="City" value={typed} onChange={(e) => setTyped(e.target.value)} sx={{ mb: 2 }} slotProps={{ htmlInput: { maxLength: 80 } }} />

      {live.isError && <ErrorState body="We couldn't load the live matches." onRetry={() => void live.refetch()} />}
      {live.isLoading && <Skeleton variant="rounded" height={90} />}
      {live.isSuccess && matches.length === 0 && (
        <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title={city ? `No live matches in ${city}` : 'No live matches right now'} body="Check back when a match is being scored." />
      )}

      <Stack spacing={1.5}>
        {matches.map((m) => (
          <Card key={m.matchId} variant="outlined" data-testid="live-match">
            <CardActionArea component={RouterLink} to={`/m/${m.matchId}`}>
              <CardContent>
                <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
                  <Typography variant="h6" component="h2">{m.teamA} vs {m.teamB}</Typography>
                  <Chip size="small" color={m.status === 'IN_PROGRESS' ? 'error' : 'default'} label={STATUS_LABEL[m.status] ?? m.status} />
                </Stack>
                {m.scores.map((s) => (
                  <Typography key={s.team} variant="body1" sx={{ fontWeight: s.current ? 700 : 400 }} data-testid="live-score">{s.team} {s.runs}/{s.wickets} ({s.overs} ov)</Typography>
                ))}
                <Typography variant="body2" color="text.secondary">{[`${m.overs} overs`, m.venue, m.city].filter(Boolean).join(' · ')}</Typography>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Stack>
      {live.hasNextPage && (
        <Button sx={{ mt: 2 }} fullWidth variant="outlined" onClick={() => void live.fetchNextPage()} disabled={live.isFetchingNextPage} data-testid="load-more">
          {live.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </Button>
      )}
    </Box>
  );
};

export default LiveMatchesPage;
