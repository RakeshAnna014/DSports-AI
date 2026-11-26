import { useEffect, useState } from 'react';
import { Alert, Box, Button, Paper, Skeleton, Stack, Table, TableBody, TableCell, TableRow, Tab, Tabs, Typography } from '@mui/material';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { cricketPublicApi, isNotFound } from '@/api/cricketPublic';
import type { PublicCareerLine, PublicPlayerStats } from '@/types/cricketPublic';

const BALL_LABEL: Record<PublicCareerLine['ballType'], string> = { LEATHER: 'Leather ball', TENNIS: 'Tennis ball' };

/** A figure the server could not work out is a dash, never a zero. */
const figure = (value: number | null): string => (value == null ? '–' : value.toFixed(2));

const Row = ({ label, value, id }: { label: string; value: string | number; id: string }) => (
  <TableRow>
    <TableCell component="th" scope="row" sx={{ color: 'text.secondary' }}>{label}</TableCell>
    <TableCell align="right" data-testid={id}>{value}</TableCell>
  </TableRow>
);

/** The batting, bowling and fielding of one ball type. Everything is read from the server's line; nothing is worked out here. */
export const CareerTables = ({ line }: { line: PublicCareerLine }) => {
  const b = line.batting;
  const w = line.bowling;
  return (
    <Stack spacing={2} data-testid={`career-${line.ballType.toLowerCase()}`}>
      <Typography variant="subtitle2">{line.matches} {line.matches === 1 ? 'match' : 'matches'}</Typography>
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small" aria-label="Batting">
          <TableBody>
            <Row id="bat-innings" label="Innings" value={b.innings} />
            <Row id="bat-runs" label="Runs" value={b.runs} />
            <Row id="bat-highest" label="Highest score" value={b.innings === 0 ? '–' : `${b.highest}${b.highestNotOut ? '*' : ''}`} />
            <Row id="bat-average" label="Average" value={figure(b.average)} />
            <Row id="bat-sr" label="Strike rate" value={figure(b.strikeRate)} />
            <Row id="bat-notouts" label="Not outs" value={b.notOuts} />
            <Row id="bat-fours" label="Fours" value={b.fours} />
            <Row id="bat-sixes" label="Sixes" value={b.sixes} />
            <Row id="bat-fifties" label="50s / 100s" value={`${b.fifties} / ${b.hundreds}`} />
            <Row id="bat-ducks" label="Ducks" value={b.ducks} />
          </TableBody>
        </Table>
      </Box>
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small" aria-label="Bowling">
          <TableBody>
            <Row id="bowl-innings" label="Innings" value={w.innings} />
            <Row id="bowl-overs" label="Overs" value={w.overs} />
            <Row id="bowl-wickets" label="Wickets" value={w.wickets} />
            <Row id="bowl-best" label="Best bowling" value={w.best ?? '–'} />
            <Row id="bowl-average" label="Average" value={figure(w.average)} />
            <Row id="bowl-economy" label="Economy" value={figure(w.economy)} />
            <Row id="bowl-sr" label="Strike rate" value={figure(w.strikeRate)} />
            <Row id="bowl-maidens" label="Maidens" value={w.maidens} />
            <Row id="bowl-five" label="5 wickets" value={w.fiveWickets} />
          </TableBody>
        </Table>
      </Box>
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small" aria-label="Fielding">
          <TableBody>
            <Row id="field-catches" label="Catches" value={line.fielding.catches} />
            <Row id="field-stumpings" label="Stumpings" value={line.fielding.stumpings} />
            <Row id="field-runouts" label="Run outs" value={line.fielding.runOuts} />
          </TableBody>
        </Table>
      </Box>
    </Stack>
  );
};

type Load = { state: 'loading' } | { state: 'ready'; stats: PublicPlayerStats } | { state: 'none' } | { state: 'failed' };

/**
 * A player's public career: one tab for each ball type the player has a finished match in (tennis and leather are never added together). A player with no finished
 * match, and an id that does not exist, show the same message. It needs no sign-in.
 */
const PlayerStatsPage = () => {
  const { id = '' } = useParams();
  const [load, setLoad] = useState<Load>({ state: 'loading' });
  const [tab, setTab] = useState(0);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoad({ state: 'loading' });
    cricketPublicApi.playerStats(id)
      .then((stats) => !cancelled && setLoad({ state: 'ready', stats }))
      .catch((error) => !cancelled && setLoad(isNotFound(error) ? { state: 'none' } : { state: 'failed' }));
    return () => {
      cancelled = true;
    };
  }, [id, attempt]);

  return (
    <Box sx={{ maxWidth: 640, mx: 'auto' }} data-testid="player-stats">
      {load.state === 'loading' && <Skeleton variant="rounded" height={200} />}
      {load.state === 'none' && (
        <Alert severity="info" data-testid="player-none" action={<Button color="inherit" size="small" component={RouterLink} to="/live">Live cricket</Button>}>
          There are no finished matches for this player yet, or the link is not right. A career appears once a match this player took part in has been completed.
        </Alert>
      )}
      {load.state === 'failed' && (
        <Alert severity="warning" data-testid="player-failed" action={<Button color="inherit" size="small" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>}>
          The statistics could not be loaded. Check your connection and try again.
        </Alert>
      )}
      {load.state === 'ready' && (
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="h5" component="h1" sx={{ overflowWrap: 'anywhere' }} data-testid="player-name">{load.stats.name}</Typography>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1 }}>Career statistics from completed matches scored on DSports. Abandoned and no-result matches are not counted.</Typography>
          {load.stats.lines.length > 1 && (
            <Tabs value={tab} onChange={(_, value: number) => setTab(value)} variant="fullWidth" sx={{ mb: 2 }} aria-label="Ball type">
              {load.stats.lines.map((line) => <Tab key={line.ballType} label={BALL_LABEL[line.ballType]} data-testid={`tab-${line.ballType.toLowerCase()}`} />)}
            </Tabs>
          )}
          {load.stats.lines.length === 1 && <Typography variant="subtitle1" sx={{ mb: 1 }}>{BALL_LABEL[load.stats.lines[0].ballType]}</Typography>}
          <CareerTables line={load.stats.lines[Math.min(tab, load.stats.lines.length - 1)]} />
        </Paper>
      )}
    </Box>
  );
};

export default PlayerStatsPage;
