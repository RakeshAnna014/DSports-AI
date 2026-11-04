import { useState } from 'react';
import { Alert, Box, FormControl, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { planningApi, planningError } from '@/api/planning';
import FilterBar from './FilterBar';
import { BarList, LineChart, ScopeBanner, n, pctText, pretty, signed, usePlanning } from './parts';

const GRAINS: [string, string][] = [['category', 'Sport / category'], ['sport', 'Sport'], ['sku', 'Product'], ['store', 'Store'], ['zone', 'Zone'], ['region', 'Region'], ['week', 'Week'], ['month', 'Month'], ['quarter', 'Financial quarter'], ['fy', 'Financial year'], ['total', 'Total']];

/** Forecast, target and actual rolled up to a grain. Variances are computed only over lines that have both sides; the coverage columns say how many that was. */
const Performance = () => {
  const { planId, filters, source } = usePlanning();
  const [grain, setGrain] = useState('category');
  const [basis, setBasis] = useState<'PLAN' | 'MODEL'>('PLAN');
  const q = useQuery({ queryKey: ['pln', 'performance', planId, grain, basis, filters], queryFn: () => planningApi.performance(planId as string, grain, filters, basis), enabled: !!planId, retry: false });
  if (!planId) return <Alert severity="info">Choose a plan to compare its forecast and target with actual sales.</Alert>;
  const d = q.data;
  const time = ['week', 'month', 'quarter', 'fy'].includes(grain);
  return (
    <Stack spacing={1.25} data-testid="performance">
      <ScopeBanner source={source} />
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        <FilterBar show={['sport', 'category', 'region', 'store']} />
        <FormControl size="small" sx={{ minWidth: 170 }}><InputLabel id="pg-l">Group by</InputLabel>
          <Select labelId="pg-l" label="Group by" value={grain} onChange={(e) => setGrain(e.target.value)} SelectDisplayProps={{ 'data-testid': 'perf-grain' } as React.HTMLAttributes<HTMLDivElement>}>
            {GRAINS.map(([v, t]) => <MenuItem key={v} value={v}>{t}</MenuItem>)}</Select></FormControl>
        <ToggleButtonGroup size="small" exclusive value={basis} onChange={(_e, v: 'PLAN' | 'MODEL' | null) => v && setBasis(v)} aria-label="Forecast basis">
          <ToggleButton value="PLAN" data-testid="perf-basis-plan">Plan forecast</ToggleButton><ToggleButton value="MODEL" data-testid="perf-basis-model">Model forecast</ToggleButton></ToggleButtonGroup>
      </Stack>
      {q.isLoading && <Skeleton variant="rounded" height={180} />}
      {q.isError && <Alert severity="warning" data-testid="perf-error">{planningError(q.error)}</Alert>}
      {d && (
        <>
          <Alert severity="info" icon={false} data-testid="perf-notice">{d.notice} Data through {d.data_through ?? 'unknown'}. Forecast variance = actual − forecast; target variance = actual − target; attainment = actual ÷ target.</Alert>
          {time && <Paper variant="outlined" sx={{ p: 1.5 }}><LineChart testId="perf-line" points={d.rows.map((r) => ({ label: r.gkey, values: [r.forecast, r.target, r.actual] }))} series={[{ name: 'Forecast', color: '#1976d2' }, { name: 'Target', color: '#ed6c02' }, { name: 'Actual', color: '#2e7d32' }]} /></Paper>}
          {!time && d.rows.length > 1 && <Paper variant="outlined" sx={{ p: 1.5 }}><Typography variant="caption" fontWeight={800}>Actual units (closed weeks)</Typography><BarList testId="perf-bars" items={d.rows.filter((r) => r.actual !== null).slice(0, 12).map((r) => ({ label: r.gkey, value: r.actual ?? 0 }))} /></Paper>}
          <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead><TableRow><TableCell>{pretty(grain)}</TableCell><TableCell align="right">Forecast</TableCell><TableCell align="right">Target</TableCell><TableCell align="right">Actual</TableCell>
                <TableCell align="right">Forecast var.</TableCell><TableCell align="right">Forecast var. %</TableCell><TableCell align="right">Target var.</TableCell><TableCell align="right">Attainment</TableCell><TableCell align="right">Lines with actual / target</TableCell></TableRow></TableHead>
              <TableBody>
                {[...d.rows, ...(d.total && grain !== 'total' ? [{ ...d.total, gkey: 'Total' }] : [])].map((r) => (
                  <TableRow key={r.gkey} hover data-testid={r.gkey === 'Total' ? 'perf-total' : 'perf-row'} sx={r.gkey === 'Total' ? { fontWeight: 800, bgcolor: 'action.hover' } : undefined}>
                    <TableCell>{r.gkey}</TableCell><TableCell align="right">{n(r.forecast, 0)}</TableCell><TableCell align="right">{r.target === null ? '—' : n(r.target, 0)}</TableCell><TableCell align="right">{r.actual === null ? <Typography variant="caption" color="text.secondary">no closed week</Typography> : n(r.actual, 0)}</TableCell>
                    <TableCell align="right" data-testid="perf-fvar">{signed(r.forecast_variance, 0)}</TableCell><TableCell align="right">{pctText(r.forecast_variance_pct)}</TableCell><TableCell align="right">{signed(r.target_variance, 0)}</TableCell>
                    <TableCell align="right" data-testid="perf-attain">{pctText(r.target_attainment_pct)}</TableCell><TableCell align="right">{r.with_actual} / {r.with_target} of {r.lines}</TableCell>
                  </TableRow>))}
              </TableBody>
            </Table>
            {d.rows.length === 0 && <Box sx={{ p: 3, textAlign: 'center' }}><Typography color="text.secondary">No line matches these filters.</Typography></Box>}
          </Paper>
        </>
      )}
    </Stack>
  );
};

export default Performance;
