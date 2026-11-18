import { useState } from 'react';
import { Box, Button, Dialog, DialogContent, DialogTitle, Stack, Typography } from '@mui/material';
import type { DeliveryBody, DeliveryKind } from '@/types/cricketScoring';

type Sheet = 'WIDE' | 'NO_BALL' | 'BYE' | 'LEG_BYE' | null;

const pad = { minHeight: 60, fontSize: '1.25rem', fontWeight: 800 };

/** A row of big, equal buttons. */
const Row = ({ children }: { children: React.ReactNode }) => (
  <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(64px, 1fr))', gap: 1 }}>{children}</Box>
);

/**
 * The scorer's buttons: runs, the four kinds of extra and the wicket. Fours and sixes are boundaries. A wide, a no-ball, a bye or a leg bye opens a sheet for its
 * runs. Every choice becomes one {@link DeliveryBody}; the server decides whether it is allowed.
 */
export const BallPad = ({ disabled, onDeliver, onWicket }: { disabled: boolean; onDeliver: (delivery: DeliveryBody) => void; onWicket: (kind: DeliveryKind) => void }) => {
  const [sheet, setSheet] = useState<Sheet>(null);
  const send = (delivery: DeliveryBody) => {
    setSheet(null);
    onDeliver(delivery);
  };
  const wicket = (kind: DeliveryKind) => {
    setSheet(null);
    onWicket(kind);
  };
  const run = (n: number) => send({ kind: 'NORMAL', runs: n, runsType: 'BAT', boundary: n === 4 || n === 6 });

  return (
    <Stack spacing={1} data-testid="ball-pad">
      <Row>{[0, 1, 2].map((n) => <Button key={n} variant="contained" color="inherit" sx={pad} disabled={disabled} onClick={() => run(n)} data-testid={`run-${n}`}>{n === 0 ? '•' : n}</Button>)}</Row>
      <Row>
        {[3, 4, 6].map((n) => <Button key={n} variant="contained" color={n === 3 ? 'inherit' : 'primary'} sx={pad} disabled={disabled} onClick={() => run(n)} data-testid={`run-${n}`}>{n}</Button>)}
      </Row>
      <Row>
        <Button variant="outlined" sx={pad} disabled={disabled} onClick={() => setSheet('WIDE')} data-testid="btn-wide">WD</Button>
        <Button variant="outlined" sx={pad} disabled={disabled} onClick={() => setSheet('NO_BALL')} data-testid="btn-noball">NB</Button>
        <Button variant="outlined" sx={pad} disabled={disabled} onClick={() => setSheet('BYE')} data-testid="btn-bye">B</Button>
        <Button variant="outlined" sx={pad} disabled={disabled} onClick={() => setSheet('LEG_BYE')} data-testid="btn-legbye">LB</Button>
      </Row>
      <Button variant="contained" color="error" sx={pad} disabled={disabled} onClick={() => onWicket('NORMAL')} data-testid="btn-wicket">W</Button>

      <Dialog open={sheet !== null} onClose={() => setSheet(null)} fullWidth maxWidth="xs">
        <DialogTitle>{sheet === 'WIDE' ? 'Wide' : sheet === 'NO_BALL' ? 'No ball' : sheet === 'BYE' ? 'Byes' : 'Leg byes'}</DialogTitle>
        <DialogContent>
          {sheet === 'WIDE' && (
            <Stack spacing={1.5}>
              <Typography variant="body2" color="text.secondary">Extra runs run on top of the wide:</Typography>
              <Row>{[0, 1, 2, 3, 4].map((n) => <Button key={n} variant="outlined" sx={pad} onClick={() => send({ kind: 'WIDE', runs: n, boundary: n === 4 })} data-testid={`wide-${n}`}>{n === 0 ? 'Wd' : `Wd+${n}`}</Button>)}</Row>
              <Button color="error" variant="outlined" onClick={() => wicket('WIDE')} data-testid="wide-wicket">Wicket on a wide</Button>
            </Stack>
          )}
          {sheet === 'NO_BALL' && (
            <Stack spacing={1.5}>
              <Typography variant="body2" color="text.secondary">Off the bat:</Typography>
              <Row>{[0, 1, 2, 3, 4, 6].map((n) => <Button key={n} variant="outlined" sx={pad} onClick={() => send({ kind: 'NO_BALL', runs: n, runsType: 'BAT', boundary: n === 4 || n === 6 })} data-testid={`nb-bat-${n}`}>{n}</Button>)}</Row>
              <Typography variant="body2" color="text.secondary">Byes on the no-ball:</Typography>
              <Row>{[1, 2, 3, 4].map((n) => <Button key={n} variant="outlined" sx={pad} onClick={() => send({ kind: 'NO_BALL', runs: n, runsType: 'BYE', boundary: n === 4 })} data-testid={`nb-bye-${n}`}>B{n}</Button>)}</Row>
              <Typography variant="body2" color="text.secondary">Leg byes on the no-ball:</Typography>
              <Row>{[1, 2, 3, 4].map((n) => <Button key={n} variant="outlined" sx={pad} onClick={() => send({ kind: 'NO_BALL', runs: n, runsType: 'LEG_BYE', boundary: n === 4 })} data-testid={`nb-legbye-${n}`}>Lb{n}</Button>)}</Row>
              <Button color="error" variant="outlined" onClick={() => wicket('NO_BALL')} data-testid="nb-wicket">Run out or obstruction on a no-ball</Button>
            </Stack>
          )}
          {(sheet === 'BYE' || sheet === 'LEG_BYE') && (
            <Row>{[1, 2, 3, 4].map((n) => (
              <Button key={n} variant="outlined" sx={pad} onClick={() => send({ kind: 'NORMAL', runs: n, runsType: sheet, boundary: n === 4 })} data-testid={`${sheet === 'BYE' ? 'bye' : 'legbye'}-${n}`}>{n}</Button>
            ))}</Row>
          )}
          <Button fullWidth sx={{ mt: 2 }} onClick={() => setSheet(null)}>Cancel</Button>
        </DialogContent>
      </Dialog>
    </Stack>
  );
};
