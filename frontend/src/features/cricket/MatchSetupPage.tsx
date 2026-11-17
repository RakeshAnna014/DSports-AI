import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, MenuItem, Paper, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { cricketApi } from '@/api/cricket';
import { getApiError, getApiErrorMessage } from '@/api/errors';
import { ErrorState } from '@/components/common/States';
import type { Match, MatchSide, TossDecision } from '@/types/cricket';

/** Picks a playing XI from a squad: click a player to add them at the next batting position, click again to take them out. */
const XiPicker = ({ match, side, disabled, saving, onSave }: { match: Match; side: MatchSide; disabled: boolean; saving: boolean; onSave: (ids: string[]) => void }) => {
  const [order, setOrder] = useState<string[]>(side.playingXi.map((p) => p.playerId));
  useEffect(() => { setOrder(side.playingXi.map((p) => p.playerId)); }, [side.playingXi]);
  const max = match.rules.playersPerSide;
  const toggle = (id: string) => setOrder((o) => (o.includes(id) ? o.filter((x) => x !== id) : o.length < max ? [...o, id] : o));
  const saved = side.playingXi.map((p) => p.playerId).join(',') === order.join(',');
  return (
    <Paper variant="outlined" sx={{ p: 2 }} data-testid="xi-picker">
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="subtitle1" fontWeight={800}>{side.teamName}: playing XI</Typography>
        <Typography variant="caption" color="text.secondary" data-testid="xi-count">{order.length} of {max}</Typography>
      </Stack>
      {side.squad.length === 0 && <Typography variant="body2" color="text.secondary">This team has no players yet. Add players on the team page first.</Typography>}
      <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1}>
        {side.squad.map((p) => {
          const position = order.indexOf(p.playerId) + 1;
          return (
            <Chip key={p.playerId} label={position > 0 ? `${position}. ${p.name}` : p.name} color={position > 0 ? 'primary' : 'default'} variant={position > 0 ? 'filled' : 'outlined'}
              onClick={disabled ? undefined : () => toggle(p.playerId)} disabled={disabled} data-testid="xi-player" aria-pressed={position > 0} />
          );
        })}
      </Stack>
      {!disabled && (
        <Button size="small" variant="contained" sx={{ mt: 1.5 }} disabled={order.length < 2 || saved || saving} onClick={() => onSave(order)} data-testid="save-xi">Save XI</Button>
      )}
    </Paper>
  );
};

/**
 * Match setup after creation: the toss, each side's playing XI in batting order, and the scorers. The creator or a scorer records the toss and the XIs;
 * only the creator manages scorers. Scoring itself comes in the next step; the match shows "ready" once the toss and both XIs are in.
 */
const MatchSetupPage = () => {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [winner, setWinner] = useState('');
  const [decision, setDecision] = useState<TossDecision>('BAT');
  const [email, setEmail] = useState('');

  const match = useQuery({ queryKey: ['match', id], queryFn: () => cricketApi.matches.get(id) });
  const refresh = () => { void queryClient.invalidateQueries({ queryKey: ['match', id] }); void queryClient.invalidateQueries({ queryKey: ['my-matches'] }); };
  const fail = (fallback: string) => (err: unknown) => {
    const code = getApiError(err)?.code;
    setMessage(code === 'USER_NOT_REGISTERED' ? 'Ask them to register first. Nobody has registered with that email.' : getApiErrorMessage(err, fallback));
  };

  const toss = useMutation({ mutationFn: () => cricketApi.matches.toss(id, winner, decision), onSuccess: () => { setMessage(null); refresh(); }, onError: fail('The toss could not be recorded.') });
  const xi = useMutation({ mutationFn: ({ teamId, ids }: { teamId: string; ids: string[] }) => cricketApi.matches.setPlayingXi(id, teamId, ids), onSuccess: () => { setMessage(null); refresh(); }, onError: fail('The playing XI could not be saved.') });
  const addScorer = useMutation({ mutationFn: () => cricketApi.matches.addScorer(id, email.trim()), onSuccess: () => { setEmail(''); setMessage(null); refresh(); }, onError: fail('The scorer could not be added.') });
  const removeScorer = useMutation({ mutationFn: (userId: string) => cricketApi.matches.removeScorer(id, userId), onSuccess: refresh, onError: fail('The scorer could not be removed.') });

  if (match.isError) return <ErrorState body="We couldn't find that match." onRetry={() => void match.refetch()} />;
  const m = match.data;
  if (!m) return <Skeleton variant="rounded" height={200} />;
  const locked = m.status !== 'SCHEDULED' && m.status !== 'TOSS_DONE';
  const teamName = (teamId: string) => (teamId === m.teamA.teamId ? m.teamA.teamName : m.teamB.teamName);

  return (
    <Box>
      <Button size="small" onClick={() => navigate('/matches')} sx={{ mb: 1 }}>Back to my matches</Button>
      <Typography variant="h3" component="h1">{m.teamA.teamName} vs {m.teamB.teamName}</Typography>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ my: 1 }}>
        <Chip label={`${m.overs} overs`} />
        <Chip label={m.ballType === 'TENNIS' ? 'Tennis ball' : 'Leather ball'} />
        <Chip label={`${m.rules.playersPerSide} a side`} />
        {m.venue?.name && <Chip variant="outlined" label={m.venue.name} data-testid="match-venue" />}
        {m.city && <Chip variant="outlined" label={m.city} />}
        {m.scheduledAt && <Chip variant="outlined" label={new Date(m.scheduledAt).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })} />}
        <Chip color={m.readyToStart ? 'success' : 'default'} label={m.readyToStart ? 'Ready to start' : m.status === 'TOSS_DONE' ? 'Toss done' : 'Being set up'} data-testid="match-state" />
      </Stack>
      {m.umpires && <Typography variant="body2" color="text.secondary">Umpires: {m.umpires}</Typography>}
      {message && <Alert severity="warning" sx={{ my: 2 }} onClose={() => setMessage(null)} data-testid="setup-message">{message}</Alert>}
      {locked && <Alert severity="info" sx={{ my: 2 }}>This match has started, so its setup can no longer change.</Alert>}
      {!m.canScore && <Alert severity="info" sx={{ my: 2 }}>You can see this match because you have a team in it. Only its creator or scorer can change the setup.</Alert>}

      <Paper variant="outlined" sx={{ p: 2, my: 2 }}>
        <Typography variant="h6" sx={{ mb: 1 }}>Toss</Typography>
        {m.toss && <Typography variant="body1" data-testid="toss-result" sx={{ mb: 1 }}>{teamName(m.toss.winnerTeamId)} won the toss and chose to {m.toss.decision === 'BAT' ? 'bat' : 'bowl'}. {m.battingFirstTeamId ? `${teamName(m.battingFirstTeamId)} bat first.` : ''}</Typography>}
        {m.canScore && !locked && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" select label="Won the toss" value={winner} onChange={(e) => setWinner(e.target.value)} sx={{ minWidth: 200 }} data-testid="toss-winner">
              <MenuItem value={m.teamA.teamId}>{m.teamA.teamName}</MenuItem>
              <MenuItem value={m.teamB.teamId}>{m.teamB.teamName}</MenuItem>
            </TextField>
            <TextField size="small" select label="Chose to" value={decision} onChange={(e) => setDecision(e.target.value as TossDecision)} sx={{ minWidth: 140 }}>
              <MenuItem value="BAT">Bat</MenuItem>
              <MenuItem value="BOWL">Bowl</MenuItem>
            </TextField>
            <Button variant="contained" disabled={!winner || toss.isPending} onClick={() => toss.mutate()} data-testid="record-toss">{m.toss ? 'Change toss' : 'Record toss'}</Button>
          </Stack>
        )}
        {!m.toss && !m.canScore && <Typography variant="body2" color="text.secondary">No toss yet.</Typography>}
      </Paper>

      <Stack spacing={2} sx={{ mb: 2 }}>
        {[m.teamA, m.teamB].map((side) => (
          m.canScore
            ? <XiPicker key={side.teamId} match={m} side={side} disabled={locked} saving={xi.isPending} onSave={(ids) => xi.mutate({ teamId: side.teamId, ids })} />
            : (
              <Paper key={side.teamId} variant="outlined" sx={{ p: 2 }} data-testid="xi-view">
                <Typography variant="subtitle1" fontWeight={800}>{side.teamName}: playing XI</Typography>
                <Typography variant="body2" color="text.secondary">{side.playingXi.length === 0 ? 'Not chosen yet.' : side.playingXi.map((p) => `${p.battingOrder}. ${p.name}`).join(', ')}</Typography>
              </Paper>
            )
        ))}
      </Stack>

      {m.canScore && (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="h6" sx={{ mb: 1 }}>Scorers</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>You score this match{m.canManage ? ' as its creator' : ''}. {m.canManage ? 'Add other registered people by email.' : ''}</Typography>
          <Stack spacing={0.5} sx={{ mb: 1 }}>
            {m.scorers.map((s) => (
              <Stack key={s.userId} direction="row" justifyContent="space-between" alignItems="center" data-testid="scorer-row">
                <Typography variant="body2">{s.name} ({s.email})</Typography>
                {m.canManage && <Button size="small" color="error" onClick={() => removeScorer.mutate(s.userId)} data-testid="remove-scorer">Remove</Button>}
              </Stack>
            ))}
          </Stack>
          {m.canManage && (
            <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1.5} onSubmit={(e) => { e.preventDefault(); if (email.trim()) addScorer.mutate(); }}>
              <TextField size="small" label="Scorer's email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} sx={{ minWidth: 260 }} />
              <Button type="submit" variant="outlined" disabled={!email.trim() || addScorer.isPending} data-testid="add-scorer">Add scorer</Button>
            </Stack>
          )}
        </Paper>
      )}
    </Box>
  );
};

export default MatchSetupPage;
