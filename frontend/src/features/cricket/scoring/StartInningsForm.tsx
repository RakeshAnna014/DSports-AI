import { useState } from 'react';
import { Button, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import type { Match, MatchSide } from '@/types/cricket';

/** Opens an innings: the two openers of the batting side and the first bowler of the other. The side batting and the XIs come from the match. */
export const StartInningsForm = ({ match, battingTeamId, title, busy, onStart }: {
  match: Match; battingTeamId: string; title: string; busy: boolean; onStart: (striker: string, nonStriker: string, bowler: string) => void;
}) => {
  const batting: MatchSide = match.teamA.teamId === battingTeamId ? match.teamA : match.teamB;
  const bowling: MatchSide = match.teamA.teamId === battingTeamId ? match.teamB : match.teamA;
  const [striker, setStriker] = useState(batting.playingXi[0]?.playerId ?? '');
  const [nonStriker, setNonStriker] = useState(batting.playingXi[1]?.playerId ?? '');
  const [bowler, setBowler] = useState(bowling.playingXi[0]?.playerId ?? '');
  const valid = !!striker && !!nonStriker && striker !== nonStriker && !!bowler;

  return (
    <Paper variant="outlined" sx={{ p: 2 }} data-testid="start-innings">
      <Typography variant="h6" sx={{ mb: 1 }}>{title}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{batting.teamName} bat, {bowling.teamName} bowl.</Typography>
      <Stack spacing={2}>
        <TextField select size="small" label="Opening striker" value={striker} onChange={(e) => setStriker(e.target.value)}>
          {batting.playingXi.map((p) => <MenuItem key={p.playerId} value={p.playerId} disabled={p.playerId === nonStriker}>{p.name}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Opening non-striker" value={nonStriker} onChange={(e) => setNonStriker(e.target.value)}>
          {batting.playingXi.map((p) => <MenuItem key={p.playerId} value={p.playerId} disabled={p.playerId === striker}>{p.name}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Opening bowler" value={bowler} onChange={(e) => setBowler(e.target.value)}>
          {bowling.playingXi.map((p) => <MenuItem key={p.playerId} value={p.playerId}>{p.name}</MenuItem>)}
        </TextField>
        <Button variant="contained" size="large" disabled={!valid || busy} onClick={() => onStart(striker, nonStriker, bowler)} data-testid="start-innings-button">Start innings</Button>
      </Stack>
    </Paper>
  );
};
