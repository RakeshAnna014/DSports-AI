import { useState } from 'react';
import { Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import type { CommandBody, DeliveryBody, InningsView, ScoringView } from '@/types/cricketScoring';
import { ChooserDialog } from './ChooserDialog';

type Mode = 'menu' | 'retire' | 'retire-type' | 'bowler' | 'target' | 'declare' | 'end';

/**
 * The rarer things a scorer does: retire a batter (hurt or out), replace an injured bowler, ran fours and other odd runs, declare, call the match off, revise the
 * target after rain. Each ends in one command for the outbox. Anything that ends the innings or the match asks first.
 */
export const MoreDialog = ({ view, innings, onSend, onClose }: { view: ScoringView; innings: InningsView; onSend: (command: CommandBody, label: string) => void; onClose: () => void }) => {
  const [mode, setMode] = useState<Mode>('menu');
  const [retiring, setRetiring] = useState<string | null>(null);
  const [target, setTarget] = useState(String(innings.target ?? ''));
  const send = (command: CommandBody, label: string) => {
    onClose();
    onSend(command, label);
  };
  const run = (delivery: DeliveryBody, label: string) => send({ type: 'DELIVER', delivery }, label);

  if (mode === 'retire') {
    const atCrease = [innings.striker, innings.nonStriker].filter((p): p is NonNullable<typeof p> => p !== null);
    return <ChooserDialog title="Who is retiring?" choices={atCrease.map((p) => ({ id: p.id, label: p.name }))} onChoose={(id) => { setRetiring(id); setMode('retire-type'); }} onClose={onClose} />;
  }
  if (mode === 'retire-type' && retiring) {
    const name = [innings.striker, innings.nonStriker].find((p) => p?.id === retiring)?.name ?? 'The batter';
    return (
      <Dialog open fullWidth maxWidth="xs" onClose={onClose}>
        <DialogTitle>{name} retires</DialogTitle>
        <DialogContent>
          <Stack spacing={1}>
            <Button variant="outlined" onClick={() => send({ type: 'RETIRE_BATTER', batterId: retiring, retirement: 'HURT' }, `${name} retired hurt`)} data-testid="retire-hurt">Retired hurt (not a wicket, may return)</Button>
            <Button variant="outlined" color="error" onClick={() => send({ type: 'RETIRE_BATTER', batterId: retiring, retirement: 'OUT' }, `${name} retired out`)} data-testid="retire-out">Retired out (counts as a wicket)</Button>
          </Stack>
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button></DialogActions>
      </Dialog>
    );
  }
  if (mode === 'bowler') {
    return <ChooserDialog title="Who takes over the over?" notice="The new bowler bowls the rest of this over. The over counts for whoever bowled most of it."
      choices={view.bowlers.filter((b) => b.canReplaceMidOver).map((b) => ({ id: b.player.id, label: b.player.name, detail: `${b.overs} ov` }))}
      onChoose={(id) => send({ type: 'CHANGE_BOWLER', bowlerId: id }, 'Bowler changed')} onClose={onClose} />;
  }
  if (mode === 'target') {
    return (
      <Dialog open fullWidth maxWidth="xs" onClose={onClose}>
        <DialogTitle>Revised target</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1 }}>The number is yours (for example after rain). It is not calculated here.</Typography>
          <TextField fullWidth size="small" type="number" label="New target" value={target} onChange={(e) => setTarget(e.target.value)} inputProps={{ min: 1 }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="contained" disabled={!(Number(target) >= 1)} onClick={() => send({ type: 'REVISE_TARGET', target: Number(target) }, `Target ${target}`)} data-testid="confirm-target">Set target</Button>
        </DialogActions>
      </Dialog>
    );
  }
  if (mode === 'declare') {
    return (
      <Dialog open fullWidth maxWidth="xs" onClose={onClose}>
        <DialogTitle>Declare the innings?</DialogTitle>
        <DialogContent><Typography>The innings ends at {innings.runs}/{innings.wickets}. You can undo this for 24 hours after the match is finished.</Typography></DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => send({ type: 'END_INNINGS', reason: 'DECLARED' }, 'Innings declared')} data-testid="confirm-declare">Declare</Button></DialogActions>
      </Dialog>
    );
  }
  if (mode === 'end') {
    return (
      <Dialog open fullWidth maxWidth="xs" onClose={onClose}>
        <DialogTitle>Call the match off?</DialogTitle>
        <DialogContent>
          <Stack spacing={1}>
            <Button variant="outlined" color="error" onClick={() => send({ type: 'END_MATCH', reason: 'ABANDONED' }, 'Match abandoned')} data-testid="confirm-abandon">Abandoned</Button>
            <Button variant="outlined" color="error" onClick={() => send({ type: 'END_MATCH', reason: 'NO_RESULT' }, 'No result')} data-testid="confirm-no-result">No result</Button>
          </Stack>
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button></DialogActions>
      </Dialog>
    );
  }
  const canReplace = view.bowlers.some((b) => b.canReplaceMidOver);
  return (
    <Dialog open fullWidth maxWidth="xs" onClose={onClose}>
      <DialogTitle>More</DialogTitle>
      <DialogContent>
        <Stack spacing={1} data-testid="more-menu">
          <Button variant="outlined" onClick={() => run({ kind: 'NORMAL', runs: 4, runsType: 'BAT', boundary: false }, '4 runs run')} data-testid="more-ran-4">Ran 4 (not a boundary)</Button>
          <Button variant="outlined" onClick={() => run({ kind: 'NORMAL', runs: 5, runsType: 'BAT', boundary: false }, '5 runs')} data-testid="more-5">5 runs</Button>
          <Button variant="outlined" onClick={() => setMode('retire')} data-testid="more-retire">Retire a batter</Button>
          <Button variant="outlined" disabled={!canReplace} onClick={() => setMode('bowler')} data-testid="more-bowler">Bowler injured: change bowler</Button>
          {innings.target != null && <Button variant="outlined" onClick={() => setMode('target')} data-testid="more-target">Revise the target</Button>}
          <Button variant="outlined" onClick={() => setMode('declare')} data-testid="more-declare">Declare the innings</Button>
          <Button variant="outlined" color="error" onClick={() => setMode('end')} data-testid="more-end">Call the match off</Button>
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  );
};
