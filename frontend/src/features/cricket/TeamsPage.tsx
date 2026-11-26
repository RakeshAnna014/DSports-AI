import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Card, CardActionArea, CardContent, Chip, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { Groups } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { cricketApi } from '@/api/cricket';
import { getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';

/** My teams: the ones I created and the ones with a player linked to my account. A team is created here and filled on its own page. */
const TeamsPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const [name, setName] = useState('');
  const [city, setCity] = useState('');
  const [error, setError] = useState<string | null>(null);
  const teams = useQuery({ queryKey: ['my-teams'], queryFn: cricketApi.teams.mine, enabled: isAuthenticated });
  const create = useMutation({
    mutationFn: () => cricketApi.teams.create(name.trim(), city.trim()),
    onSuccess: (team) => { void queryClient.invalidateQueries({ queryKey: ['my-teams'] }); navigate(`/teams/${team.id}`); },
    onError: (err) => setError(getApiErrorMessage(err, 'The team could not be created.')),
  });

  if (!isAuthenticated) {
    return <EmptyState icon={<Groups sx={{ fontSize: 56 }} />} title="Sign in to manage teams" body="Teams and players belong to your account." action={<Button variant="contained" onClick={() => navigate('/login')}>Sign in</Button>} />;
  }
  return (
    <Box>
      <Typography variant="h3" component="h1">My teams</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Create a team, add your players (they do not need an account) and choose a captain.</Typography>

      <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }} onSubmit={(e) => { e.preventDefault(); setError(null); if (name.trim()) create.mutate(); }}>
        <TextField size="small" label="Team name" value={name} onChange={(e) => setName(e.target.value)} inputProps={{ maxLength: 60 }} />
        <TextField size="small" label="City (optional)" value={city} onChange={(e) => setCity(e.target.value)} inputProps={{ maxLength: 80 }} />
        <Button type="submit" variant="contained" disabled={!name.trim() || create.isPending} data-testid="create-team">Create team</Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)} data-testid="team-error">{error}</Alert>}

      {teams.isError && <ErrorState body="We couldn't load your teams." onRetry={() => void teams.refetch()} />}
      {teams.isLoading && <Skeleton variant="rounded" height={80} />}
      {teams.data?.length === 0 && <EmptyState icon={<Groups sx={{ fontSize: 56 }} />} title="No teams yet" body="Create your first team above." />}
      <Stack spacing={1.5}>
        {teams.data?.map((t) => (
          <Card key={t.id} variant="outlined" data-testid="team-card">
            <CardActionArea onClick={() => navigate(`/teams/${t.id}`)}>
              <CardContent>
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  <Box>
                    <Typography variant="h6" component="h2">{t.name}</Typography>
                    <Typography variant="body2" color="text.secondary">{t.city ?? 'No city'} · {t.members.length} player{t.members.length === 1 ? '' : 's'}</Typography>
                  </Box>
                  <Stack direction="row" spacing={0.75}>
                    {t.status === 'ARCHIVED' && <Chip size="small" label="Archived" />}
                    {!t.canEdit && <Chip size="small" variant="outlined" label="Member" />}
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

export default TeamsPage;
