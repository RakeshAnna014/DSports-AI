import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, MenuItem, Paper, Skeleton, Stack, TextField, Typography,
} from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { cricketApi } from '@/api/cricket';
import { getApiErrorMessage } from '@/api/errors';
import { ErrorState } from '@/components/common/States';
import { ROLE_LABEL, type PlayingRole } from '@/types/cricket';

/** One team: its players (guests included), the captain, and what the viewer may change. The server decides who may edit; a member without rights only reads. */
const TeamDetailPage = () => {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [pickId, setPickId] = useState('');
  const [guestName, setGuestName] = useState('');
  const [guestPhone, setGuestPhone] = useState('');
  const [guestRole, setGuestRole] = useState<PlayingRole | ''>('');
  const [renaming, setRenaming] = useState(false);
  const [newName, setNewName] = useState('');
  const [newCity, setNewCity] = useState('');
  const [confirmArchive, setConfirmArchive] = useState(false);

  const team = useQuery({ queryKey: ['team', id], queryFn: () => cricketApi.teams.get(id) });
  const players = useQuery({ queryKey: ['my-players'], queryFn: cricketApi.players.mine, enabled: !!team.data?.canEdit });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ['team', id] }); void queryClient.invalidateQueries({ queryKey: ['my-teams'] }); void queryClient.invalidateQueries({ queryKey: ['my-players'] }); };
  const fail = (fallback: string) => (err: unknown) => setMessage(getApiErrorMessage(err, fallback));

  const add = useMutation({ mutationFn: (playerId: string) => cricketApi.teams.addMember(id, playerId), onSuccess: () => { setPickId(''); refresh(); }, onError: fail('That player could not be added.') });
  const addGuest = useMutation({
    mutationFn: async () => {
      const player = await cricketApi.players.createGuest({ name: guestName.trim(), phone: guestPhone.trim() || undefined, role: guestRole || undefined });
      return cricketApi.teams.addMember(id, player.id);
    },
    onSuccess: () => { setGuestName(''); setGuestPhone(''); setGuestRole(''); refresh(); },
    onError: fail('The player could not be added.'),
  });
  const remove = useMutation({ mutationFn: (playerId: string) => cricketApi.teams.removeMember(id, playerId), onSuccess: refresh, onError: fail('That player could not be removed.') });
  const captain = useMutation({ mutationFn: (playerId: string) => cricketApi.teams.setCaptain(id, playerId), onSuccess: refresh, onError: fail('The captain could not be changed.') });
  const rename = useMutation({
    mutationFn: () => cricketApi.teams.update(id, newName.trim(), newCity.trim() || undefined, team.data!.version),
    onSuccess: () => { setRenaming(false); refresh(); },
    onError: fail('The team could not be changed.'),
  });
  const archive = useMutation({ mutationFn: () => cricketApi.teams.archive(id), onSuccess: () => { setConfirmArchive(false); refresh(); }, onError: (err) => { setConfirmArchive(false); setMessage(getApiErrorMessage(err, 'The team could not be archived.')); } });

  if (team.isError) return <ErrorState body="We couldn't find that team." onRetry={() => void team.refetch()} />;
  const t = team.data;
  if (!t) return <Skeleton variant="rounded" height={160} />;
  const inTeam = new Set(t.members.map((m) => m.playerId));
  const addable = (players.data ?? []).filter((p) => !inTeam.has(p.id));
  const editable = t.canEdit && t.status === 'ACTIVE';

  return (
    <Box>
      <Button size="small" onClick={() => navigate('/teams')} sx={{ mb: 1 }}>Back to my teams</Button>
      <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" useFlexGap spacing={1}>
        <Box>
          <Typography variant="h3" component="h1">{t.name}</Typography>
          <Typography variant="body2" color="text.secondary">{t.city ?? 'No city'} · {t.members.length} player{t.members.length === 1 ? '' : 's'}{t.status === 'ARCHIVED' ? ' · archived' : ''}</Typography>
        </Box>
        {editable && (
          <Stack direction="row" spacing={1}>
            <Button size="small" onClick={() => { setNewName(t.name); setNewCity(t.city ?? ''); setRenaming(true); }} data-testid="rename-team">Rename</Button>
            <Button size="small" color="error" onClick={() => setConfirmArchive(true)} data-testid="archive-team">Archive</Button>
          </Stack>
        )}
      </Stack>
      {!t.canEdit && <Alert severity="info" sx={{ mt: 2 }}>You are a member of this team. Only its creator or captain can change it.</Alert>}
      {message && <Alert severity="warning" sx={{ mt: 2 }} onClose={() => setMessage(null)} data-testid="team-message">{message}</Alert>}

      <Paper variant="outlined" sx={{ p: 2, mt: 2 }}>
        <Typography variant="h6" sx={{ mb: 1 }}>Players</Typography>
        {t.members.length === 0 && <Typography variant="body2" color="text.secondary">No players yet.</Typography>}
        <Stack spacing={1}>
          {t.members.map((m) => (
            <Stack key={m.playerId} direction="row" justifyContent="space-between" alignItems="center" data-testid="team-member">
              <Box>
                <Typography variant="body1" fontWeight={700}>
                  {m.name} {m.captain && <Chip size="small" color="primary" label="Captain" data-testid="captain-chip" />} {m.linked && <Chip size="small" variant="outlined" label="Has an account" />}
                </Typography>
                <Typography variant="caption" color="text.secondary">{[m.role ? ROLE_LABEL[m.role] : null, m.phone].filter(Boolean).join(' · ') || 'No details'}</Typography>
              </Box>
              {editable && (
                <Stack direction="row" spacing={1}>
                  {!m.captain && <Button size="small" onClick={() => captain.mutate(m.playerId)} data-testid="make-captain">Make captain</Button>}
                  {!m.captain && <Button size="small" color="error" onClick={() => remove.mutate(m.playerId)} data-testid="remove-member">Remove</Button>}
                </Stack>
              )}
            </Stack>
          ))}
        </Stack>
      </Paper>

      {editable && (
        <Paper variant="outlined" sx={{ p: 2, mt: 2 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>Add a player</Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
            <TextField size="small" select label="From my players" value={pickId} onChange={(e) => setPickId(e.target.value)} sx={{ minWidth: 240 }} data-testid="pick-player"
              helperText={addable.length === 0 ? 'No other players yet: add a new one below.' : undefined}>
              {addable.map((p) => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
            </TextField>
            <Button variant="outlined" disabled={!pickId || add.isPending} onClick={() => add.mutate(pickId)} data-testid="add-existing">Add to team</Button>
          </Stack>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>Or a new player (no account needed)</Typography>
          <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} onSubmit={(e) => { e.preventDefault(); if (guestName.trim()) addGuest.mutate(); }}>
            <TextField size="small" label="Name" value={guestName} onChange={(e) => setGuestName(e.target.value)} inputProps={{ maxLength: 80 }} />
            <TextField size="small" label="Phone (optional)" value={guestPhone} onChange={(e) => setGuestPhone(e.target.value)} inputProps={{ maxLength: 20 }} helperText="Only you can see it" />
            <TextField size="small" select label="Role" value={guestRole} onChange={(e) => setGuestRole(e.target.value as PlayingRole)} sx={{ minWidth: 150 }}>
              {(Object.keys(ROLE_LABEL) as PlayingRole[]).map((r) => <MenuItem key={r} value={r}>{ROLE_LABEL[r]}</MenuItem>)}
            </TextField>
            <Button type="submit" variant="contained" disabled={!guestName.trim() || addGuest.isPending} data-testid="add-guest">Add new player</Button>
          </Stack>
        </Paper>
      )}

      <Dialog open={renaming} onClose={() => setRenaming(false)} fullWidth maxWidth="xs">
        <DialogTitle>Rename team</DialogTitle>
        <DialogContent>
          <Stack spacing={1.5} sx={{ pt: 1 }}>
            <TextField size="small" label="Team name" value={newName} onChange={(e) => setNewName(e.target.value)} inputProps={{ maxLength: 60 }} />
            <TextField size="small" label="City" value={newCity} onChange={(e) => setNewCity(e.target.value)} inputProps={{ maxLength: 80 }} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRenaming(false)}>Close</Button>
          <Button variant="contained" disabled={!newName.trim() || rename.isPending} onClick={() => rename.mutate()} data-testid="save-team">Save</Button>
        </DialogActions>
      </Dialog>
      <Dialog open={confirmArchive} onClose={() => setConfirmArchive(false)}>
        <DialogTitle>Archive this team?</DialogTitle>
        <DialogContent><DialogContentText>An archived team keeps its history but can no longer change.</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmArchive(false)}>Keep it</Button>
          <Button color="error" variant="contained" onClick={() => archive.mutate()} data-testid="confirm-archive">Archive</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default TeamDetailPage;
