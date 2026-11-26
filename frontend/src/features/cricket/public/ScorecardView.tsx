import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { ExpandMore } from '@mui/icons-material';
import { PlayerLink } from './PlayerLink';
import type { PublicInnings, PublicScorecard } from '@/types/cricketPublic';

export const rate = (value: number | null): string => (value == null ? '–' : value.toFixed(2));

export const STATUS_LABEL: Record<string, string> = {
  IN_PROGRESS: 'Live', INNINGS_BREAK: 'Innings break', COMPLETED: 'Result', ABANDONED: 'Abandoned', NO_RESULT: 'No result', TOSS_DONE: 'Starting soon',
};

/** The big line: the batting side's score and overs, the run rate and, in a chase, what is needed. */
export const Headline = ({ card, innings }: { card: PublicScorecard; innings: PublicInnings }) => (
  <Paper variant="outlined" sx={{ p: 2 }} data-testid="public-headline">
    <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
      <Typography variant="h4" component="div" data-testid="public-score">{innings.battingTeam} {innings.runs}/{innings.wickets}</Typography>
      <Typography variant="body1" color="text.secondary" data-testid="public-overs">{innings.overs} / {card.overs} ov</Typography>
    </Stack>
    <Typography variant="body2" color="text.secondary" data-testid="public-run-rate">Run rate {rate(innings.runRate)}</Typography>
    {innings.target != null && innings.status === 'IN_PROGRESS' && (
      <Typography variant="body1" sx={{ mt: 1, fontWeight: 600 }} data-testid="public-chase">
        Target {innings.target} · need {innings.runsNeeded} off {innings.ballsRemaining} ball{innings.ballsRemaining === 1 ? '' : 's'} · required rate {rate(innings.requiredRate)}
      </Typography>
    )}
    {innings.target != null && innings.status !== 'IN_PROGRESS' && (
      <Typography variant="body2" sx={{ mt: 1 }} data-testid="public-chase">Target was {innings.target}.</Typography>
    )}
  </Paper>
);

const InningsDetails = ({ innings }: { innings: PublicInnings }) => (
  <Box sx={{ overflowX: 'auto' }}>
    <Table size="small" aria-label={`${innings.battingTeam} batting`}>
      <TableHead><TableRow><TableCell>Batter</TableCell><TableCell align="right">R</TableCell><TableCell align="right">B</TableCell><TableCell align="right">4s</TableCell><TableCell align="right">6s</TableCell></TableRow></TableHead>
      <TableBody>
        {innings.batters.map((b, i) => (
          <TableRow key={`${b.name}-${i}`} data-testid="public-batter" selected={b.atCrease}>
            <TableCell sx={{ fontWeight: b.striker ? 800 : 400 }}>
              <PlayerLink id={b.playerId} name={b.name} />{b.striker ? ' *' : ''}
              <Typography variant="caption" display="block" color="text.secondary">{b.howOut}</Typography>
            </TableCell>
            <TableCell align="right">{b.runs}</TableCell><TableCell align="right">{b.balls}</TableCell><TableCell align="right">{b.fours}</TableCell><TableCell align="right">{b.sixes}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
    <Typography variant="body2" sx={{ my: 1 }} data-testid="public-extras">
      Extras {innings.extras.total} (b {innings.extras.byes}, lb {innings.extras.legByes}, wd {innings.extras.wides}, nb {innings.extras.noBalls})
    </Typography>
    {innings.yetToBat.length > 0 && <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }} data-testid="public-yet-to-bat">Yet to bat: {innings.yetToBat.join(', ')}</Typography>}
    <Table size="small" aria-label={`${innings.bowlingTeam} bowling`}>
      <TableHead><TableRow><TableCell>Bowler</TableCell><TableCell align="right">O</TableCell><TableCell align="right">M</TableCell><TableCell align="right">R</TableCell><TableCell align="right">W</TableCell><TableCell align="right">Econ</TableCell></TableRow></TableHead>
      <TableBody>
        {innings.bowlers.map((b, i) => (
          <TableRow key={`${b.name}-${i}`} data-testid="public-bowler" selected={b.bowling}>
            <TableCell><PlayerLink id={b.playerId} name={b.name} /></TableCell><TableCell align="right">{b.overs}</TableCell><TableCell align="right">{b.maidens}</TableCell><TableCell align="right">{b.runs}</TableCell>
            <TableCell align="right">{b.wickets}</TableCell><TableCell align="right">{rate(b.economy)}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
    {innings.fallOfWickets.length > 0 && (
      <Typography variant="body2" sx={{ mt: 1 }} data-testid="public-fall">
        Fall of wickets: {innings.fallOfWickets.map((f) => `${f.number}-${f.runs} (${f.batter}, ${f.overs} ov)`).join(', ')}
      </Typography>
    )}
    {innings.waivers.length > 0 && <Alert severity="info" sx={{ mt: 1 }} data-testid="public-waivers">{innings.waivers.map((w) => w.text).join(' ')}</Alert>}
  </Box>
);

/** One innings as a collapsible block; the newest is open. */
export const InningsBlock = ({ innings, open }: { innings: PublicInnings; open: boolean }) => (
  <Accordion variant="outlined" disableGutters defaultExpanded={open} data-testid="public-innings">
    <AccordionSummary expandIcon={<ExpandMore />}>
      <Typography fontWeight={700}>{innings.battingTeam}: {innings.runs}/{innings.wickets} ({innings.overs} ov)</Typography>
    </AccordionSummary>
    <AccordionDetails><InningsDetails innings={innings} /></AccordionDetails>
  </Accordion>
);
