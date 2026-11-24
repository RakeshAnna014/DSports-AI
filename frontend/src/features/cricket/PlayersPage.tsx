import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { Person } from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import { cricketApi } from '@/api/cricket';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { useAuthStore } from '@/store/authStore';
import { EmptyState, ErrorState } from '@/components/common/States';
import { ROLE_LABEL, type PlayingRole } from '@/types/cricket';

const CLAIM_TEXT: Record<string, string> = {
  PENDING: 'Waiting for the person who added them to agree',
  APPROVED: 'Approved: this profile is now yours',
  REJECTED: 'Not approved',
};

/**
 * My players: guests I added (no account needed), my own profile, and the claims that link a guest to a real account. A phone number only helps to find a
 * profile; it is never proof, so a claim always waits for the person who created the guest to approve it.
 */
const PlayersPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [role, setRole] = useState<PlayingRole | ''>('');
  const [ownName, setOwnName] = useState('');
  const [lookup, setLookup] = useState('');
  const [message, setMessage] = useState<{ severity: 'success' | 'info' | 'warning' | 'error'; text: string } | null>(null);

  const players = useQuery({ queryKey: ['my-players'], queryFn: cricketApi.players.mine, enabled: isAuthenticated });
  const incoming = useQuery({ queryKey: ['claims-incoming'], queryFn: cricketApi.players.incomingClaims, enabled: isAuthenticated });
  const myClaims = useQuery({ queryKey: ['claims-mine'], queryFn: cricketApi.players.myClaims, enabled: isAuthenticated });
  const found = useMutation({ mutationFn: () => cricketApi.players.claimable(lookup.trim()), onError: (err) => setMessage({ severity: 'warning', text: getApiErrorMessage(err, 'Enter a valid phone number.') }) });
  const refresh = () => { for (const key of ['my-players', 'claims-incoming', 'claims-mine', 'my-teams']) void queryClient.invalidateQueries({ queryKey: [key] }); };

  const addGuest = useMutation({
    mutationFn: () => cricketApi.players.createGuest({ name: name.trim(), phone: phone.trim() || undefined, role: role || undefined }),
    onSuccess: () => { setName(''); setPhone(''); setRole(''); setMessage(null); refresh(); },
    onError: (err) => setMessage({ severity: 'error', text: getApiError(err)?.code === 'DUPLICATE_PLAYER_PHONE' ? 'You already have a player with that phone number.' : getApiErrorMessage(err, 'The player could not be added.') }),
  });
  const createOwn = useMutation({
    mutationFn: () => cricketApi.players.createOwn({ name: ownName.trim() }),
    onSuccess: () => { setOwnName(''); refresh(); },
    onError: (err) => setMessage({ severity: 'error', text: getApiErrorMessage(err, 'Your profile could not be created.') }),
  });
  const claim = useMutation({
    mutationFn: (playerId: string) => cricketApi.players.claim(playerId),
    onSuccess: () => { setMessage({ severity: 'success', text: 'Asked. The person who added this player has to approve it.' }); found.reset(); setLookup(''); refresh(); },
    onError: (err) => {
      const code = getApiError(err)?.code;
      setMessage({ severity: 'warning', text: code === 'CLAIMANT_ALREADY_LINKED' ? 'You already have a player profile, and two profiles cannot be merged yet.'
        : code === 'CLAIM_ALREADY_PENDING' ? 'You have already asked for this profile.' : getApiErrorMessage(err, 'The claim could not be sent.') });
    },
  });
  const publicProfile = useMutation({
    mutationFn: (visible: boolean) => cricketApi.players.setPublicProfile(visible),
    onSuccess: () => refresh(),
    onError: (err) => setMessage({ severity: 'error', text: getApiErrorMessage(err, 'The setting could not be saved.') }),
  });
  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) => (approve ? cricketApi.players.approveClaim(id) : cricketApi.players.rejectClaim(id)),
    onSuccess: (c) => { setMessage({ severity: 'success', text: c.status === 'APPROVED' ? 'Approved: the profile now belongs to that account. Its career statistics now have a public page; the player can hide it from their profile.' : 'Rejected.' }); refresh(); },
    onError: (err) => setMessage({ severity: 'error', text: getApiErrorMessage(err, 'The claim could not be decided.') }),
  });

  if (!isAuthenticated) {
    return <EmptyState icon={<Person sx={{ fontSize: 56 }} />} title="Sign in to manage players" body="Players belong to your account." action={<Button variant="contained" onClick={() => navigate('/login')}>Sign in</Button>} />;
  }
  const own = players.data?.find((p) => p.mine);
  const others = players.data?.filter((p) => !p.mine) ?? [];
  return (
    <Box>
      <Typography variant="h3" component="h1">Players</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Add the people you play with. They do not need an account; if they sign up later they can ask to claim their profile.</Typography>
      {message && <Alert severity={message.severity} sx={{ mb: 2 }} onClose={() => setMessage(null)} data-testid="players-message">{message.text}</Alert>}

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Typography variant="h6" sx={{ mb: 1 }}>My profile</Typography>
        {own ? (
          <>
            <Typography variant="body1" data-testid="own-profile">{own.name} <Chip size="small" color="primary" label="You" /></Typography>
            <FormControlLabel sx={{ display: 'block', mt: 0.5 }} label="Public statistics page"
              control={<Switch checked={own.publicProfile} disabled={publicProfile.isPending} onChange={(e) => publicProfile.mutate(e.target.checked)} inputProps={{ 'aria-label': 'Public statistics page' }} data-testid="public-profile-toggle" />} />
            <Typography variant="caption" color="text.secondary" display="block" data-testid="public-profile-note">
              {own.publicProfile ? 'Anyone can see your career numbers, and your name on a scorecard links to them. Turn this off to show your name only.' : 'Your career page is hidden. Your name still appears on scorecards of matches you play, without a link.'}
            </Typography>
          </>
        ) : (
          <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} onSubmit={(e) => { e.preventDefault(); if (ownName.trim()) createOwn.mutate(); }}>
            <TextField size="small" label="Your player name" value={ownName} onChange={(e) => setOwnName(e.target.value)} inputProps={{ maxLength: 80 }} />
            <Button type="submit" variant="outlined" disabled={!ownName.trim() || createOwn.isPending} data-testid="create-own">Create my profile</Button>
          </Stack>
        )}
      </Paper>

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Typography variant="h6" sx={{ mb: 1 }}>Add a player</Typography>
        <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} onSubmit={(e) => { e.preventDefault(); if (name.trim()) addGuest.mutate(); }}>
          <TextField size="small" label="Name" value={name} onChange={(e) => setName(e.target.value)} inputProps={{ maxLength: 80 }} />
          <TextField size="small" label="Phone (optional)" value={phone} onChange={(e) => setPhone(e.target.value)} inputProps={{ maxLength: 20 }} helperText="Only you can see it" />
          <TextField size="small" select label="Role" value={role} onChange={(e) => setRole(e.target.value as PlayingRole)} sx={{ minWidth: 150 }}>
            {(Object.keys(ROLE_LABEL) as PlayingRole[]).map((r) => <MenuItem key={r} value={r}>{ROLE_LABEL[r]}</MenuItem>)}
          </TextField>
          <Button type="submit" variant="contained" disabled={!name.trim() || addGuest.isPending} data-testid="add-player">Add player</Button>
        </Stack>
      </Paper>

      {players.isError && <ErrorState body="We couldn't load your players." onRetry={() => void players.refetch()} />}
      <Stack spacing={1} sx={{ mb: 3 }}>
        {others.length === 0 && players.data && <Typography variant="body2" color="text.secondary">No players added yet.</Typography>}
        {others.map((p) => (
          <Paper key={p.id} variant="outlined" sx={{ p: 1.5 }} data-testid="player-row">
            <Typography variant="body1" fontWeight={700}>{p.name} {p.linked && <Chip size="small" variant="outlined" label="Has an account" />}</Typography>
            <Typography variant="caption" color="text.secondary">{[p.role ? ROLE_LABEL[p.role] : null, p.phone].filter(Boolean).join(' · ') || 'No details'}</Typography>
          </Paper>
        ))}
      </Stack>

      {(incoming.data?.length ?? 0) > 0 && (
        <Paper variant="outlined" sx={{ p: 2, mb: 2 }} data-testid="incoming-claims">
          <Typography variant="h6" sx={{ mb: 1 }}>Waiting for your decision</Typography>
          <Stack spacing={1}>
            {incoming.data!.map((c) => (
              <Stack key={c.id} direction="row" justifyContent="space-between" alignItems="center" data-testid="incoming-claim">
                <Box>
                  <Typography variant="body2"><b>{c.claimantName}</b> ({c.claimantEmail}) says they are <b>{c.playerName}</b>.</Typography>
                  {c.notice && <Typography variant="caption" color="text.secondary" data-testid="claim-notice">{c.notice}</Typography>}
                </Box>
                <Stack direction="row" spacing={1}>
                  <Button size="small" variant="contained" onClick={() => decide.mutate({ id: c.id, approve: true })} data-testid="approve-claim">Approve</Button>
                  <Button size="small" color="error" onClick={() => decide.mutate({ id: c.id, approve: false })} data-testid="reject-claim">Reject</Button>
                </Stack>
              </Stack>
            ))}
          </Stack>
        </Paper>
      )}

      {!own && (
        <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
          <Typography variant="h6" sx={{ mb: 0.5 }}>Did someone already add you?</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Search by the phone number they used. You will only see a shortened name, and the person who added you must approve.</Typography>
          <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} onSubmit={(e) => { e.preventDefault(); if (lookup.trim()) found.mutate(); }}>
            <TextField size="small" label="Your phone number" value={lookup} onChange={(e) => setLookup(e.target.value)} inputProps={{ maxLength: 20 }} />
            <Button type="submit" variant="outlined" disabled={!lookup.trim() || found.isPending} data-testid="find-claimable">Search</Button>
          </Stack>
          {found.data?.length === 0 && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }} data-testid="no-claimable">Nobody has added that number.</Typography>}
          <Stack spacing={1} sx={{ mt: 1 }}>
            {found.data?.map((c) => (
              <Stack key={c.playerId} direction="row" justifyContent="space-between" alignItems="center" data-testid="claimable-row">
                <Typography variant="body1">{c.displayName}</Typography>
                <Button size="small" variant="contained" onClick={() => claim.mutate(c.playerId)} disabled={claim.isPending} data-testid="claim-player">This is me</Button>
              </Stack>
            ))}
          </Stack>
        </Paper>
      )}

      {(myClaims.data?.length ?? 0) > 0 && (
        <Paper variant="outlined" sx={{ p: 2 }} data-testid="my-claims">
          <Typography variant="h6" sx={{ mb: 1 }}>My claims</Typography>
          <Stack spacing={0.5}>
            {myClaims.data!.map((c) => (
              <Box key={c.id}>
                <Typography variant="body2" color="text.secondary">{c.playerName}: {CLAIM_TEXT[c.status]}</Typography>
                {c.notice && <Typography variant="caption" color="text.secondary" data-testid="my-claim-notice">{c.notice}</Typography>}
              </Box>
            ))}
          </Stack>
        </Paper>
      )}
    </Box>
  );
};

export default PlayersPage;
