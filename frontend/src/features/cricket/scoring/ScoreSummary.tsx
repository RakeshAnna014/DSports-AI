import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { ExpandMore } from '@mui/icons-material';
import type { InningsView, ScoringView } from '@/types/cricketScoring';

/** The live line: the score, the chase, the two batters, the bowler and the balls of the over. */
export const ScoreSummary = ({ view, innings }: { view: ScoringView; innings: InningsView }) => {
  const atCrease = innings.batters.filter((b) => b.atCrease);
  return (
    <Paper variant="outlined" sx={{ p: 2 }} data-testid="score-summary">
      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
        <Typography variant="h4" component="div" data-testid="score">{innings.battingTeam.name} {innings.runs}/{innings.wickets}</Typography>
        <Typography variant="body1" color="text.secondary" data-testid="overs">{innings.overs} ov</Typography>
      </Stack>
      {innings.target != null && (
        <Typography variant="body2" sx={{ mb: 1 }} data-testid="chase">
          Target {innings.target}. Need {innings.runsNeeded} off {innings.ballsRemaining} ball{innings.ballsRemaining === 1 ? '' : 's'}.
        </Typography>
      )}
      {view.freeHit && <Alert severity="warning" icon={false} sx={{ my: 1, py: 0 }} data-testid="free-hit">FREE HIT</Alert>}
      <Box sx={{ mt: 1 }}>
        {atCrease.map((b) => (
          <Stack key={b.player.id} direction="row" justifyContent="space-between" data-testid="at-crease">
            <Typography variant="body1" fontWeight={innings.striker?.id === b.player.id ? 800 : 400}>{b.player.name}{innings.striker?.id === b.player.id ? ' *' : ''}</Typography>
            <Typography variant="body1">{b.runs} ({b.balls})</Typography>
          </Stack>
        ))}
        {innings.bowler && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }} data-testid="bowler">
            {innings.bowler.name} {innings.bowlers.find((b) => b.player.id === innings.bowler?.id)?.overs}-{innings.bowlers.find((b) => b.player.id === innings.bowler?.id)?.maidens}-
            {innings.bowlers.find((b) => b.player.id === innings.bowler?.id)?.runs}-{innings.bowlers.find((b) => b.player.id === innings.bowler?.id)?.wickets}
          </Typography>
        )}
      </Box>
      <Stack direction="row" spacing={0.75} sx={{ mt: 1, minHeight: 32 }} flexWrap="wrap" useFlexGap data-testid="this-over">
        {innings.thisOver.map((ball, i) => <Chip key={i} size="small" label={ball} color={ball.includes('W') && !ball.startsWith('Wd') ? 'error' : 'default'} />)}
      </Stack>
      {innings.waivers.length > 0 && <Alert severity="info" sx={{ mt: 1 }} data-testid="waivers">{innings.waivers.map((w) => w.text).join(' ')}</Alert>}
    </Paper>
  );
};

/** The full scorecard of one innings: batters with how they were out, extras, bowlers, fall of wickets. */
export const InningsCard = ({ innings }: { innings: InningsView }) => (
  <Accordion variant="outlined" disableGutters data-testid="innings-card">
    <AccordionSummary expandIcon={<ExpandMore />}>
      <Typography fontWeight={700}>{innings.battingTeam.name}: {innings.runs}/{innings.wickets} ({innings.overs} ov)</Typography>
    </AccordionSummary>
    <AccordionDetails sx={{ overflowX: 'auto' }}>
      <Table size="small">
        <TableHead><TableRow><TableCell>Batter</TableCell><TableCell align="right">R</TableCell><TableCell align="right">B</TableCell><TableCell align="right">4s</TableCell><TableCell align="right">6s</TableCell></TableRow></TableHead>
        <TableBody>
          {innings.batters.map((b) => (
            <TableRow key={b.player.id}>
              <TableCell>{b.player.name}<Typography variant="caption" display="block" color="text.secondary">{b.howOut}</Typography></TableCell>
              <TableCell align="right">{b.runs}</TableCell><TableCell align="right">{b.balls}</TableCell><TableCell align="right">{b.fours}</TableCell><TableCell align="right">{b.sixes}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      <Typography variant="body2" sx={{ my: 1 }}>Extras {innings.extras.total} (b {innings.extras.byes}, lb {innings.extras.legByes}, wd {innings.extras.wides}, nb {innings.extras.noBalls})</Typography>
      <Table size="small">
        <TableHead><TableRow><TableCell>Bowler</TableCell><TableCell align="right">O</TableCell><TableCell align="right">M</TableCell><TableCell align="right">R</TableCell><TableCell align="right">W</TableCell></TableRow></TableHead>
        <TableBody>
          {innings.bowlers.map((b) => (
            <TableRow key={b.player.id}><TableCell>{b.player.name}</TableCell><TableCell align="right">{b.overs}</TableCell><TableCell align="right">{b.maidens}</TableCell><TableCell align="right">{b.runs}</TableCell><TableCell align="right">{b.wickets}</TableCell></TableRow>
          ))}
        </TableBody>
      </Table>
      {innings.fallOfWickets.length > 0 && (
        <Typography variant="body2" sx={{ mt: 1 }}>Fall of wickets: {innings.fallOfWickets.map((f) => `${f.number}-${f.runs} (${f.batter.name}, ${f.overs})`).join(', ')}</Typography>
      )}
      {innings.waivers.length > 0 && <Alert severity="info" sx={{ mt: 1 }}>{innings.waivers.map((w) => w.text).join(' ')}</Alert>}
    </AccordionDetails>
  </Accordion>
);
