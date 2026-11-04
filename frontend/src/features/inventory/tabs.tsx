import { useState } from 'react';
import { Alert, Box, Button, Chip, MenuItem, Select, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { inventoryAiApi } from '@/api/inventoryAi';
import { colors } from '@/design/tokens';
import { BarList, ForecastChart } from './charts';
import {
  ClearanceTable, EventView, OverstockTable, ProductDialog, RegionView, ReplenishTable, RiskTable, ScenarioView,
} from './blocks';
import { Card, DataBadge, Empty, StockBasisNotice, ErrorState, HealthChip, inr, Kpi, Loading, num, RecommendationBadge, RISK_COLOR, shortDate } from './ui';

export const useTaxonomy = () => useQuery({ queryKey: ['inv', 'taxonomy'], queryFn: inventoryAiApi.taxonomy, staleTime: 600_000 });

/** Sport + category filters, limited to what exists. */
export const Filters = ({ sport, category, onChange, extra }: { sport: string; category: string; onChange: (s: string, c: string) => void; extra?: React.ReactNode }) => {
  const { data: tax = {} } = useTaxonomy();
  const cats = sport ? tax[sport] ?? [] : [...new Set(Object.values(tax).flat())].sort();
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center" data-testid="filters">
      <Select size="small" displayEmpty value={sport} onChange={(e) => onChange(e.target.value, '')} sx={{ minWidth: 150 }} inputProps={{ 'aria-label': 'Sport', 'data-testid': 'filter-sport' }}>
        <MenuItem value="">All sports</MenuItem>{Object.keys(tax).sort().map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
      </Select>
      <Select size="small" displayEmpty value={category} onChange={(e) => onChange(sport, e.target.value)} sx={{ minWidth: 150 }} inputProps={{ 'aria-label': 'Category', 'data-testid': 'filter-category' }}>
        <MenuItem value="">All categories</MenuItem>{cats.map((c) => <MenuItem key={c} value={c}>{c}</MenuItem>)}
      </Select>
      {extra}
    </Stack>
  );
};

const Box2 = ({ children }: { children: React.ReactNode }) => <Stack spacing={2}>{children}</Stack>;

// ------------------------------------------------------------------------------------------------------------ Overview
export const OverviewTab = () => {
  const nav = useNavigate();
  const [horizon, setHorizon] = useState<'7' | '30' | '90'>('30');
  const [sel, setSel] = useState<string | null>(null);
  const ov = useQuery({ queryKey: ['inv', 'overview'], queryFn: inventoryAiApi.overview });
  const fc = useQuery({ queryKey: ['inv', 'forecast', '', ''], queryFn: () => inventoryAiApi.forecast({}) });
  const risks = useQuery({ queryKey: ['inv', 'risk', 'top'], queryFn: () => inventoryAiApi.risk({ limit: 8 }) });
  const clear = useQuery({ queryKey: ['inv', 'clearance', '', ''], queryFn: () => inventoryAiApi.clearance({}) });
  const regions = useQuery({ queryKey: ['inv', 'regions', 'all'], queryFn: () => inventoryAiApi.regions({}) });
  if (ov.isError) return <ErrorState error={ov.error} onRetry={() => ov.refetch()} />;
  if (!ov.data) return <Loading />;
  const o = ov.data;
  const days = Number(horizon);
  const hist = (fc.data?.history ?? []).slice(-90);
  const daily = (fc.data?.daily ?? []).slice(0, days);
  const total = fc.data ? (typeof fc.data.totals[horizon] === 'number' ? (fc.data.totals[horizon] as number) : 0) : 0;
  return (
    <Box2>
      <StockBasisNotice />
      <Grid container spacing={1.5} data-testid="kpis">
        <Grid size={{ xs: 6, md: 2 }}><Kpi testId="kpi-total" label="Total SKUs" value={num(o.total)} sub={`${num(o.inventory_units)} units · ${inr(o.inventory_value_at_cost)} at cost`} color={colors.navy600} /></Grid>
        <Grid size={{ xs: 6, md: 2 }}><Kpi testId="kpi-at-risk" label="At risk" value={num(o.at_risk)} sub={`${o.stockout_high} high · ${o.stockout_medium} medium`} color="#F79009" onClick={() => nav('/inventory-ai/risk')} /></Grid>
        <Grid size={{ xs: 6, md: 2 }}><Kpi testId="kpi-stockout" label="Stock-out risk" value={num(o.stockout_high)} sub="high risk SKUs" color={RISK_COLOR.HIGH} onClick={() => nav('/inventory-ai/risk')} /></Grid>
        <Grid size={{ xs: 6, md: 2 }}><Kpi testId="kpi-overstock" label="Overstock" value={num(o.overstock)} sub={`${num(o.excess_units)} excess units · ${inr(o.excess_value)}`} color="#7A5AF8" onClick={() => nav('/inventory-ai/overstock')} /></Grid>
        <Grid size={{ xs: 6, md: 2 }}><Kpi testId="kpi-slow" label="Slow moving" value={num(o.slow_moving)} sub={`${o.critical_aging} critically aged`} color="#F04438" onClick={() => nav('/inventory-ai/overstock')} /></Grid>
        <Grid size={{ xs: 6, md: 2 }}><Kpi testId="kpi-aging" label="Aging > 90 days" value={num(o.aging_over_90)} sub="oldest stock" color="#912018" onClick={() => nav('/inventory-ai/overstock')} /></Grid>
      </Grid>
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, lg: 8 }}>
          <Card title="Demand forecast" subtitle={`All products · model ${o.model.model_name} ${o.model.model_version} · history shown is POC synthetic`} testId="overview-forecast"
            action={<ToggleButtonGroup size="small" exclusive value={horizon} onChange={(_, v) => v && setHorizon(v)} aria-label="Horizon">
              {['7', '30', '90'].map((h) => <ToggleButton key={h} value={h} data-testid={`horizon-${h}`} sx={{ px: 1.5, fontWeight: 700 }}>{h}d</ToggleButton>)}</ToggleButtonGroup>}>
            {fc.isError ? <ErrorState error={fc.error} /> : !fc.data ? <Loading rows={2} /> : (
              <>
                <Typography variant="h5" fontWeight={900} data-testid="forecast-total">{num(total)} <Typography component="span" variant="body2" color="text.secondary">units forecast for the next {horizon} days</Typography></Typography>
                <ForecastChart history={hist} daily={daily} testId="overview-chart" />
              </>
            )}
          </Card>
        </Grid>
        <Grid size={{ xs: 12, lg: 4 }}>
          <Card title="Top stock-out risks" testId="top-risks" action={<Button size="small" onClick={() => nav('/inventory-ai/risk')}>All</Button>}>
            {!risks.data ? <Loading rows={3} /> : <Stack spacing={1}>{risks.data.slice(0, 6).map((r) => (
              <Stack key={r.product_id} direction="row" spacing={1} alignItems="center" data-testid="top-risk-item" onClick={() => setSel(r.product_id)} sx={{ cursor: 'pointer' }}>
                <Chip size="small" label={r.risk_level} sx={{ bgcolor: RISK_COLOR[r.risk_level ?? 'LOW'], color: '#fff', fontWeight: 800, height: 20, minWidth: 64 }} />
                <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }} noWrap title={r.name}>{r.name}</Typography>
                <Typography variant="body2" fontWeight={800}>{r.days_of_cover == null ? '90+' : num(r.days_of_cover, 1)} d</Typography>
              </Stack>))}</Stack>}
          </Card>
        </Grid>
      </Grid>
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card title="Recommended actions" testId="recommended-actions" action={<RecommendationBadge />}>
            <Stack spacing={1}>
              <Action label="Replenish" detail={`${o.replenish_skus} SKUs · ${num(o.reorder_units)} units recommended`} color={colors.green} onClick={() => nav('/inventory-ai/replenishment')} testId="action-replenish" />
              <Action label="Review promotion" detail="Run a 30% / 50% what-if on slow-moving stock" color={colors.orange} onClick={() => nav('/inventory-ai/promotions')} testId="action-promotion" />
              <Action label="Clearance candidates" detail={`${o.clearance_candidates} products · ${num(clear.data?.total_excess_units ?? 0)} excess units`} color="#7A5AF8" onClick={() => nav('/inventory-ai/overstock')} testId="action-clearance" />
              {o.upcoming_events[0] && <Action label="Event readiness" detail={`${o.upcoming_events[0].name} in ${o.upcoming_events[0].starts_in_days} days`} color={colors.navy600} onClick={() => nav('/inventory-ai/events')} testId="action-event" />}
            </Stack>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card title="Regional demand (next 30 days)" subtitle="POC synthetic regions" testId="overview-regions">
            {!regions.data ? <Loading rows={2} /> : <BarList items={regions.data.regions.map((r) => ({ label: r.name, value: r.demand_30d, sub: `${num(r.days_of_cover, 0)} d cover` }))} />}
          </Card>
        </Grid>
      </Grid>
      <Typography variant="caption" color="text.secondary" data-testid="data-note">{o.data_note} Model trained {o.model.created_at.slice(0, 16).replace('T', ' ')} on history to {shortDate(o.model.training_end)}.</Typography>
      <ProductDialog productId={sel} onClose={() => setSel(null)} />
    </Box2>
  );
};

const Action = ({ label, detail, color, onClick, testId }: { label: string; detail: string; color: string; onClick: () => void; testId: string }) => (
  <Stack direction="row" spacing={1.5} alignItems="center" data-testid={testId} onClick={onClick}
    sx={{ p: 1.1, borderRadius: 2, border: `1px solid ${colors.line}`, cursor: 'pointer', '&:hover': { bgcolor: '#F7F9FC' } }}>
    <Box sx={{ width: 6, alignSelf: 'stretch', borderRadius: 3, bgcolor: color }} />
    <Box sx={{ flex: 1 }}><Typography fontWeight={800}>{label}</Typography><Typography variant="caption" color="text.secondary">{detail}</Typography></Box>
    <Button size="small" variant="outlined">Open</Button>
  </Stack>
);

// ------------------------------------------------------------------------------------------------------------ Forecast
export const ForecastTab = () => {
  const [sport, setSport] = useState('Cricket');
  const [category, setCategory] = useState('');
  const fc = useQuery({ queryKey: ['inv', 'forecast', sport, category], queryFn: () => inventoryAiApi.forecast({ sport, category }), retry: false });
  const quality = useQuery({ queryKey: ['inv', 'quality'], queryFn: inventoryAiApi.quality });
  const regions = useQuery({ queryKey: ['inv', 'regions', sport, category], queryFn: () => inventoryAiApi.regions({ sport, category }), retry: false });
  const m = quality.data?.model;
  const hold = m?.metrics.holdout[m.metrics.selected];
  const base = m?.metrics.holdout[m.metrics.baseline];
  return (
    <Box2>
      <Filters sport={sport} category={category} onChange={(s, c) => { setSport(s); setCategory(c); }} />
      <Card title="Demand forecast" subtitle="Persisted model output. Weekly seasonality, trend and annual season; event and promotion days excluded from the baseline." testId="forecast-card">
        {fc.isError ? <ErrorState error={fc.error} /> : !fc.data ? <Loading rows={2} /> : (
          <Stack spacing={1}>
            <Stack direction="row" spacing={3} flexWrap="wrap">
              {(['7', '30', '90'] as const).map((h) => <Box key={h}><Typography variant="caption" color="text.secondary">Next {h} days</Typography><Typography variant="h5" fontWeight={900} data-testid={`fc-${h}`}>{num(fc.data!.totals[h] as number)}</Typography></Box>)}
              <Box><Typography variant="caption" color="text.secondary">Products</Typography><Typography variant="h5" fontWeight={900}>{fc.data.products}</Typography></Box>
            </Stack>
            <ForecastChart history={fc.data.history} daily={fc.data.daily} />
            <Typography variant="caption" color="text.secondary">Model {fc.data.model_name} {fc.data.model_version} · forecast as of {shortDate(fc.data.as_of)} · history is <b>POC synthetic data</b></Typography>
          </Stack>
        )}
      </Card>
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card title="How accurate is it?" subtitle="Measured on a held-out period never used to choose or calibrate the model" testId="accuracy-card">
            {!m || !hold || !base ? <Loading rows={2} /> : (
              <Stack spacing={1}>
                <BarList testId="accuracy-bars" items={[{ label: `${m.metrics.selected} (selected)`, value: hold.wape * 100, color: colors.green }, { label: `${m.metrics.baseline} (baseline)`, value: base.wape * 100, color: '#9AA6BD' }]} format={(v) => `${num(v, 1)}% daily WAPE`} />
                <Typography variant="body2">WAPE of 7 / 30 / 90-day totals: <b>{['7', '30', '90'].map((h) => hold.sum_wape[h] == null ? '—' : `${num(hold.sum_wape[h]! * 100, 1)}%`).join(' / ')}</b> (baseline {['7', '30', '90'].map((h) => base.sum_wape[h] == null ? '—' : `${num(base.sum_wape[h]! * 100, 1)}%`).join(' / ')}).</Typography>
                <Typography variant="body2">80% interval coverage on held-out data: {['7', '30', '90'].map((h) => m.metrics.interval_coverage_holdout[h] == null ? '—' : `${num(m.metrics.interval_coverage_holdout[h]! * 100)}%`).join(' / ')} (target 80%).</Typography>
                <Alert severity="warning" sx={{ py: 0 }}>{m.metrics.note}</Alert>
              </Stack>
            )}
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card title="Regional demand and stock" testId="forecast-regions">{regions.isError ? <ErrorState error={regions.error} /> : !regions.data ? <Loading rows={2} /> : <RegionView data={regions.data} />}</Card>
        </Grid>
      </Grid>
      <Card title="Data quality" subtitle="Reported, never silently corrected" testId="data-quality-card">
        {!quality.data ? <Loading rows={2} /> : (
          <Stack spacing={0.5}>{quality.data.data_quality.checks.map((c) => (
            <Stack direction="row" spacing={1} key={c.check} alignItems="flex-start"><Chip size="small" label={c.status} color={c.status === 'OK' ? 'success' : c.status === 'INFO' ? 'default' : 'warning'} sx={{ height: 20, minWidth: 52 }} /><Typography variant="body2"><b>{c.check.replace(/_/g, ' ')}</b>: {c.detail}</Typography></Stack>))}</Stack>
        )}
      </Card>
    </Box2>
  );
};

// ------------------------------------------------------------------------------------------------------------ Stock risk
export const RiskTab = () => {
  const [sport, setSport] = useState('');
  const [category, setCategory] = useState('');
  const [level, setLevel] = useState('');
  const [sel, setSel] = useState<string | null>(null);
  const risks = useQuery({ queryKey: ['inv', 'risk', sport, category, level], queryFn: () => inventoryAiApi.risk({ sport, category, level, limit: 60 }) });
  return (
    <Box2>
      <StockBasisNotice />
      <Filters sport={sport} category={category} onChange={(s, c) => { setSport(s); setCategory(c); }} extra={
        <ToggleButtonGroup size="small" exclusive value={level} onChange={(_, v) => setLevel(v ?? '')} aria-label="Risk level">
          <ToggleButton value="">At risk</ToggleButton><ToggleButton value="HIGH" data-testid="level-high">High</ToggleButton><ToggleButton value="MEDIUM">Medium</ToggleButton><ToggleButton value="LOW">Low</ToggleButton>
        </ToggleButtonGroup>} />
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, xl: 8 }}>
          <Card title="Stock-out risk ranking" subtitle="Days of cover from the persisted forecast, supplier lead time and open orders. Click a product for the reasons." testId="risk-card">
            {risks.isError ? <ErrorState error={risks.error} /> : !risks.data ? <Loading /> : <RiskTable rows={risks.data} onSelect={setSel} />}
          </Card>
        </Grid>
        <Grid size={{ xs: 12, xl: 4 }}>
          <Card title="Inventory coverage (days)" subtitle="Lowest cover first" testId="coverage-card">
            {!risks.data ? <Loading rows={2} /> : <BarList testId="coverage-bars" items={risks.data.slice(0, 10).map((r) => ({ label: r.name, value: r.days_of_cover ?? 90, color: RISK_COLOR[r.risk_level ?? 'LOW'], sub: `lead ${r.lead_time_days ?? '—'} d` }))} format={(v) => `${num(v, 1)} d`} />}
          </Card>
        </Grid>
      </Grid>
      <ProductDialog productId={sel} onClose={() => setSel(null)} />
    </Box2>
  );
};

// ------------------------------------------------------------------------------------------------------------ Replenishment
export const ReplenishmentTab = () => {
  const [sport, setSport] = useState('');
  const [category, setCategory] = useState('');
  const [sel, setSel] = useState<string | null>(null);
  const q = useQuery({ queryKey: ['inv', 'replenishment', sport, category], queryFn: () => inventoryAiApi.replenishment({ sport, category }) });
  const units = (q.data?.recommendations ?? []).reduce((s, r) => s + (r.reorder_qty ?? 0), 0);
  return (
    <Box2>
      <StockBasisNotice />
      <Filters sport={sport} category={category} onChange={(s, c) => { setSport(s); setCategory(c); }} />
      <Card title="Replenishment recommendations" testId="replenishment-card" action={<RecommendationBadge />}
        subtitle="Order quantity = demand over lead time + review period + safety stock − on hand − incoming, rounded up to case pack and MOQ">
        {q.isError ? <ErrorState error={q.error} /> : !q.data ? <Loading /> : (
          <Stack spacing={1}>
            <Alert severity="info" data-testid="no-po-notice">{q.data.recommendation_status} {q.data.recommendations.length} SKUs need action; {num(units)} units recommended in total. Purchase orders are raised by the buying team.</Alert>
            <ReplenishTable rows={q.data.recommendations} onSelect={setSel} />
          </Stack>
        )}
      </Card>
      <ProductDialog productId={sel} onClose={() => setSel(null)} />
    </Box2>
  );
};

// ------------------------------------------------------------------------------------------------------------ Overstock
export const OverstockTab = () => {
  const [sport, setSport] = useState('');
  const [category, setCategory] = useState('');
  const [sel, setSel] = useState<string | null>(null);
  const over = useQuery({ queryKey: ['inv', 'overstock', sport, category], queryFn: () => inventoryAiApi.overstock({ sport, category }) });
  const slow = useQuery({ queryKey: ['inv', 'slow', sport, category], queryFn: () => inventoryAiApi.slowMoving({ sport, category }) });
  const clear = useQuery({ queryKey: ['inv', 'clearance', sport, category], queryFn: () => inventoryAiApi.clearance({ sport, category }) });
  const aged = useQuery({ queryKey: ['inv', 'aging', sport, category], queryFn: () => inventoryAiApi.aging({ sport, category }) });
  const dist = (aged.data?.buckets ?? []).map((b) => ({ label: b.bucket, value: b.skus, sub: `${num(b.units)} units · ${inr(b.value)}`,
    color: b.bucket.startsWith('120') ? '#912018' : b.bucket.startsWith('90') ? '#F04438' : b.bucket.startsWith('60') ? '#F79009' : colors.green }));
  return (
    <Box2>
      <StockBasisNotice />
      <Filters sport={sport} category={category} onChange={(s, c) => { setSport(s); setCategory(c); }} />
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, xl: 7 }}>
          <Card title="Clearance candidates" subtitle="Slow-moving or critically aged stock with excess over the 90-day forecast; discount from the scenario engine" testId="clearance-card" action={<RecommendationBadge />}>
            {clear.isError ? <ErrorState error={clear.error} /> : !clear.data ? <Loading rows={3} /> : (
              <Stack spacing={1}>
                <Typography variant="body2" data-testid="clearance-total">{clear.data.products} candidates · {num(clear.data.total_excess_units)} excess units · {inr(clear.data.total_excess_value)} at cost. {clear.data.recommendation_status}</Typography>
                <ClearanceTable rows={clear.data.candidates} />
              </Stack>
            )}
          </Card>
        </Grid>
        <Grid size={{ xs: 12, xl: 5 }}>
          <Card title="Inventory aging" subtitle="Products by age of their oldest stock" testId="aging-card">
            {!aged.data ? <Loading rows={2} /> : <BarList testId="aging-bars" items={dist} format={(v) => `${num(v)} SKUs`} />}
          </Card>
        </Grid>
      </Grid>
      <Card title="Overstocked products" subtitle="High: at least 180 days of cover; medium: at least 120" testId="overstock-card">
        {over.isError ? <ErrorState error={over.error} /> : !over.data ? <Loading /> : <OverstockTable rows={over.data} onSelect={setSel} />}
      </Card>
      <Card title="Slow-moving and critically aged" subtitle="Classes: Healthy · Watch · Slow Moving · Critical Aging" testId="slow-card">
        {!slow.data ? <Loading rows={3} /> : <Stack spacing={1}><Stack direction="row" spacing={1}>{['Slow Moving', 'Critical Aging'].map((c) => <Stack key={c} direction="row" spacing={0.5} alignItems="center"><HealthChip value={c} /><b>{slow.data!.filter((x) => x.health_class === c).length}</b></Stack>)}</Stack><OverstockTable rows={slow.data} onSelect={setSel} testId="slow-table" /></Stack>}
      </Card>
      <ProductDialog productId={sel} onClose={() => setSel(null)} />
    </Box2>
  );
};

// ------------------------------------------------------------------------------------------------------------ Promotions
export const PromotionsTab = () => {
  const [sport, setSport] = useState('');
  const [category, setCategory] = useState('');
  const [slowOnly, setSlowOnly] = useState(true);
  const [discounts, setDiscounts] = useState<number[]>([30, 50]);
  const [days, setDays] = useState(14);
  const run = useMutation({ mutationFn: () => inventoryAiApi.scenario({ sport: sport || undefined, category: category || undefined, discounts, days, slowMovingOnly: slowOnly }) });
  return (
    <Box2>
      <Card title="Promotion and clearance what-if" subtitle="A calculation from the persisted forecast and the estimated price elasticity. It never changes a price: the DSports pricing engine remains authoritative." testId="promotion-builder">
        <Stack spacing={1.5}>
          <Filters sport={sport} category={category} onChange={(s, c) => { setSport(s); setCategory(c); }} extra={
            <ToggleButton size="small" value="slow" selected={slowOnly} onChange={() => setSlowOnly(!slowOnly)} data-testid="slow-only">Slow-moving & aged only</ToggleButton>} />
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" fontWeight={700}>Discounts</Typography>
            {[10, 20, 30, 40, 50].map((d) => <Chip key={d} label={`${d}%`} clickable data-testid={`discount-${d}`} color={discounts.includes(d) ? 'primary' : 'default'}
              onClick={() => setDiscounts(discounts.includes(d) ? discounts.filter((x) => x !== d) : [...discounts, d].sort((a, b) => a - b))} />)}
            <TextField size="small" type="number" label="Days" value={days} onChange={(e) => setDays(Math.max(3, Math.min(60, Number(e.target.value) || 14)))} sx={{ width: 90 }} />
            <Button variant="contained" disabled={!discounts.length || run.isPending} onClick={() => run.mutate()} data-testid="run-scenario" sx={{ bgcolor: colors.green }}>Run scenario</Button>
          </Stack>
        </Stack>
      </Card>
      {run.isPending && <Loading rows={3} />}
      {run.isError && <ErrorState error={run.error} />}
      {run.data && <Card title="Scenario comparison" testId="scenario-card"><ScenarioView data={run.data} /></Card>}
      {!run.data && !run.isPending && !run.isError && <Empty>Choose the discounts to compare and run the scenario.</Empty>}
    </Box2>
  );
};

// ------------------------------------------------------------------------------------------------------------ Events
export const EventsTab = () => {
  const events = useQuery({ queryKey: ['inv', 'events'], queryFn: inventoryAiApi.events });
  const [picked, setPicked] = useState<string | null>(null);
  const [sel, setSel] = useState<string | null>(null);
  const upcoming = (events.data ?? []).filter((e) => e.status === 'UPCOMING');
  const ordered = [...(events.data ?? [])].sort((a, b) => (a.status === b.status ? a.starts_in_days - b.starts_in_days : a.status === 'UPCOMING' ? -1 : 1));
  const id = picked ?? upcoming[0]?.id ?? null;
  const impact = useQuery({ queryKey: ['inv', 'event', id], queryFn: () => inventoryAiApi.eventImpact(id!), enabled: !!id });
  const ev = events.data?.find((e) => e.id === id);
  const regions = useQuery({ queryKey: ['inv', 'regions', 'event', id], queryFn: () => inventoryAiApi.regions({ sport: ev!.sport, event_id: id! }), enabled: !!ev });
  const repl = useQuery({ queryKey: ['inv', 'replenishment', 'event', id], queryFn: () => inventoryAiApi.replenishment({ sport: ev!.sport, scenario: `EVENT:${id}` }), enabled: !!ev });
  return (
    <Box2>
      <Card title="Events" subtitle="POC reference data: a small calendar of tournaments, marathons and Olympics" testId="events-card">
        {events.isError ? <ErrorState error={events.error} /> : !events.data ? <Loading rows={2} /> : (
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>{ordered.map((e) => (
            <Chip key={e.id} clickable onClick={() => setPicked(e.id)} data-testid={`event-${e.id}`} variant={e.id === id ? 'filled' : 'outlined'} color={e.status === 'UPCOMING' ? 'primary' : 'default'}
              label={`${e.name.replace(' (POC)', '')} · ${e.status === 'UPCOMING' ? `in ${e.starts_in_days} d` : 'past'}`} />))}</Stack>
        )}
      </Card>
      {id && (impact.isError ? <ErrorState error={impact.error} /> : !impact.data ? <Loading /> : <EventView data={impact.data} onSelect={setSel} />)}
      {ev?.status === 'UPCOMING' && (
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, lg: 7 }}><Card title="Replenish before the event" testId="event-replenishment" action={<RecommendationBadge />}>{!repl.data ? <Loading rows={2} /> : <ReplenishTable rows={repl.data.recommendations} onSelect={setSel} testId="event-replenishment-table" />}</Card></Grid>
          <Grid size={{ xs: 12, lg: 5 }}><Card title="Regional demand during the event" testId="event-regions">{!regions.data ? <Loading rows={2} /> : <RegionView data={regions.data} />}</Card></Grid>
        </Grid>
      )}
      <ProductDialog productId={sel} onClose={() => setSel(null)} />
    </Box2>
  );
};

export { DataBadge };
