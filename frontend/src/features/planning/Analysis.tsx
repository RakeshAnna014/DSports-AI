import { useState } from 'react';
import { Alert, Chip, FormControl, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { planningApi, planningError } from '@/api/planning';
import { Heatmap } from '@/components/analytics';
import { BarList, ScopeBanner, Section, n, pctText, usePlanning } from './parts';

/** Differences between regions, stores and categories, from closed-week net units. Each insight shows its sample size; a small sample or one affected by stock-outs is marked LOW confidence and is not a preference. */
const Analysis = () => {
  const { source } = usePlanning();
  const [weeks, setWeeks] = useState(26);
  const [region, setRegion] = useState('');
  const q = useQuery({ queryKey: ['pln', 'regional', source, weeks, region], queryFn: () => planningApi.regional(source, weeks, region || undefined), retry: false });
  const d = q.data;
  const byRegion = d ? Object.entries(d.matrix.reduce<Record<string, number>>((a, m) => ({ ...a, [m.region]: (a[m.region] ?? 0) + m.net_units }), {})).map(([label, value]) => ({ label, value })) : [];
  const sports = d ? [...new Set(d.matrix.map((m) => m.sport))] : [];
  const regions = d ? [...new Set(d.matrix.map((m) => m.region))].sort() : [];
  const cell = (r: string, s: string) => d!.matrix.filter((m) => m.region === r && m.sport === s).reduce((a, m) => a + m.net_units, 0);
  const total = (r: string) => byRegion.find((b) => b.label === r)?.value ?? 0;
  return (
    <Stack spacing={1.25} data-testid="analysis">
      <ScopeBanner source={source} />
      <Stack direction="row" spacing={1}>
        <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="aw-l">Look back</InputLabel><Select labelId="aw-l" label="Look back" value={weeks} onChange={(e) => setWeeks(Number(e.target.value))}>{[13, 26, 52, 104].map((w) => <MenuItem key={w} value={w}>{w} closed weeks</MenuItem>)}</Select></FormControl>
        <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="ar-l">Region (products)</InputLabel><Select labelId="ar-l" label="Region (products)" value={region} onChange={(e) => setRegion(e.target.value)}><MenuItem value="">All</MenuItem>{regions.map((r) => <MenuItem key={r} value={r}>{r}</MenuItem>)}</Select></FormControl>
      </Stack>
      {q.isLoading && <Skeleton variant="rounded" height={200} />}
      {q.isError && <Alert severity="warning" data-testid="an-error">{planningError(q.error)}</Alert>}
      {d && (
        <>
          <Alert severity="info" icon={false} data-testid="an-notice">{d.notice} Weeks {d.weeks.first} to {d.weeks.last}.</Alert>
          <Section title="Where each sport sells (heatmap)" id="an-heatmap" provenance={source === 'POC_SYNTHETIC' ? ['DEMO'] : ['LIVE']} subtitle="Each cell is the sport's share of that region's units. Click a cell for the plan-versus-actual detail.">
            <Heatmap rows={regions} cols={sports} cell={(r, s) => (total(r) ? { value: (cell(r, s) / total(r)) * 100, note: `${n(cell(r, s))} units` } : { value: null })} format={(v) => `${v.toFixed(0)}%`} legend="Darker is a larger share of the region's units." />
          </Section>          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25}>
            <Section title="Sales mix by region and sport (share of the region's units)" id="an-matrix">
              <Table size="small"><TableHead><TableRow><TableCell>Region</TableCell>{sports.map((s) => <TableCell key={s} align="right">{s}</TableCell>)}<TableCell align="right">Units</TableCell></TableRow></TableHead>
                <TableBody>{regions.map((r) => <TableRow key={r} data-testid="an-region"><TableCell>{r}</TableCell>{sports.map((s) => <TableCell key={s} align="right">{pctText(total(r) ? (cell(r, s) / total(r)) * 100 : null, 0)}</TableCell>)}<TableCell align="right">{n(total(r))}</TableCell></TableRow>)}</TableBody></Table>
            </Section>
            <Section title="Units by region" id="an-bars"><BarList items={byRegion.sort((a, b) => b.value - a.value)} testId="an-region-bars" /></Section>
          </Stack>
          <Section title="Evidence-based differences" id="an-insights">
            {d.insights.length === 0 ? <Typography variant="body2" color="text.secondary">No category differs from the overall mix by 25% or more.</Typography> : d.insights.slice(0, 10).map((i) => (
              <Stack key={`${i.region}${i.sport}${i.category}`} direction="row" spacing={1} alignItems="center" sx={{ py: 0.25 }} data-testid="an-insight">
                <Chip size="small" color={i.confidence === 'MEDIUM' ? 'info' : 'warning'} variant="outlined" label={i.confidence === 'MEDIUM' ? 'Medium' : 'Low'} /><Typography variant="body2">{i.statement}</Typography></Stack>))}
          </Section>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25}>
            <Section title="Store ranking (net units)" id="an-stores">
              <Table size="small"><TableHead><TableRow><TableCell>#</TableCell><TableCell>Store</TableCell><TableCell align="right">Units</TableCell><TableCell align="right">vs region median</TableCell><TableCell align="right">Stock-out weeks</TableCell></TableRow></TableHead>
                <TableBody>{d.store_ranking.slice(0, 12).map((s) => <TableRow key={s.location_id} data-testid="an-store"><TableCell>{s.rank}</TableCell><TableCell>{s.store_name}</TableCell><TableCell align="right">{n(s.net_units)}</TableCell><TableCell align="right">{s.vs_region_median_pct === null ? '—' : `${s.vs_region_median_pct > 0 ? '+' : ''}${s.vs_region_median_pct}%`}</TableCell><TableCell align="right">{s.weeks_stockout}</TableCell></TableRow>)}</TableBody></Table>
            </Section>
            <Section title="Best-selling products" id="an-products">
              <Paper variant="outlined" sx={{ p: 1 }}><BarList testId="an-product-bars" items={d.top_products.slice(0, 10).map((p) => ({ label: p.name, value: p.net_units, note: `${p.stores} st.` }))} /></Paper>
            </Section>
          </Stack>
        </>
      )}
    </Stack>
  );
};

export default Analysis;
