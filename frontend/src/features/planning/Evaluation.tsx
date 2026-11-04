import { useState } from 'react';
import { Alert, Box, FormControl, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TablePagination, TableRow, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { planningApi, planningError } from '@/api/planning';
import FilterBar from './FilterBar';
import { DataUnavailable, GroupedBars, series as seriesColor } from '@/components/analytics';
import { ConfChip, Metric, ScopeBanner, Section, day, n, pctText, pretty, usePlanning } from './parts';

const SCOPES = ['OVERALL', 'SPORT', 'CATEGORY', 'REGION', 'STORE', 'BUCKET', 'CANDIDATE'];

/** Accuracy is measured on weeks the model did not see, against a plain 4-week average. A figure on too few observations is withheld; it is never shown as a percentage. */
const Evaluation = () => {
  const { source, filters } = usePlanning();
  const [scope, setScope] = useState('OVERALL');
  const [horizon, setHorizon] = useState('ALL');
  const [page, setPage] = useState(0);
  const ev = useQuery({ queryKey: ['pln', 'eval', source, scope, horizon], queryFn: () => planningApi.evaluation(source, scope, horizon === 'ALL' ? undefined : horizon), retry: false });
  const overall = useQuery({ queryKey: ['pln', 'eval', source, 'OVERALL', 'all'], queryFn: () => planningApi.evaluation(source, 'OVERALL', 'ALL'), retry: false });
  const series = useQuery({ queryKey: ['pln', 'series', source, filters, page], queryFn: () => planningApi.forecastSeries(source, filters, page, 15), retry: false });
  const sel = overall.data?.rows.find((r) => r.model === 'SELECTED' && r.horizon_group === 'ALL');
  const base = overall.data?.rows.find((r) => r.model === 'SELECTED' && r.horizon_group === 'ALL');
  return (
    <Stack spacing={1.25} data-testid="evaluation">
      <ScopeBanner source={source} />
      {ev.isLoading && <Skeleton variant="rounded" height={160} />}
      {ev.isError && <Alert severity="info" data-testid="eval-none">{planningError(ev.error)}</Alert>}
      {overall.data && (
        <>
          <Alert severity="info" icon={false} data-testid="eval-notice">{overall.data.notice} Model version {overall.data.run.model_version}; forecast for the weeks from {day(overall.data.run.first_week)}; history to the week of {day(overall.data.run.as_of_week)}.</Alert>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            <Metric id="ev-wape" title="WAPE (held-out weeks)" value={sel?.sufficient ? pctText((sel.wape ?? 0) * 100, 1) : 'Withheld'} hint={sel?.sufficient ? `${n(sel.n_obs)} series-weeks` : (sel?.note ?? 'too few observations')} />
            <Metric id="ev-base" title="Baseline (4-week average)" value={base?.sufficient ? pctText((base.baseline_wape ?? 0) * 100, 1) : 'Withheld'} hint={sel?.better_than_baseline === null || sel?.better_than_baseline === undefined ? '' : sel.better_than_baseline ? 'the chosen method beats the baseline' : 'the chosen method does NOT beat the baseline'} />
            <Metric id="ev-bias" title="Bias" value={sel?.sufficient ? pctText((sel.bias ?? 0) * 100, 1) : 'Withheld'} hint="positive = over-forecast" />
            <Metric id="ev-history" title="History behind the forecast" value={` weeks`} hint={`to  · `} provenance={source === 'POC_SYNTHETIC' ? 'DEMO' : 'LIVE'} />
            <Metric id="ev-series" title="Series forecast" value={n(overall.data.run.n_series)} hint={`${n(overall.data.run.n_ok)} own pattern · ${n(overall.data.run.n_fallback)} fallback · ${n(overall.data.run.n_insufficient)} none`} />
          </Stack>
        </>
      )}
      <Stack direction="row" spacing={1}>
        <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="es-l">Accuracy by</InputLabel><Select labelId="es-l" label="Accuracy by" value={scope} onChange={(e) => setScope(e.target.value)} SelectDisplayProps={{ 'data-testid': 'eval-scope' } as React.HTMLAttributes<HTMLDivElement>}>{SCOPES.map((s) => <MenuItem key={s} value={s}>{s === 'CANDIDATE' ? 'Method (comparison)' : pretty(s)}</MenuItem>)}</Select></FormControl>
        <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="eh-l">Horizon</InputLabel><Select labelId="eh-l" label="Horizon" value={horizon} onChange={(e) => setHorizon(e.target.value)}>
          {[['ALL', 'All weeks'], ['H1', 'Week 1'], ['H2_4', 'Weeks 2-4'], ['H5_8', 'Weeks 5-8'], ['H9_PLUS', 'Week 9 on']].map(([v, t]) => <MenuItem key={v} value={v}>{t}</MenuItem>)}</Select></FormControl>
      </Stack>
      {ev.data && (() => {
        const rows = ev.data.rows.filter((r) => r.model === 'SELECTED' && r.sufficient && r.wape !== null && r.baseline_wape !== null).slice(0, 14);
        return rows.length > 0 ? (
          <Section title="Model versus baseline (WAPE, lower is better)" id="eval-chart" provenance={source === 'POC_SYNTHETIC' ? ['DEMO', 'FORECAST'] : ['LIVE', 'FORECAST']}
            subtitle="Measured on weeks the model never saw. Only rows with enough observations are drawn; thinner ones are withheld, never shown as a percentage.">
            <GroupedBars unit="% WAPE" testId="eval-bars" groups={rows.map((r) => ({ label: `${r.scope_key}${r.horizon_group === 'ALL' ? '' : ' ' + r.horizon_group}`, values: { model: Math.round((r.wape ?? 0) * 1000) / 10, base: Math.round((r.baseline_wape ?? 0) * 1000) / 10 } }))}
              series={[{ key: 'model', name: 'Selected model', color: seriesColor.forecast }, { key: 'base', name: '4-week average baseline', color: seriesColor.baseline }]} />
          </Section>
        ) : <DataUnavailable what="Accuracy comparison" why="No row has enough held-out observations to quote a percentage." needs="More closed weeks of sales history (or a wider selection)." testId="eval-chart-unavailable" />;
      })()}      {ev.data && (
        <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>{scope === 'CANDIDATE' ? 'Method' : pretty(scope)}</TableCell><TableCell>Horizon</TableCell><TableCell align="right">Series-weeks</TableCell><TableCell align="right">Actual units</TableCell><TableCell align="right">WAPE</TableCell>
              <TableCell align="right">Baseline WAPE</TableCell><TableCell align="right">Bias</TableCell><TableCell align="right">MAE</TableCell><TableCell>Evidence</TableCell></TableRow></TableHead>
            <TableBody>{ev.data.rows.filter((r) => r.model === 'SELECTED' || scope === 'CANDIDATE').map((r) => (
              <TableRow key={`${r.scope}${r.scope_key}${r.model}${r.horizon_group}`} data-testid="eval-row">
                <TableCell>{r.scope_key}</TableCell><TableCell>{r.horizon_group}</TableCell><TableCell align="right">{n(r.n_obs)}</TableCell><TableCell align="right">{n(r.actual_units)}</TableCell>
                <TableCell align="right" data-testid="eval-wape">{r.wape === null ? 'withheld' : pctText(r.wape * 100, 1)}</TableCell><TableCell align="right">{r.baseline_wape === null ? '—' : pctText(r.baseline_wape * 100, 1)}</TableCell>
                <TableCell align="right">{r.bias === null ? '—' : pctText(r.bias * 100, 1)}</TableCell><TableCell align="right">{r.mae === null ? '—' : n(r.mae, 2)}</TableCell>
                <TableCell>{r.sufficient ? <Typography variant="caption" color="success.main">enough observations</Typography> : <Typography variant="caption" color="warning.main" title={r.note ?? ''}>too few to quote</Typography>}</TableCell>
              </TableRow>))}</TableBody>
          </Table>
        </Paper>
      )}
      {overall.data?.method_choice && (
        <Section title="How the method was chosen (selection weeks, per volume bucket)" id="eval-methods">
          {Object.entries(overall.data.method_choice).map(([b, info]) => (
            <Typography key={b} variant="body2" data-testid="eval-bucket">{pretty(b)} ({n(info.n_obs)} observations): chosen <b>{pretty(info.chosen ?? 'MA4')}</b>
              {info.wape ? ` — WAPE ${Object.entries(info.wape).map(([m, w]) => `${m} ${w === null ? '—' : (w * 100).toFixed(0) + '%'}`).join(' · ')}` : ''}</Typography>))}
          <Typography variant="caption" color="text.secondary">The method is chosen on earlier weeks; the figures above are measured on later weeks it never saw (no future data leaks into a forecast).</Typography>
        </Section>
      )}
      {overall.data?.features && (
        <Section title="Inputs" id="eval-features">
          <Typography variant="body2"><b>Used:</b> {overall.data.features.used.join('; ')}.</Typography>
          <Typography variant="body2"><b>Not used:</b> {overall.data.features.not_used.join('; ')}.</Typography>
        </Section>
      )}
      <Section title="Forecast by product and store (method and confidence)" id="eval-series" action={<FilterBar show={['sport', 'category', 'region', 'q']} />}>
        {series.data && (
          <>
            <Table size="small">
              <TableHead><TableRow><TableCell>Product</TableCell><TableCell>Store</TableCell><TableCell>Method</TableCell><TableCell>Bucket</TableCell><TableCell align="right">History (weeks)</TableCell><TableCell>Confidence</TableCell><TableCell align="right">Next week (range)</TableCell></TableRow></TableHead>
              <TableBody>{series.data.items.map((r) => (
                <TableRow key={`${r.product_id}${r.location_id}`} data-testid="series-row"><TableCell>{r.product_name}</TableCell><TableCell>{r.store_name}</TableCell><TableCell title={r.reason ?? ''}>{pretty(r.method)}{r.status !== 'OK' ? ` (${pretty(r.status)})` : ''}</TableCell>
                  <TableCell>{pretty(r.bucket)}</TableCell><TableCell align="right">{r.history_weeks}</TableCell><TableCell><ConfChip c={r.confidence} /></TableCell>
                  <TableCell align="right">{r.weeks[0] ? `${n(r.weeks[0].forecast, 1)} (${n(r.weeks[0].lo, 1)}–${n(r.weeks[0].hi, 1)})` : 'no forecast'}</TableCell></TableRow>))}</TableBody>
            </Table>
            {series.data.items.length === 0 && <Box sx={{ p: 2 }}><Typography color="text.secondary">No series match.</Typography></Box>}
            <TablePagination component="div" count={series.data.total} page={page} rowsPerPage={15} rowsPerPageOptions={[15]} onPageChange={(_, p) => setPage(p)} />
          </>
        )}
      </Section>
    </Stack>
  );
};

export default Evaluation;
