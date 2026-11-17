import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, FormControlLabel, MenuItem, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { useNavigate } from 'react-router-dom';
import { cricketApi } from '@/api/cricket';
import { venuesApi } from '@/api/venues';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { ErrorState } from '@/components/common/States';
import { STANDARD_RULES, type BallType, type MatchRules } from '@/types/cricket';

const num = (v: string) => (v.trim() === '' ? NaN : Number(v));
const show = (n: number) => (Number.isNaN(n) ? '' : n);

/**
 * Set up a match: two of my teams, the overs, the ball and the rules (standard ones unless changed), and optionally a paid cricket booking so the match
 * knows its venue and time. The toss, the playing XIs and the scorers are on the next screen.
 */
const NewMatchPage = () => {
  const navigate = useNavigate();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const [teamA, setTeamA] = useState('');
  const [teamB, setTeamB] = useState('');
  const [overs, setOvers] = useState('20');
  const [ballType, setBallType] = useState<BallType>('TENNIS');
  const [city, setCity] = useState('');
  const [umpires, setUmpires] = useState('');
  const [scheduledAt, setScheduledAt] = useState('');
  const [bookingId, setBookingId] = useState('');
  const [rules, setRules] = useState<MatchRules>(STANDARD_RULES);
  const [error, setError] = useState<string | null>(null);

  const teams = useQuery({ queryKey: ['my-teams'], queryFn: cricketApi.teams.mine, enabled: isAuthenticated });
  const bookings = useQuery({ queryKey: ['my-bookings'], queryFn: venuesApi.myBookings, enabled: isAuthenticated });
  const usable = (teams.data ?? []).filter((t) => t.status === 'ACTIVE');
  const cricketBookings = (bookings.data ?? []).filter((b) => b.status === 'CONFIRMED' && b.sportName === 'Cricket' && new Date(b.endAt).getTime() > Date.now());
  const set = <K extends keyof MatchRules>(key: K, value: MatchRules[K]) => setRules((r) => ({ ...r, [key]: value }));

  const create = useMutation({
    mutationFn: () => cricketApi.matches.create({
      teamAId: teamA, teamBId: teamB, overs: Number(overs), ballType, rules: { ...rules, maxOversPerBowler: rules.maxOversPerBowler ?? undefined } as Partial<MatchRules>,
      scheduledAt: scheduledAt ? new Date(scheduledAt).toISOString() : undefined, city: city.trim() || undefined, umpires: umpires.trim() || undefined, bookingId: bookingId || undefined,
    }),
    onSuccess: (match) => navigate(`/matches/${match.id}/setup`),
    onError: (err) => {
      const code = getApiError(err)?.code;
      setError(code === 'BOOKING_ALREADY_USED' ? 'That booking already hosts another match.' : getApiErrorMessage(err, 'The match could not be created.'));
    },
  });

  if (teams.isError) return <ErrorState body="We couldn't load your teams." onRetry={() => void teams.refetch()} />;
  const ready = !!teamA && !!teamB && teamA !== teamB && Number(overs) >= 1 && Number(overs) <= 100;
  return (
    <Box component="form" onSubmit={(e) => { e.preventDefault(); setError(null); if (ready) create.mutate(); }}>
      <Typography variant="h3" component="h1">New match</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Pick two of your teams. You will add the toss, the playing XIs and the scorers next.</Typography>
      {usable.length < 2 && teams.data && <Alert severity="info" sx={{ mb: 2 }} data-testid="need-teams">You need at least two teams to set up a match. <Button size="small" onClick={() => navigate('/teams')}>Create teams</Button></Alert>}

      <Stack spacing={2}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" select label="Team A" value={teamA} onChange={(e) => setTeamA(e.target.value)} fullWidth data-testid="team-a">
            {usable.map((t) => <MenuItem key={t.id} value={t.id} disabled={t.id === teamB}>{t.name}</MenuItem>)}
          </TextField>
          <TextField size="small" select label="Team B" value={teamB} onChange={(e) => setTeamB(e.target.value)} fullWidth data-testid="team-b">
            {usable.map((t) => <MenuItem key={t.id} value={t.id} disabled={t.id === teamA}>{t.name}</MenuItem>)}
          </TextField>
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" label="Overs per side" type="number" value={overs} onChange={(e) => setOvers(e.target.value)} inputProps={{ min: 1, max: 100 }} fullWidth />
          <TextField size="small" select label="Ball" value={ballType} onChange={(e) => setBallType(e.target.value as BallType)} fullWidth>
            <MenuItem value="TENNIS">Tennis ball</MenuItem>
            <MenuItem value="LEATHER">Leather ball</MenuItem>
          </TextField>
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" label="Date and time (optional)" type="datetime-local" value={scheduledAt} onChange={(e) => setScheduledAt(e.target.value)} InputLabelProps={{ shrink: true }} fullWidth />
          <TextField size="small" label="City (optional)" value={city} onChange={(e) => setCity(e.target.value)} inputProps={{ maxLength: 80 }} fullWidth />
        </Stack>
        <TextField size="small" label="Umpires (optional)" value={umpires} onChange={(e) => setUmpires(e.target.value)} inputProps={{ maxLength: 200 }} />
        <TextField size="small" select label="Venue booking (optional)" value={bookingId} onChange={(e) => setBookingId(e.target.value)} data-testid="booking-pick"
          helperText={cricketBookings.length === 0 ? 'No paid, upcoming cricket bookings of yours.' : 'The match takes its venue and time from the booking.'}>
          <MenuItem value="">No booking</MenuItem>
          {cricketBookings.map((b) => <MenuItem key={b.id} value={b.id}>{b.venueName}, {b.courtName} · {new Date(b.startAt).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}</MenuItem>)}
        </TextField>

        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>Rules (standard unless you change them)</Typography>
          <Stack spacing={1.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
              <TextField size="small" label="Players per side" type="number" value={show(rules.playersPerSide)} onChange={(e) => set('playersPerSide', num(e.target.value))} inputProps={{ min: 2, max: 11 }} fullWidth />
              <TextField size="small" label="Balls per over" type="number" value={show(rules.ballsPerOver)} onChange={(e) => set('ballsPerOver', num(e.target.value))} inputProps={{ min: 4, max: 10 }} fullWidth />
              <TextField size="small" label="Max overs per bowler" type="number" value={rules.maxOversPerBowler ?? ''} placeholder="a fifth of the match"
                onChange={(e) => set('maxOversPerBowler', e.target.value.trim() === '' ? null : Number(e.target.value))} fullWidth />
            </Stack>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
              <TextField size="small" label="Wide penalty (runs)" type="number" value={show(rules.widePenalty)} onChange={(e) => set('widePenalty', num(e.target.value))} inputProps={{ min: 0, max: 5 }} fullWidth />
              <TextField size="small" label="No-ball penalty (runs)" type="number" value={show(rules.noBallPenalty)} onChange={(e) => set('noBallPenalty', num(e.target.value))} inputProps={{ min: 0, max: 5 }} fullWidth />
            </Stack>
            <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
              <FormControlLabel control={<Switch checked={rules.freeHitEnabled} onChange={(e) => set('freeHitEnabled', e.target.checked)} />} label="Free hit after a no-ball" />
              <FormControlLabel control={<Switch checked={rules.lastManStands} onChange={(e) => set('lastManStands', e.target.checked)} />} label="Last man stands" />
            </Stack>
          </Stack>
        </Paper>

        {error && <Alert severity="error" onClose={() => setError(null)} data-testid="match-error">{error}</Alert>}
        <Box><Button type="submit" variant="contained" disabled={!ready || create.isPending} data-testid="create-match">Create match</Button></Box>
      </Stack>
    </Box>
  );
};

export default NewMatchPage;
