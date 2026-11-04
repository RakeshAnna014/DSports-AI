import { Alert, Button, Chip, Paper, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isAdmin, planningApi, planningError, seesAll, type Capability } from '@/api/planning';
import { Section, day, n, pretty, usePlanning, when } from './parts';

const TONE: Record<Capability['status'], 'success' | 'info' | 'warning' | 'error' | 'default'> = { RELIABLE: 'success', LIMITED_ESTIMATE: 'info', INSUFFICIENT: 'warning', DEMONSTRATION_ONLY: 'warning', UNAVAILABLE: 'error' };

/** Which inputs are real, limited, synthetic or missing, and the controls that load data and generate forecasts. Read before trusting any number elsewhere. */
const Readiness = () => {
  const { roles } = usePlanning();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['pln', 'readiness'], queryFn: planningApi.readiness, retry: false });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['pln'] }); };
  const seed = useMutation({ mutationFn: planningApi.seedDemo, onSuccess: refresh });
  const ingest = useMutation({ mutationFn: (mode: 'INCREMENTAL' | 'BACKFILL') => planningApi.ingest(mode), onSuccess: refresh });
  const forecast = useMutation({ mutationFn: (scope: 'POC_SYNTHETIC' | 'DSPORTS_LIVE') => planningApi.runForecast(scope), onSuccess: refresh });
  if (q.isLoading) return <Skeleton variant="rounded" height={200} />;
  if (q.isError) return <Alert severity="warning" data-testid="ready-error">{planningError(q.error)}</Alert>;
  const d = q.data!;
  return (
    <Stack spacing={1.25} data-testid="readiness">
      <Alert severity="warning" icon={false}>Every forecast shows which of these inputs it rests on. Demonstration data demonstrates the method; it is not evidence of business accuracy.</Alert>
      <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Capability</TableCell><TableCell>Status</TableCell><TableCell>Evidence</TableCell><TableCell>What it means</TableCell></TableRow></TableHead>
          <TableBody>{d.capabilities.map((c) => (
            <TableRow key={c.key} data-testid="cap-row" data-key={c.key}><TableCell>{c.label}</TableCell><TableCell><Chip size="small" color={TONE[c.status]} label={pretty(c.status)} data-testid="cap-status" /></TableCell><TableCell>{c.evidence}</TableCell><TableCell>{c.note}</TableCell></TableRow>))}</TableBody>
        </Table>
      </Paper>
      <Section title="Forecasting by data scope" id="ready-forecasting">
        {(['POC_SYNTHETIC', 'DSPORTS_LIVE'] as const).map((s) => (
          <Stack key={s} direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ py: 0.25 }} data-testid="forecast-scope">
            <Chip size="small" color={TONE[d.forecasting[s].status as Capability['status']] ?? 'default'} label={`${s === 'POC_SYNTHETIC' ? 'Demonstration' : 'Live online'}: ${pretty(d.forecasting[s].status)}`} />
            <Typography variant="body2">{d.forecasting[s].reason}</Typography>
            {seesAll(roles) && <Button size="small" variant="outlined" disabled={forecast.isPending} onClick={() => forecast.mutate(s)} data-testid={`run-forecast-${s}`}>{d.forecasting[s].run ? 'Regenerate forecast' : 'Generate forecast'}</Button>}
          </Stack>))}
        {forecast.isPending && <Typography variant="caption" color="text.secondary">Forecasting every product and store; this takes about half a minute…</Typography>}
        {forecast.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="forecast-error">{planningError(forecast.error)}</Alert>}
        {forecast.data && <Alert severity="success" sx={{ mt: 1 }} data-testid="forecast-result">Forecast run {String(forecast.data.status).toLowerCase()}: {n(Number(forecast.data.series))} series ({n(Number(forecast.data.ok))} own pattern, {n(Number(forecast.data.fallback))} fallback, {n(Number(forecast.data.insufficient))} none).</Alert>}
      </Section>
      <Section title="Live DSports sales feed" id="ready-live">
        <Typography variant="body2">{n(Number(d.live.lines ? (d.live.lines as unknown as Record<string, number>).orders : 0))} order(s) on {n(Number(d.live.lines ? (d.live.lines as unknown as Record<string, number>).order_days : 0))} distinct day(s) → {n(Number(d.live.weeks))} week(s) of online sales, data through {day(String(d.live.data_through ?? ''))}.</Typography>
        <Typography variant="body2" data-testid="reconcile">Reconciliation of the weekly aggregates with the stored order lines: <b>{d.reconciliation.reconciled ? 'exact (units and money agree)' : 'DOES NOT AGREE'}</b> ({n(d.reconciliation.lines)} line(s), {n(d.reconciliation.excluded)} not a sale).</Typography>
        {d.last_ingest && <Typography variant="caption" color="text.secondary" display="block">Last ingest {pretty(String(d.last_ingest.status))} at {when(String(d.last_ingest.finished_at ?? d.last_ingest.started_at))}: {String(d.last_ingest.lines_read)} read, {String(d.last_ingest.lines_changed)} changed.</Typography>}
        <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
          {isAdmin(roles) && <Button size="small" variant="outlined" disabled={ingest.isPending} onClick={() => ingest.mutate('INCREMENTAL')} data-testid="ingest-incremental">Refresh from DSports</Button>}
          {isAdmin(roles) && <Button size="small" variant="outlined" disabled={ingest.isPending} onClick={() => ingest.mutate('BACKFILL')} data-testid="ingest-backfill">Full backfill</Button>}
          {isAdmin(roles) && <Button size="small" variant="outlined" disabled={seed.isPending} onClick={() => seed.mutate()} data-testid="seed-demo">Build the demonstration network</Button>}
        </Stack>
        {ingest.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="ingest-error">{planningError(ingest.error)}</Alert>}
        {ingest.data && <Alert severity="success" sx={{ mt: 1 }} data-testid="ingest-result">Ingest {String(ingest.data.status).toLowerCase()}: {String(ingest.data.lines_read)} line(s) read, {String(ingest.data.lines_changed)} changed, {String(ingest.data.lines_excluded)} not a sale.</Alert>}
        {seed.isError && <Alert severity="warning" sx={{ mt: 1 }}>{planningError(seed.error)}</Alert>}
        {seed.data && <Alert severity="success" sx={{ mt: 1 }} data-testid="seed-result">{String(seed.data.note)}</Alert>}
      </Section>
      <Section title="Conventions" id="ready-conventions">
        <Typography variant="body2">Weeks start on {d.conventions.week_start}, in {d.conventions.timezone}; the financial year starts in month {d.conventions.fy_start_month}.</Typography>
        <Typography variant="body2">Net units = {d.conventions.net_units}.</Typography>
        <Typography variant="body2">{d.conventions.restatement}.</Typography>
        <Typography variant="body2">A forecast is shown only where a series has at least {d.thresholds.min_history_weeks} weeks of its own history (otherwise a labelled fallback); accuracy needs at least {d.thresholds.min_eval_observations} observations and {d.thresholds.min_eval_units} units.</Typography>
      </Section>
    </Stack>
  );
};

export default Readiness;
