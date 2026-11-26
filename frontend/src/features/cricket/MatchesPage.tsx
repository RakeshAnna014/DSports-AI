import { useQuery } from '@tanstack/react-query';
import { Box, Button, Card, CardActionArea, CardContent, Chip, Skeleton, Stack, Typography } from '@mui/material';
import { SportsCricket } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { cricketApi } from '@/api/cricket';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';

const STATUS_LABEL: Record<string, string> = {
  SCHEDULED: 'Set up', TOSS_DONE: 'Toss done', IN_PROGRESS: 'In progress', INNINGS_BREAK: 'Innings break', COMPLETED: 'Completed', ABANDONED: 'Abandoned', NO_RESULT: 'No result',
};

/** My matches: the ones I created, may score, or have a team in. */
const MatchesPage = () => {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const matches = useQuery({ queryKey: ['my-matches'], queryFn: cricketApi.matches.mine, enabled: isAuthenticated });

  if (!isAuthenticated) {
    return <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="Sign in to manage matches" body="Matches are set up from your teams." action={<Button variant="contained" onClick={() => navigate('/login')}>Sign in</Button>} />;
  }
  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="h3" component="h1">My matches</Typography>
        <Button variant="contained" onClick={() => navigate('/matches/new')} data-testid="new-match">New match</Button>
      </Stack>
      {matches.isError && <ErrorState body="We couldn't load your matches." onRetry={() => void matches.refetch()} />}
      {matches.isLoading && <Skeleton variant="rounded" height={80} />}
      {matches.data?.length === 0 && <EmptyState icon={<SportsCricket sx={{ fontSize: 56 }} />} title="No matches yet" body="Create two teams, then set up a match between them." />}
      <Stack spacing={1.5}>
        {matches.data?.map((m) => (
          <Card key={m.id} variant="outlined" data-testid="match-card">
            <CardActionArea onClick={() => navigate(`/matches/${m.id}/setup`)}>
              <CardContent>
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  <Box>
                    <Typography variant="h6" component="h2">{m.teamA.teamName} vs {m.teamB.teamName}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {m.overs} overs · {m.ballType === 'TENNIS' ? 'Tennis ball' : 'Leather ball'}
                      {m.scheduledAt ? ` · ${new Date(m.scheduledAt).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}` : ''}
                      {m.venue?.name ? ` · ${m.venue.name}` : m.city ? ` · ${m.city}` : ''}
                    </Typography>
                  </Box>
                  <Stack direction="row" spacing={0.75}>
                    <Chip size="small" label={STATUS_LABEL[m.status] ?? m.status} />
                    {m.canScore && <Chip size="small" variant="outlined" label="Scorer" />}
                  </Stack>
                </Stack>
              </CardContent>
            </CardActionArea>
          </Card>
        ))}
      </Stack>
    </Box>
  );
};

export default MatchesPage;
