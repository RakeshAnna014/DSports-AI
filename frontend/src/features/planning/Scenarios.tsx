import { useSearchParams } from 'react-router-dom';
import { useState } from 'react';
import {
  Alert, Box, Button, Checkbox, Chip, FormControl, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isApprover, planningApi, planningError, type Scenario } from '@/api/planning';
import FilterBar from './FilterBar';
import { ScopeBanner, Section, n, pretty, signed, usePlanning } from './parts';

const KINDS: [string, string][] = [['GROWTH_TARGET', 'Growth target'], ['PROMOTION', 'Promotion'], ['LEAD_TIME', 'Supplier lead time'], ['SAFETY_STOCK', 'Safety stock (service level)']];
const ROWS: [string, string][] = [['demand_units', 'Demand (units)'], ['order_units', 'Order quantity'], ['stores_high_risk', 'Stores at high risk'], ['order_cost', 'Order cost'], ['revenue', 'Estimated revenue'], ['margin', 'Estimated margin']];

const Pilots = () => {
  const { roles } = usePlanning();
  const qc = useQueryClient();
  const pilots = useQuery({ queryKey: ['pln', 'pilots'], queryFn: planningApi.pilots, retry: false });
  const [qs] = useSearchParams();
  const [f, setF] = useState({ opportunity_id: qs.get('opp') ?? '', duration_weeks: '8', comparable_sport: '', comparable_category: '' });
  const propose = useMutation({
    mutationFn: () => planningApi.proposePilot({ opportunity_id: f.opportunity_id.trim(), duration_weeks: Number(f.duration_weeks), comparable_sport: f.comparable_sport || undefined, comparable_category: f.comparable_category || undefined }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pln', 'pilots'] }); },
  });
  const decide = useMutation({ mutationFn: (v: { id: string; status: string }) => planningApi.pilotDecision(v.id, v.status), onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pln', 'pilots'] }); } });
  return (
    <Section title="New-product pilots (from Product & Supplier Intelligence)" id="pilots">
      <Alert severity="info" icon={false} sx={{ mb: 1 }}>A new product has no sales history, so it is piloted, not forecast. A pilot proposes stores and stock from comparable products, as a low-confidence range with its assumptions. It never publishes a product, buys stock or changes a price; the product must already be approved as a business opportunity.</Alert>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <TextField size="small" label="Product record id (from Product Intelligence)" value={f.opportunity_id} onChange={(e) => setF({ ...f, opportunity_id: e.target.value })} sx={{ minWidth: 330 }} inputProps={{ 'data-testid': 'pilot-opp' }} />
        <TextField size="small" type="number" label="Weeks" value={f.duration_weeks} onChange={(e) => setF({ ...f, duration_weeks: e.target.value })} sx={{ width: 90 }} />
        <TextField size="small" label="Comparable sport" value={f.comparable_sport} onChange={(e) => setF({ ...f, comparable_sport: e.target.value })} sx={{ width: 140 }} />
        <TextField size="small" label="Comparable category" value={f.comparable_category} onChange={(e) => setF({ ...f, comparable_category: e.target.value })} sx={{ width: 150 }} />
        <Button size="small" variant="contained" disabled={propose.isPending || f.opportunity_id.trim().length < 36} onClick={() => propose.mutate()} data-testid="pilot-propose">Propose a pilot</Button>
      </Stack>
      {propose.isError && <Alert severity="warning" data-testid="pilot-error">{planningError(propose.error)}</Alert>}
      {(pilots.data ?? []).length === 0 && <Typography variant="body2" color="text.secondary" data-testid="pilots-empty">No pilot has been proposed.</Typography>}
      {(pilots.data ?? []).map((p) => (
        <Paper key={p.id} variant="outlined" sx={{ p: 1, mb: 0.75 }} data-testid="pilot-row">
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography fontWeight={800}>{p.product_name}</Typography><Chip size="small" label={pretty(p.status)} /><Chip size="small" variant="outlined" color={p.estimate.status === 'ESTIMATED' ? 'warning' : 'default'} label={p.estimate.status === 'ESTIMATED' ? 'Low-confidence estimate' : pretty(p.estimate.status)} />
            {isApprover(roles) && p.status === 'PROPOSED' && p.estimate.status === 'ESTIMATED' && <Button size="small" onClick={() => decide.mutate({ id: p.id, status: 'APPROVED' })}>Approve pilot</Button>}
          </Stack>
          <Typography variant="body2">{p.estimate.reason}</Typography>
          {p.estimate.hint && <Typography variant="caption" color="warning.main" display="block">{p.estimate.hint}</Typography>}
          {p.stores.length > 0 && <Typography variant="body2">Stores: {p.stores.map((s) => `${s.location_id} (${n(s.weekly_lo, 1)}–${n(s.weekly_hi, 1)}/week, stock ${s.pilot_qty})`).join('; ')} for {p.duration_weeks} weeks.</Typography>}
          <Typography variant="caption" color="text.secondary">Assumptions: {p.estimate.assumptions.join(' ')}</Typography>
        </Paper>))}
      {decide.isError && <Alert severity="warning">{planningError(decide.error)}</Alert>}
    </Section>
  );
};

/** Simulations over the stored forecast. They are saved and comparable, and they never change a plan; only a growth-target scenario can be adopted, and only as targets. */
const Scenarios = () => {
  const { planId, filters, source } = usePlanning();
  const qc = useQueryClient();
  const [kind, setKind] = useState('GROWTH_TARGET');
  const [name, setName] = useState('');
  const [v, setV] = useState({ growth: '10', discount: '10', weeks: '2', delta: '7', service: '0.99' });
  const [picked, setPicked] = useState<string[]>([]);
  const [viewing, setViewing] = useState<Scenario | null>(null);
  const list = useQuery({ queryKey: ['pln', 'scenarios', planId], queryFn: () => planningApi.scenarios(planId), enabled: !!planId, retry: false });
  const cmp = useQuery({ queryKey: ['pln', 'compare', picked], queryFn: () => planningApi.compareScenarios(picked), enabled: picked.length >= 2, retry: false });
  const create = useMutation({
    mutationFn: () => planningApi.createScenario({ plan_id: planId, name: name || pretty(kind), kind, filters: { sport: filters.sport, category: filters.category, region: filters.region, stores: filters.store, product_id: filters.product_id },
      params: kind === 'GROWTH_TARGET' ? { growth_pct: Number(v.growth) } : kind === 'PROMOTION' ? { discount_pct: Number(v.discount), weeks: Number(v.weeks), start_week_index: 0 } : kind === 'LEAD_TIME' ? { delta_days: Number(v.delta) } : { service_level: Number(v.service) } }),
    onSuccess: (s) => { setViewing(s); void qc.invalidateQueries({ queryKey: ['pln', 'scenarios'] }); },
  });
  const adopt = useMutation({ mutationFn: (id: string) => planningApi.adoptScenario(id), onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pln'] }); } });
  if (!planId) return <Alert severity="info">Choose a plan: a scenario is a what-if over that plan's forecast.</Alert>;
  const summary = (s: Scenario) => s.results?.summary ?? s.summary;
  return (
    <Stack spacing={1.25} data-testid="scenarios">
      <ScopeBanner source={source} />
      <Section title="New scenario" id="scenario-form">
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
          <FilterBar show={['sport', 'category', 'region']} />
          <FormControl size="small" sx={{ minWidth: 200 }}><InputLabel id="sk-l">What if</InputLabel><Select labelId="sk-l" label="What if" value={kind} onChange={(e) => setKind(e.target.value)} SelectDisplayProps={{ 'data-testid': 'scn-kind' } as React.HTMLAttributes<HTMLDivElement>}>{KINDS.map(([k, t]) => <MenuItem key={k} value={k}>{t}</MenuItem>)}</Select></FormControl>
          {kind === 'GROWTH_TARGET' && <TextField size="small" type="number" label="Growth %" value={v.growth} onChange={(e) => setV({ ...v, growth: e.target.value })} sx={{ width: 110 }} inputProps={{ 'data-testid': 'scn-growth' }} />}
          {kind === 'PROMOTION' && (<><TextField size="small" type="number" label="Discount %" value={v.discount} onChange={(e) => setV({ ...v, discount: e.target.value })} sx={{ width: 110 }} inputProps={{ 'data-testid': 'scn-discount' }} /><TextField size="small" type="number" label="Weeks" value={v.weeks} onChange={(e) => setV({ ...v, weeks: e.target.value })} sx={{ width: 90 }} /></>)}
          {kind === 'LEAD_TIME' && <TextField size="small" type="number" label="Change in lead time (days)" value={v.delta} onChange={(e) => setV({ ...v, delta: e.target.value })} sx={{ width: 190 }} inputProps={{ 'data-testid': 'scn-delta' }} />}
          {kind === 'SAFETY_STOCK' && <TextField size="small" type="number" label="Service level (0-1)" value={v.service} onChange={(e) => setV({ ...v, service: e.target.value })} sx={{ width: 150 }} />}
          <TextField size="small" label="Name" value={name} onChange={(e) => setName(e.target.value)} sx={{ width: 170 }} />
          <Button size="small" variant="contained" disabled={create.isPending || (!filters.sport && !filters.category)} onClick={() => create.mutate()} data-testid="scn-run">{create.isPending ? 'Calculating…' : 'Run scenario'}</Button>
        </Stack>
        {!filters.sport && !filters.category && <Typography variant="caption" color="text.secondary">Choose a sport or category: a scenario is evaluated product by product (at most 150 products).</Typography>}
        {create.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="scn-error">{planningError(create.error)}</Alert>}
      </Section>
      {viewing && viewing.results && (
        <Section title={`Result: ${viewing.name}`} id="scn-result">
          <Alert severity="info" icon={false}>{viewing.results.notice}</Alert>
          <Table size="small"><TableHead><TableRow><TableCell /><TableCell align="right">Baseline</TableCell><TableCell align="right">Scenario</TableCell><TableCell align="right">Change</TableCell></TableRow></TableHead>
            <TableBody>{ROWS.map(([k, t]) => <TableRow key={k}><TableCell>{t}</TableCell><TableCell align="right">{n(summary(viewing)?.baseline[k] ?? null)}</TableCell><TableCell align="right" data-testid={`scn-${k}`}>{n(summary(viewing)?.scenario[k] ?? null)}</TableCell><TableCell align="right">{signed(summary(viewing)?.delta[k] ?? null)}</TableCell></TableRow>)}</TableBody></Table>
          <Typography variant="caption" color="text.secondary" display="block">Limits: {viewing.results.limits.join(' ')}{viewing.results.skipped.length ? ` ${viewing.results.skipped.length} product(s) skipped: ${viewing.results.skipped[0].why}` : ''}</Typography>
        </Section>
      )}
      <Section title="Saved scenarios (simulations: separate from every plan and from actuals)" id="scn-list">
        {(list.data ?? []).length === 0 && <Typography variant="body2" color="text.secondary" data-testid="scn-empty">No scenario saved for this plan.</Typography>}
        {list.isLoading && <Skeleton variant="rounded" height={60} />}
        {(list.data ?? []).map((s) => (
          <Stack key={s.id} direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap data-testid="scn-row">
            <Checkbox size="small" checked={picked.includes(s.id)} onChange={(e) => setPicked((p) => (e.target.checked ? [...p, s.id].slice(-5) : p.filter((x) => x !== s.id)))} inputProps={{ 'aria-label': `Compare ${s.name}` }} />
            <Typography variant="body2"><b>{s.name}</b> · {pretty(s.kind)} · {Object.entries(s.params).filter(([k]) => k !== 'filters').map(([k, x]) => `${k} ${String(x)}`).join(', ')}</Typography>
            <Chip size="small" variant="outlined" label={pretty(s.status)} />
            <Button size="small" onClick={() => void planningApi.scenario(s.id).then(setViewing)}>Open</Button>
            {s.kind === 'GROWTH_TARGET' && s.status === 'SIMULATION' && <Button size="small" onClick={() => adopt.mutate(s.id)} data-testid="scn-adopt">Adopt as targets</Button>}
          </Stack>))}
        {adopt.isError && <Alert severity="warning" sx={{ mt: 1 }}>{planningError(adopt.error)}</Alert>}
        {adopt.data && <Alert severity="success" sx={{ mt: 1 }} data-testid="scn-adopted">{adopt.data.lines_set} target(s) were set on the plan. No forecast was changed.</Alert>}
      </Section>
      {cmp.data && (
        <Section title="Comparison" id="scn-compare">
          <Table size="small"><TableHead><TableRow><TableCell /><TableCell align="right">Baseline</TableCell>{cmp.data.scenarios.map((s) => <TableCell key={s.id} align="right">{s.name}</TableCell>)}</TableRow></TableHead>
            <TableBody>{ROWS.map(([k, t]) => <TableRow key={k}><TableCell>{t}</TableCell><TableCell align="right">{n(cmp.data.scenarios[0].summary.baseline[k] ?? null)}</TableCell>{cmp.data.scenarios.map((s) => <TableCell key={s.id} align="right">{n(s.summary.scenario[k] ?? null)}</TableCell>)}</TableRow>)}</TableBody></Table>
          <Typography variant="caption" color="text.secondary">{cmp.data.notice}</Typography>
        </Section>
      )}
      <Pilots />
      <Box />
    </Stack>
  );
};

export default Scenarios;
