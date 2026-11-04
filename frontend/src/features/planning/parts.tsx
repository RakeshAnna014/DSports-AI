import { createContext, useContext } from 'react';
import { Alert, Box, Chip, Stack, Typography } from '@mui/material';
import { ConfidenceMeter, KpiCard, ProvenanceChip, SectionCard } from '@/components/analytics';
import type { Confidence, Filters, PlanStatus, Source } from '@/api/planning';

export const n = (v: number | null | undefined, d = 0) => (v === null || v === undefined || Number.isNaN(v) ? '—' : v.toLocaleString('en-IN', { minimumFractionDigits: d, maximumFractionDigits: d }));
export const signed = (v: number | null | undefined, d = 0) => (v === null || v === undefined ? '—' : `${v > 0 ? '+' : ''}${n(v, d)}`);
export const pctText = (v: number | null | undefined, d = 1) => (v === null || v === undefined ? '—' : `${n(v, d)}%`);
export const pretty = (s?: string | null) => (s ? s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : '—');
export const day = (iso?: string | null) => (iso ? iso.slice(0, 10) : '—');
export const when = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

/** The effective forecast of a plan line: the approved one if the plan was approved, else the manager's, else the model's. */
export const usedForecast = (l: { approved_forecast: number | null; manager_forecast: number | null; model_forecast: number | null }) => l.approved_forecast ?? l.manager_forecast ?? l.model_forecast;

export interface PlanningCtx {
  source: Source; setSource: (s: Source) => void; planId: string | undefined; setPlanId: (id: string | undefined) => void; filters: Filters; setFilters: (f: Filters) => void;
  taxonomy: Record<string, string[]>; roles: string[]; goTab: (tab: string, extra?: Record<string, string>) => void;
}
export const PlanningContext = createContext<PlanningCtx | null>(null);
export const usePlanning = () => {
  const c = useContext(PlanningContext);
  if (!c) throw new Error('PlanningContext missing');
  return c;
};

export const SOURCE_LABEL: Record<Source, string> = { POC_SYNTHETIC: 'Demonstration data', DSPORTS_LIVE: 'Live online orders' };

/** Always shown where numbers appear: demonstration data is never presented as real business history, and live data says what it is not (physical stores). */
export const ScopeBanner = ({ source }: { source: Source }) => (
  source === 'POC_SYNTHETIC'
    ? (
      <Alert severity="warning" icon={false} sx={{ py: 0.5, borderRadius: 2.5, border: '1px solid #FEC84B', bgcolor: '#FFFAEB' }} data-testid="scope-banner-demo">
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <ProvenanceChip kind="DEMO" />
          <span><b>DEMONSTRATION DATA.</b> Synthetic stores and sales generated for the proof of concept. These are not real DSports stores, sales or validated predictions, and approving a plan built on them is a demonstration decision.</span>
        </Stack>
      </Alert>
    )
    : (
      <Alert severity="info" icon={false} sx={{ py: 0.5, borderRadius: 2.5 }} data-testid="scope-banner-live">
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <ProvenanceChip kind="LIVE" />
          <span><b>Live online orders.</b> Real DSports web orders (no physical stores). With only a few weeks of history, forecasts are low-confidence fallbacks.</span>
        </Stack>
      </Alert>
    )
);
const STATUS: Record<PlanStatus, 'default' | 'info' | 'success' | 'warning' | 'error' | 'primary'> = { DRAFT: 'default', SUBMITTED: 'info', UNDER_REVIEW: 'info', APPROVED: 'success', PUBLISHED: 'primary', REJECTED: 'error', RETURNED: 'warning', SUPERSEDED: 'default' };
export const PlanStatusChip = ({ status }: { status: PlanStatus }) => <Chip size="small" color={STATUS[status]} label={pretty(status)} data-testid="plan-status" />;

export const ConfChip = ({ c }: { c: Confidence | null | undefined }) => (c ? <ConfidenceMeter level={c} reason={c === 'LOW' ? 'Low confidence: short history, intermittent sales or a fallback method.' : c === 'NONE' ? 'No forecast: insufficient data.' : `${pretty(c)} confidence`} /> : null);

export const Metric = ({ id, title, value, hint, tone, provenance }: { id: string; title: string; value: string; hint?: string; tone?: 'default' | 'warn'; provenance?: Parameters<typeof KpiCard>[0]['provenance'] }) => (
  <Box sx={{ minWidth: 170, flex: 1 }}><KpiCard testId={`metric-${id}`} label={title} value={value} sub={hint} accent={tone === 'warn' ? '#F79009' : undefined} provenance={provenance} /></Box>
);

export const Section = ({ title, id, children, action, provenance, subtitle }: { title: string; id?: string; children: React.ReactNode; action?: React.ReactNode; provenance?: Parameters<typeof SectionCard>[0]['provenance']; subtitle?: React.ReactNode }) => (
  <SectionCard title={title} testId={id} action={action} provenance={provenance} subtitle={subtitle} dense sx={{ overflowX: 'auto', maxWidth: '100%' }}>{children}</SectionCard>
);
export interface Point { label: string; values: (number | null)[] }

/** A dependency-free line chart: one polyline per series, gaps where a value is missing (a missing actual is a gap, never a zero). */
export const LineChart = ({ points, series, height = 180, testId }: { points: Point[]; series: { name: string; color: string }[]; height?: number; testId?: string }) => {
  const W = 640, H = height, L = 44, B = 22, T = 8, R = 8;
  const vals = points.flatMap((p) => p.values.filter((v): v is number => v !== null));
  if (points.length < 2 || vals.length === 0) return <Typography variant="body2" color="text.secondary" data-testid={testId ? `${testId}-empty` : undefined}>Not enough points to draw a chart.</Typography>;
  const max = Math.max(...vals, 1);
  const x = (i: number) => L + (i * (W - L - R)) / (points.length - 1);
  const y = (v: number) => T + (1 - v / max) * (H - T - B);
  return (
    <Box data-testid={testId} sx={{ overflowX: 'auto' }}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Forecast, target and actual by week">
        {[0, 0.5, 1].map((f) => <g key={f}><line x1={L} x2={W - R} y1={y(max * f)} y2={y(max * f)} stroke="#e0e3e8" /><text x={L - 4} y={y(max * f) + 4} fontSize="10" textAnchor="end" fill="#667">{n(max * f)}</text></g>)}
        {points.map((p, i) => (i % Math.ceil(points.length / 8) === 0 ? <text key={p.label} x={x(i)} y={H - 6} fontSize="10" textAnchor="middle" fill="#667">{p.label.slice(5)}</text> : null))}
        {series.map((s, k) => {
          const segs: string[] = [];
          let cur = '';
          points.forEach((p, i) => {
            const v = p.values[k];
            if (v === null || v === undefined) { if (cur) segs.push(cur); cur = ''; } else cur += `${cur ? 'L' : 'M'}${x(i)},${y(v)}`;
          });
          if (cur) segs.push(cur);
          return segs.map((d, i) => <path key={`${s.name}${i}`} d={d} fill="none" stroke={s.color} strokeWidth={2} />);
        })}
      </svg>
      <Stack direction="row" spacing={2}>{series.map((s) => <Stack key={s.name} direction="row" spacing={0.5} alignItems="center"><Box sx={{ width: 14, height: 3, bgcolor: s.color }} /><Typography variant="caption">{s.name}</Typography></Stack>)}</Stack>
    </Box>
  );
};

/** Horizontal bars with the value written on each, so the figure is never only a length. */
export const BarList = ({ items, testId }: { items: { label: string; value: number; note?: string }[]; testId?: string }) => {
  const max = Math.max(...items.map((i) => i.value), 1);
  return (
    <Box data-testid={testId}>
      {items.map((i) => (
        <Stack key={i.label} direction="row" spacing={1} alignItems="center" sx={{ mb: 0.4 }}>
          <Typography variant="caption" sx={{ width: 150, flexShrink: 0 }} noWrap title={i.label}>{i.label}</Typography>
          <Box sx={{ flex: 1, bgcolor: 'action.hover', borderRadius: 0.5, height: 14 }}><Box sx={{ width: `${(i.value / max) * 100}%`, height: '100%', bgcolor: 'primary.main', borderRadius: 0.5 }} /></Box>
          <Typography variant="caption" sx={{ width: 86, textAlign: 'right' }}>{n(i.value)}{i.note ? ` ${i.note}` : ''}</Typography>
        </Stack>
      ))}
    </Box>
  );
};
