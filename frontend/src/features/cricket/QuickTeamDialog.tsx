import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import { cricketApi } from '@/api/cricket';
import { getApiErrorMessage } from '@/api/errors';
import type { Team } from '@/types/cricket';

const MAX_PLAYERS = 30;

/** One name per line; blank lines and repeats (any case) are dropped, and the list is capped at the team limit. */
export const parseNames = (text: string): string[] => {
  const seen = new Set<string>();
  return text.split('\n').map((n) => n.trim().replace(/\s+/g, ' ')).filter((n) => {
    const key = n.toLowerCase();
    if (!n || seen.has(key)) return false;
    seen.add(key);
    return true;
  }).slice(0, MAX_PLAYERS);
};

/**
 * A quick team from the match wizard: a name and the players' names, nothing else. Each name becomes a guest player (no account needed) in the new team.
 * It is several calls (team, then each player), so if one fails we keep the team, say which names were missed and let the creator add them on the team page.
 */
export const QuickTeamDialog = ({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (team: Team) => void }) => {
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [names, setNames] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState(false);
  const players = parseNames(names);

  const create = useMutation({
    mutationFn: async () => {
      let team = await cricketApi.teams.create(name.trim());
      const missed: string[] = [];
      for (const playerName of players) {
        try {
          const player = await cricketApi.players.createGuest({ name: playerName });
          team = await cricketApi.teams.addMember(team.id, player.id);
        } catch {
          missed.push(playerName);
        }
      }
      return { team, missed };
    },
    onSuccess: ({ team, missed }) => {
      void queryClient.invalidateQueries({ queryKey: ['my-teams'] });
      setName(''); setNames('');
      if (missed.length > 0) {
        setCreated(true);
        setError(`${team.name} was created, but these players could not be added: ${missed.join(', ')}. Add them on the team page.`);
      } else {
        setError(null);
      }
      onCreated(team);
      if (missed.length === 0) onClose();
    },
    onError: (err) => setError(getApiErrorMessage(err, 'The team could not be created.')),
  });

  return (
    <Dialog open={open} onClose={() => { setCreated(false); setError(null); onClose(); }} fullWidth maxWidth="xs">
      <DialogTitle>New team</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <TextField size="small" label="Team name" value={name} onChange={(e) => setName(e.target.value)} inputProps={{ maxLength: 60 }} autoFocus />
          <TextField size="small" label="Players (one name per line)" value={names} onChange={(e) => setNames(e.target.value)} multiline minRows={5}
            helperText="Names only. They do not need an account." />
          <Typography variant="caption" color="text.secondary" data-testid="quick-team-count">{players.length} player{players.length === 1 ? '' : 's'}{players.length === MAX_PLAYERS ? ' (the most a team can have)' : ''}</Typography>
          {error && <Alert severity="warning" data-testid="quick-team-error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => { setCreated(false); setError(null); onClose(); }}>{error ? 'Close' : 'Cancel'}</Button>
        <Button variant="contained" disabled={!name.trim() || create.isPending || created} onClick={() => { setError(null); create.mutate(); }} data-testid="quick-team-create">Create team</Button>
      </DialogActions>
    </Dialog>
  );
};
