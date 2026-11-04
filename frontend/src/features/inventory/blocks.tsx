import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogContent, DialogTitle, Divider, IconButton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import Grid from '@mui/material/Grid2';
import { inventoryAiApi } from '@/api/inventoryAi';
import { colors } from '@/design/tokens';
import type {
  ClearanceCandidate, InvAssessment, InvEventImpact, InvForecast, InvRegions, InvScenario,
} from '@/types/inventoryAi';
import { BarList, ForecastChart, MiniBars } from './charts';
import { ActionChip, Card, Empty, ErrorState, HealthChip, inr, Loading, num, RecommendationBadge, RiskChip, shortDate } from './ui';

const Scroll = ({ children, testId }: { children: React.ReactNode; testId?: string }) => (
  <TableContainer data-testid={testId} sx={{ maxHeight: 520 }}><Table size="small" stickyHeader>{children}</Table></TableContainer>
);
const Th = ({ children, right }: { children?: React.ReactNode; right?: boolean }) => (
  <TableCell align={right ? 'right' : 'left'} sx={{ fontWeight: 800, fontSize: 12, whiteSpace: 'nowrap', bgcolor: '#F7F9FC' }}>{children}</TableCell>
);
const Td = ({ children, right, bold }: { children?: React.ReactNode; right?: boolean; bold?: boolean }) => (
  <TableCell align={right ? 'right' : 'left'} sx={{ fontSize: 13, fontWeight: bold ? 700 : 400, py: 0.9 }}>{children}</TableCell>
);
const Name = ({ a, onClick }: { a: { name: string; sport?: string; category?: string }; onClick?: () => void }) => (
  <Box onClick={onClick} sx={{ cursor: onClick ? 'pointer' : 'default', minWidth: 190 }}>
    <Typography variant="body2" fontWeight={700} sx={onClick ? { '&:hover': { color: colors.greenDark } } : undefined}>{a.name}</Typography>
    <Typography variant="caption" color="text.secondary">{[a.sport, a.category].filter(Boolean).join(' · ')}</Typography>
  </Box>
);

/** Plain-language reasons built ONLY from the persisted numbers (no model). */
export const explainRisk = (a: InvAssessment): string[] => {
  const out: string[] = [];
  if (a.status !== 'OK' && a.status !== 'MISSING_LEAD_TIME') return [a.reason ?? 'Insufficient data to produce a reliable forecast.'];
  if (a.days_of_cover != null && a.lead_time_days != null) {
    out.push(`${num(a.on_hand)} units on hand give ${num(a.days_of_cover, 1)} days of cover against a supplier lead time of ${a.lead_time_days} days${a.days_of_cover < a.lead_time_days ? ': stock runs out before a new order could arrive' : ''}.`);
  } else if (a.days_of_cover == null) {
    out.push(`${num(a.on_hand)} units on hand and ${num(a.incoming)} incoming cover the 90-day forecast.`);
  }
  if (a.p_stockout_lt != null) out.push(`The chance of running out before replenishment is ${num(a.p_stockout_lt * 100)}%.`);
  if (a.detail?.incoming_arrives_after_stockout) out.push(`An open order of ${num(a.incoming)} units arrives in ${a.detail.next_arrival_in_days} days, after the projected stock-out${a.stockout_date ? ` (${shortDate(a.stockout_date)})` : ''}.`);
  if (a.detail?.demand_trend_pct != null) out.push(`Demand has moved ${a.detail.demand_trend_pct > 0 ? '+' : ''}${num(a.detail.demand_trend_pct, 1)}% over the last 4 weeks versus the 8 before.`);
  if (a.reorder_qty) out.push(`Recommended order: ${num(a.reorder_qty)} units by ${shortDate(a.order_by)} (target ${num(a.detail.target_level)} = demand over lead time + review period + safety stock ${num(a.safety_stock)}, less position).`);
  if (a.detail?.event) out.push(`Event-adjusted: ${a.detail.event.name} multiplies demand by ${num(a.detail.event.uplift, 2)} (${a.detail.event.basis === 'POC_ASSUMPTION' ? 'POC assumption' : 'estimated from past events'}).`);
  return out;
};

export const ProductDialog = ({ productId, onClose }: { productId: string | null; onClose: () => void }) => {
  const [data, setData] = useState<InvForecast | null>(null);
  const [err, setErr] = useState<unknown>(null);
  useEffect(() => {
    setData(null); setErr(null);
    if (productId) inventoryAiApi.productForecast(productId).then(setData).catch(setErr);
  }, [productId]);
  const a = data?.assessment;
  return (
    <Dialog open={!!productId} onClose={onClose} maxWidth="md" fullWidth data-testid="product-dialog">
      <DialogTitle sx={{ pr: 6 }}>
        {data?.name ?? 'Product'}
        <IconButton onClick={onClose} sx={{ position: 'absolute', right: 8, top: 8 }} aria-label="Close"><CloseIcon /></IconButton>
      </DialogTitle>
      <DialogContent>
        {err ? <ErrorState error={err} /> : !data ? <Loading /> : data.status && data.status !== 'OK' ? <Alert severity="info" data-testid="insufficient">{data.reason}</Alert> : (
          <Stack spacing={2}>
            <ForecastChart history={data.history} daily={data.daily} testId="product-forecast-chart" />
            {a && (
              <Grid container spacing={1.5}>
                {[['On hand', num(a.on_hand)], ['Incoming', num(a.incoming)], ['Days of cover', a.days_of_cover == null ? '90+' : num(a.days_of_cover, 1)], ['Lead time', `${a.lead_time_days ?? '—'} days`],
                  ['Forecast 7/30/90 d', `${num(a.forecast_7)} / ${num(a.forecast_30)} / ${num(a.forecast_90)}`], ['Safety stock', num(a.safety_stock)],
                  ['Supplier', a.supplier ?? '—'], ['Oldest stock', `${num(a.age_days)} days`]].map(([k, v]) => (
                  <Grid key={k} size={{ xs: 6, md: 3 }}><Typography variant="caption" color="text.secondary">{k}</Typography><Typography fontWeight={800}>{v}</Typography></Grid>
                ))}
              </Grid>
            )}
            {a && (
              <Box data-testid="why-this-rating">
                <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.75 }}><Typography fontWeight={800}>Why this rating</Typography><RiskChip level={a.risk_level} /><HealthChip value={a.health_class} /></Stack>
                <Stack component="ul" spacing={0.5} sx={{ m: 0, pl: 2.5 }}>{explainRisk(a).map((t) => <Typography component="li" variant="body2" key={t}>{t}</Typography>)}</Stack>
              </Box>
            )}
            <Typography variant="caption" color="text.secondary">Model {data.model_name} {data.model_version}. Forecast and reasons are calculated by deterministic code from persisted model output; no language model is involved.</Typography>
          </Stack>
        )}
      </DialogContent>
    </Dialog>
  );
};

export const RiskTable = ({ rows, onSelect, testId = 'risk-table' }: { rows: InvAssessment[]; onSelect?: (id: string) => void; testId?: string }) => {
  if (!rows.length) return <Empty>No products match.</Empty>;
  return (
    <Scroll testId={testId}>
      <TableHead><TableRow><Th>Product</Th><Th>Risk</Th><Th right>Days of cover</Th><Th right>On hand</Th><Th right>Incoming</Th><Th right>Lead time</Th><Th right>P(stock-out)</Th><Th>Action</Th></TableRow></TableHead>
      <TableBody>
        {rows.map((a) => (
          <TableRow key={a.product_id} hover data-testid="risk-row">
            <Td><Name a={a} onClick={onSelect ? () => onSelect(a.product_id) : undefined} /></Td>
            <Td><RiskChip level={a.risk_level} /></Td>
            <Td right bold>{a.days_of_cover == null ? '90+' : num(a.days_of_cover, 1)}</Td>
            <Td right>{num(a.on_hand)}</Td><Td right>{num(a.incoming)}</Td><Td right>{a.lead_time_days ?? '—'} d</Td>
            <Td right>{a.p_stockout_lt == null ? '—' : `${num(a.p_stockout_lt * 100)}%`}</Td>
            <Td><ActionChip a={a} /></Td>
          </TableRow>
        ))}
      </TableBody>
    </Scroll>
  );
};

export const ReplenishTable = ({ rows, onSelect, testId = 'replenishment-table' }: { rows: InvAssessment[]; onSelect?: (id: string) => void; testId?: string }) => {
  if (!rows.length) return <Empty>No replenishment is needed right now.</Empty>;
  return (
    <Scroll testId={testId}>
      <TableHead><TableRow><Th>Product</Th><Th>Priority</Th><Th right>Order qty</Th><Th>Order by</Th><Th>Arrives</Th><Th right>On hand</Th><Th right>Incoming</Th><Th right>Demand in lead time</Th><Th right>Safety stock</Th><Th>Supplier</Th></TableRow></TableHead>
      <TableBody>
        {rows.map((a) => (
          <TableRow key={a.product_id} hover data-testid="replenishment-row">
            <Td><Name a={a} onClick={onSelect ? () => onSelect(a.product_id) : undefined} /></Td>
            <Td>{a.detail?.action === 'EXPEDITE_INCOMING' ? <ActionChip a={a} /> : <RiskChip level={a.priority} />}</Td>
            <Td right bold>{a.reorder_qty ? num(a.reorder_qty) : '—'}</Td>
            <Td>{a.reorder_qty ? shortDate(a.order_by) : '—'}</Td><Td>{a.reorder_qty ? shortDate(a.arrival_date) : '—'}</Td>
            <Td right>{num(a.on_hand)}</Td><Td right>{num(a.incoming)}</Td><Td right>{num(a.detail?.demand_during_lead_time, 1)}</Td><Td right>{num(a.safety_stock)}</Td>
            <Td>{a.supplier ?? '—'}</Td>
          </TableRow>
        ))}
      </TableBody>
    </Scroll>
  );
};

export const OverstockTable = ({ rows, onSelect, testId = 'overstock-table' }: { rows: InvAssessment[]; onSelect?: (id: string) => void; testId?: string }) => {
  if (!rows.length) return <Empty>No overstocked or aged products match.</Empty>;
  return (
    <Scroll testId={testId}>
      <TableHead><TableRow><Th>Product</Th><Th>Class</Th><Th right>On hand</Th><Th right>90-day forecast</Th><Th right>Excess units</Th><Th right>Excess value</Th><Th right>Days of cover</Th><Th right>Oldest stock</Th></TableRow></TableHead>
      <TableBody>
        {rows.map((a) => (
          <TableRow key={a.product_id} hover data-testid="overstock-row">
            <Td><Name a={a} onClick={onSelect ? () => onSelect(a.product_id) : undefined} /></Td>
            <Td><HealthChip value={a.health_class} /></Td>
            <Td right>{num(a.on_hand)}</Td><Td right>{num(a.forecast_90)}</Td><Td right bold>{num(a.excess_units)}</Td><Td right>{inr(a.excess_value)}</Td>
            <Td right>{num(a.coverage_days)}</Td><Td right>{num(a.age_days)} d</Td>
          </TableRow>
        ))}
      </TableBody>
    </Scroll>
  );
};

export const ClearanceTable = ({ rows, testId = 'clearance-table' }: { rows: ClearanceCandidate[]; testId?: string }) => {
  if (!rows.length) return <Empty>No clearance candidates.</Empty>;
  return (
    <Scroll testId={testId}>
      <TableHead><TableRow><Th>Product</Th><Th right>Stock</Th><Th right>Age</Th><Th right>90-day forecast</Th><Th right>Excess</Th><Th>Recommended scenario</Th><Th right>Units sold (30 d)</Th></TableRow></TableHead>
      <TableBody>
        {rows.map((c) => (
          <TableRow key={c.product_id} hover data-testid="clearance-row">
            <Td><Name a={c} /></Td><Td right>{num(c.on_hand)}</Td><Td right>{c.age_days} d</Td><Td right>{num(c.forecast_90)}</Td>
            <Td right bold>{num(c.excess_units)} · {inr(c.excess_value)}</Td>
            <Td>{c.recommended_discount_pct ? <Chip size="small" label={`${c.recommended_discount_pct}% off`} sx={{ bgcolor: colors.green, color: '#fff', fontWeight: 800, height: 22 }} /> : <Typography variant="caption" color="text.secondary">No profitable discount</Typography>}</Td>
            <Td right>{num(c.expected_units_sold_30d, 1)}</Td>
          </TableRow>
        ))}
      </TableBody>
    </Scroll>
  );
};

export const ScenarioView = ({ data }: { data: InvScenario }) => {
  const s = data.scenarios;
  const label = (d: number) => (d === 0 ? 'Baseline' : `${d}% off`);
  const rec = data.recommended_discount_pct;
  const items = (f: (x: (typeof s)[number]) => number) => s.map((x) => ({ label: label(x.discount_pct), value: f(x), highlight: rec != null && x.discount_pct === rec }));
  return (
    <Stack spacing={2} data-testid="scenario-view">
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap"><RecommendationBadge /><Typography variant="caption" color="text.secondary">{data.products} products · {data.duration_days} days · calculation only: prices are never changed here</Typography></Stack>
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 3 }}><MiniBars testId="chart-units" title="Units sold" items={items((x) => x.units_sold)} format={(v) => num(v)} /></Grid>
        <Grid size={{ xs: 12, md: 3 }}><MiniBars testId="chart-revenue" title="Revenue" items={items((x) => x.revenue)} format={inr} /></Grid>
        <Grid size={{ xs: 12, md: 3 }}><MiniBars testId="chart-margin" title="Margin" items={items((x) => x.margin)} format={inr} /></Grid>
        <Grid size={{ xs: 12, md: 3 }}><MiniBars testId="chart-reduction" title="Inventory reduction (units)" items={items((x) => x.inventory_reduction_units)} format={(v) => num(v)} /></Grid>
      </Grid>
      <Scroll testId="scenario-table">
        <TableHead><TableRow><Th />{s.map((x) => <Th key={x.discount_pct} right>{label(x.discount_pct)}</Th>)}</TableRow></TableHead>
        <TableBody>
          {([['Expected units sold', (x: (typeof s)[number]) => num(x.units_sold)], ['Range (elasticity uncertainty)', (x: (typeof s)[number]) => `${num(x.units_low)}–${num(x.units_high)}`],
            ['Revenue', (x: (typeof s)[number]) => inr(x.revenue)], ['Margin', (x: (typeof s)[number]) => inr(x.margin)], ['Ending stock', (x: (typeof s)[number]) => num(x.ending_stock)],
            ['Inventory reduction vs baseline', (x: (typeof s)[number]) => `${num(x.inventory_reduction_units)} units · ${inr(x.inventory_reduction_value)}`],
            ['Products at stock-out risk', (x: (typeof s)[number]) => num(x.products_at_stockout_risk)], ['Lowest unit margin', (x: (typeof s)[number]) => (x.min_unit_margin_pct == null ? '—' : `${num(x.min_unit_margin_pct, 1)}%`)],
            ['Net benefit vs baseline', (x: (typeof s)[number]) => inr(x.net_benefit)]] as const).map(([k, f]) => (
            <TableRow key={k}><Td bold>{k}</Td>{s.map((x) => <Td key={x.discount_pct} right>{f(x)}</Td>)}</TableRow>
          ))}
        </TableBody>
      </Scroll>
      <Alert severity={rec != null ? 'success' : 'warning'} data-testid="scenario-conclusion">
        {rec != null ? <>Recommended uniform discount: <b>{rec}% off</b> (best net benefit with every product above the {data.min_margin_pct}% margin floor).</>
          : <>No single discount keeps every product above the {data.min_margin_pct}% margin floor{data.selective?.discounted_products ? `; discounting ${data.selective.discounted_products} of ${data.selective.products} products individually gives a net benefit of ${inr(data.selective.net_benefit)}` : '; keep the current price'}.</>}
      </Alert>
      {data.selective && data.selective.discounted_products > 0 && (
        <Card title="Selective plan" subtitle="each product at its own best discount (margin floor respected)" testId="selective-plan">
          <Typography variant="body2">{data.selective.discounted_products} of {data.selective.products} products discounted · units {num(data.selective.units_sold)} · revenue {inr(data.selective.revenue)} · margin {inr(data.selective.margin)} · inventory reduction {num(data.selective.inventory_reduction_units)} units</Typography>
          <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>{data.selective.discount_by_product.slice(0, 12).map((p) => <Chip key={p.name} size="small" label={`${p.name}: ${p.discount_pct}%`} />)}</Stack>
        </Card>
      )}
      <Typography variant="caption" color="text.secondary" component="div" data-testid="scenario-assumptions">
        <b>Assumptions:</b> {data.assumptions.join(' · ')}. Elasticity: {data.elasticity.map((e) => `${e.category} ${num(e.elasticity, 2)} (${e.basis === 'HISTORICAL' ? 'measured' : 'POC assumption'})`).join(', ')}.
      </Typography>
    </Stack>
  );
};

export const RegionView = ({ data }: { data: InvRegions }) => (
  <Stack spacing={1.5} data-testid="region-view">
    <Scroll testId="region-table">
      <TableHead><TableRow><Th>Region</Th><Th right>Demand (next 30 d)</Th><Th right>Share</Th><Th right>Stock</Th><Th right>Days of cover</Th><Th right>Demand vs previous 90 d</Th></TableRow></TableHead>
      <TableBody>
        {data.regions.map((r) => {
          const heat = Math.min(1, r.demand_share_pct / 40);
          return (
            <TableRow key={r.region} data-testid="region-row">
              <Td bold>{r.name}</Td><Td right>{num(r.demand_30d)}</Td>
              <Td right><Box sx={{ display: 'inline-block', px: 1, borderRadius: 1, bgcolor: `rgba(22,163,74,${0.12 + heat * 0.5})`, fontWeight: 700 }}>{num(r.demand_share_pct, 1)}%</Box></Td>
              <Td right>{num(r.stock)}</Td><Td right>{r.days_of_cover == null ? '—' : num(r.days_of_cover, 1)}</Td>
              <Td right>{r.growth_pct == null ? '—' : `${r.growth_pct > 0 ? '+' : ''}${num(r.growth_pct, 1)}%`}</Td>
            </TableRow>
          );
        })}
      </TableBody>
    </Scroll>
    {data.transfers.length > 0 ? (
      <Alert severity="info" data-testid="transfers">Suggested transfers (recommendation only): {data.transfers.map((t) => `${num(t.units)} units ${t.from} → ${t.to}`).join('; ')}</Alert>
    ) : <Typography variant="caption" color="text.secondary" data-testid="no-transfers">No transfer is suggested: no region is above 60 days of cover while another is below 21.</Typography>}
    <Typography variant="caption" color="text.secondary">{data.note}</Typography>
  </Stack>
);

const BASIS_LABEL: Record<string, string> = {
  HISTORICAL: 'measured at past events of this sport',
  HISTORICAL_SPORT_LEVEL: 'sport-wide average (this category was not measurable)',
  HISTORICAL_OTHER_SPORT: 'borrowed from other sports',
  POC_ASSUMPTION: 'POC assumption, not measured',
};

export const EventView = ({ data, onSelect }: { data: InvEventImpact; onSelect?: (id: string) => void }) => {
  const short = data.readiness.filter((r) => (r.projected_shortage_by_event_end ?? 0) > 0);
  return (
    <Stack spacing={2} data-testid="event-view">
      <Alert severity="info"><b>{data.event.name}</b>: demand window {shortDate(data.event.start_date)} – {shortDate(data.event.end_date)} (starts in {data.event.starts_in_days} days). {data.assumption_note}</Alert>
      <Card title="Estimated demand uplift by category" subtitle="from past events of the same sport; range = 10th–90th percentile across products" testId="uplift-card">
        <BarList testId="uplift-bars" items={data.impact.map((i) => ({ label: i.category, value: i.uplift, sub: `×${num(i.uplift_lo, 2)}–${num(i.uplift_hi, 2)} · ${BASIS_LABEL[i.basis] ?? i.basis}${i.basis === 'HISTORICAL' ? ` (${i.n_events} past event${i.n_events === 1 ? '' : 's'})` : ''}`,
          color: i.basis === 'POC_ASSUMPTION' ? colors.orange : i.basis === 'HISTORICAL' ? colors.green : '#7AA989' }))} format={(v) => `×${num(v, 2)}`} />
      </Card>
      <Card title={`Readiness: ${short.length} of ${data.readiness.length} SKUs projected short by the end of the event`} testId="readiness-card">
        <Scroll testId="readiness-table">
          <TableHead><TableRow><Th>Product</Th><Th>Risk</Th><Th right>On hand</Th><Th right>Incoming</Th><Th right>Baseline demand in window</Th><Th right>Event-adjusted demand</Th><Th right>Projected shortage</Th><Th right>Order qty</Th><Th>Order by</Th></TableRow></TableHead>
          <TableBody>
            {data.readiness.map((r) => (
              <TableRow key={r.product_id} hover data-testid="readiness-row">
                <Td><Name a={r} onClick={onSelect ? () => onSelect(r.product_id) : undefined} /></Td><Td><RiskChip level={r.risk_level} /></Td><Td right>{num(r.on_hand)}</Td><Td right>{num(r.incoming)}</Td>
                <Td right>{num(r.baseline_window_demand, 0)}</Td><Td right>{num(r.event_window_demand, 0)}</Td>
                <Td right bold>{(r.projected_shortage_by_event_end ?? 0) > 0 ? num(r.projected_shortage_by_event_end) : '—'}</Td><Td right>{r.reorder_qty ? num(r.reorder_qty) : '—'}</Td><Td>{r.reorder_qty ? shortDate(r.order_by) : '—'}</Td>
              </TableRow>
            ))}
          </TableBody>
        </Scroll>
      </Card>
    </Stack>
  );
};

export const BlockDivider = () => <Divider sx={{ my: 1 }} />;
export { Button };
