import { useState } from 'react';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import type { CreaseEnd, DeliveryBody, DeliveryKind, DismissalType, InningsView, ScoringView } from '@/types/cricketScoring';
import { defaultRunOutEnd, runOutPreview, type RunOutBatter } from './runOutEnd';

export const DISMISSAL_LABEL: Record<DismissalType, string> = {
  BOWLED: 'Bowled', CAUGHT: 'Caught', LBW: 'LBW', STUMPED: 'Stumped', HIT_WICKET: 'Hit wicket', RUN_OUT: 'Run out', OBSTRUCTING_FIELD: 'Obstructing the field', HIT_BALL_TWICE: 'Hit the ball twice',
};

const WITH_FIELDER: DismissalType[] = ['CAUGHT', 'STUMPED', 'RUN_OUT'];
const EITHER_BATTER: DismissalType[] = ['RUN_OUT', 'OBSTRUCTING_FIELD'];
const KEEPS_RUNS: DismissalType[] = ['RUN_OUT', 'OBSTRUCTING_FIELD', 'HIT_BALL_TWICE'];

/**
 * How was he out? Only the ways the server says the next ball allows are offered (a wide, a no-ball and a free hit shorten the list). A run out asks who, the runs
 * completed and at which end: the end starts from the runs (see {@link defaultRunOutEnd}) and the scorer can change it. Nothing is sent until "Confirm".
 */
export const WicketSheet = ({ view, innings, kind, onCancel, onConfirm }: { view: ScoringView; innings: InningsView; kind: DeliveryKind; onCancel: () => void; onConfirm: (delivery: DeliveryBody) => void }) => {
  const [type, setType] = useState<DismissalType | null>(null);
  const [who, setWho] = useState<RunOutBatter>('STRIKER');
  const [runs, setRuns] = useState(0);
  const [endOverride, setEndOverride] = useState<CreaseEnd | null>(null);
  const [fielder, setFielder] = useState('');

  const allowed = view.allowedDismissals[kind] ?? [];
  const striker = innings.striker;
  const nonStriker = innings.nonStriker;
  const out = who === 'STRIKER' ? striker : nonStriker;
  const partner = who === 'STRIKER' ? nonStriker : striker;
  const end = endOverride ?? defaultRunOutEnd(who, runs);
  const ballsInOver = Number(innings.overs.split('.')[1] ?? 0);
  const lastBallOfOver = kind === 'NORMAL' && ballsInOver === view.rules.ballsPerOver - 1;
  const fielders = view.bowlers.map((b) => b.player);

  const confirm = () => {
    if (!type) {
      return;
    }
    const either = EITHER_BATTER.includes(type);
    const dismissal: DeliveryBody['dismissal'] = {
      type,
      ...(either && out ? { batterOut: out.id } : {}),
      ...(type === 'RUN_OUT' ? { end } : {}),
      ...(WITH_FIELDER.includes(type) && fielder ? { fielderId: fielder } : {}),
    };
    onConfirm({ kind, runs: KEEPS_RUNS.includes(type) ? runs : 0, runsType: kind === 'WIDE' ? undefined : 'BAT', boundary: false, dismissal });
  };

  return (
    <Dialog open onClose={onCancel} fullWidth maxWidth="xs">
      <DialogTitle>{kind === 'WIDE' ? 'Wicket on a wide' : kind === 'NO_BALL' ? 'Wicket on a no-ball' : view.freeHit ? 'Wicket on a free hit' : 'How was he out?'}</DialogTitle>
      <DialogContent>
        {!type && (
          <Stack spacing={1}>
            {allowed.map((t) => <Button key={t} variant="outlined" size="large" onClick={() => setType(t)} data-testid={`how-${t}`}>{DISMISSAL_LABEL[t]}</Button>)}
            {allowed.length === 0 && <Alert severity="info">No dismissal is possible on this ball.</Alert>}
          </Stack>
        )}
        {type && (
          <Stack spacing={2} sx={{ pt: 0.5 }}>
            <Typography variant="subtitle1" fontWeight={800}>{DISMISSAL_LABEL[type]}</Typography>
            {EITHER_BATTER.includes(type) && (
              <ToggleButtonGroup exclusive size="small" value={who} onChange={(_, v: RunOutBatter | null) => { if (v) { setWho(v); setEndOverride(null); } }} aria-label="Who is out">
                <ToggleButton value="STRIKER" data-testid="who-striker">{striker?.name ?? 'Striker'} (striker)</ToggleButton>
                <ToggleButton value="NON_STRIKER" data-testid="who-nonstriker">{nonStriker?.name ?? 'Non-striker'}</ToggleButton>
              </ToggleButtonGroup>
            )}
            {KEEPS_RUNS.includes(type) && (
              <Stack direction="row" alignItems="center" spacing={1}>
                <Typography variant="body2">Runs completed</Typography>
                <Button size="small" variant="outlined" onClick={() => setRuns((r) => Math.max(0, r - 1))} aria-label="Fewer runs">-</Button>
                <Typography data-testid="runs-completed" sx={{ minWidth: 24, textAlign: 'center' }}>{runs}</Typography>
                <Button size="small" variant="outlined" onClick={() => setRuns((r) => Math.min(7, r + 1))} aria-label="More runs">+</Button>
              </Stack>
            )}
            {type === 'RUN_OUT' && (
              <Box>
                <Typography variant="body2" sx={{ mb: 0.5 }}>Out at</Typography>
                <ToggleButtonGroup exclusive size="small" value={end} onChange={(_, v: CreaseEnd | null) => { if (v) setEndOverride(v); }} aria-label="Out at which end">
                  <ToggleButton value="BATTING_END" data-testid="end-striker">Striker&apos;s end</ToggleButton>
                  <ToggleButton value="BOWLERS_END" data-testid="end-bowler">Bowler&apos;s end</ToggleButton>
                </ToggleButtonGroup>
                <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }} data-testid="end-hint">
                  {endOverride && endOverride !== defaultRunOutEnd(who, runs) ? 'You changed the end from the usual one for these runs.' : 'Set from the runs completed. Change it if it was not so.'}
                </Typography>
                <Typography variant="body2" sx={{ mt: 1 }} data-testid="run-out-preview">{runOutPreview(out?.name ?? 'The batter', partner?.name ?? null, end, lastBallOfOver)}</Typography>
              </Box>
            )}
            {WITH_FIELDER.includes(type) && (
              <TextField select size="small" label="Fielder (optional)" value={fielder} onChange={(e) => setFielder(e.target.value)} data-testid="fielder">
                <MenuItem value="">Not recorded</MenuItem>
                {fielders.map((f) => <MenuItem key={f.id} value={f.id}>{f.name}</MenuItem>)}
              </TextField>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {type && <Button onClick={() => setType(null)}>Back</Button>}
        <Button onClick={onCancel}>Cancel</Button>
        {type && <Button variant="contained" color="error" onClick={confirm} data-testid="confirm-wicket">Confirm</Button>}
      </DialogActions>
    </Dialog>
  );
};
