import { Alert, Button, Chip, Skeleton, Stack, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { KpiCard, ProvenanceChip, SectionCard } from '@/components/analytics';
import type { ReactNode } from 'react';
import { colors } from '@/design/tokens';
import { getInventoryError } from '@/api/inventoryAi';
import type { InvAssessment, RiskLevel } from '@/types/inventoryAi';

export const inr = (v: number | null | undefined) => (v == null ? '—' : `₹${Math.round(v).toLocaleString('en-IN')}`);
export const num = (v: number | null | undefined, digits = 0) =>
  v == null ? '—' : v.toLocaleString('en-IN', { maximumFractionDigits: digits, minimumFractionDigits: digits });
export const shortDate = (iso: string | null | undefined) => (iso ? new Date(iso + 'T00:00:00').toLocaleDateString('en-GB', { day: '2-digit', month: 'short' }) : '—');

export const RISK_COLOR: Record<string, string> = { HIGH: '#D92D20', MEDIUM: '#F79009', LOW: '#12B76A' };
export const HEALTH_COLOR: Record<string, string> = { Healthy: '#12B76A', Watch: '#F79009', 'Slow Moving': '#F04438', 'Critical Aging': '#912018' };

export const RiskChip = ({ level }: { level: RiskLevel | string | null }) =>
  level ? <Chip size="small" label={level} data-testid={`risk-${level}`} sx={{ fontWeight: 800, color: '#fff', bgcolor: RISK_COLOR[level] ?? 'grey.600', height: 22 }} /> : <>—</>;

export const HealthChip = ({ value }: { value: string | null }) =>
  value ? <Chip size="small" label={value} variant="outlined" sx={{ fontWeight: 700, borderColor: HEALTH_COLOR[value], color: HEALTH_COLOR[value], height: 22 }} /> : <>—</>;

const ACTION: Record<string, { label: string; color: string }> = {
  ORDER: { label: 'Replenish', color: colors.green },
  EXPEDITE_INCOMING: { label: 'Expedite open order', color: colors.orange },
};
export const ActionChip = ({ a }: { a: InvAssessment }) => {
  const x = a.detail?.action ? ACTION[a.detail.action] : undefined;
  return x ? <Chip size="small" label={x.label} sx={{ fontWeight: 700, bgcolor: x.color, color: '#fff', height: 22 }} /> : <>—</>;
};

/** Always visible: what kind of data the numbers are. Synthetic data is never presented as real business data. */
export const DataBadge = ({ source = 'POC_SYNTHETIC', note }: { source?: string; note?: string }) => (
  <Tooltip title={note ?? 'Demand history, stock positions, purchase orders, cost, suppliers, lead times, regions and events are generated POC data, not real DSports business data. The catalogue and prices are real DSports data.'}>
    <Chip size="small" label={source === 'POC_SYNTHETIC' ? 'POC synthetic data' : source.replace('_', ' ').toLowerCase()} data-testid="data-badge"
      sx={{ bgcolor: colors.orangeSoft, color: '#B54708', fontWeight: 800, border: '1px solid #FEC84B', height: 22 }} />
  </Tooltip>
);

export const RecommendationBadge = () => (
  <Chip size="small" label="AI Recommendation · nothing executed" data-testid="ai-recommendation-badge"
    sx={{ bgcolor: colors.navy800, color: colors.lime, fontWeight: 800, height: 22 }} />
);

export const Card = ({ title, subtitle, action, children, testId, sx }: { title?: ReactNode; subtitle?: ReactNode; action?: ReactNode; children: ReactNode; testId?: string; sx?: object }) => (
  <SectionCard title={title} subtitle={subtitle} action={action} testId={testId} sx={sx}>{children}</SectionCard>
);

export const Kpi = ({ label, value, sub, color, onClick, testId }: { label: string; value: ReactNode; sub?: ReactNode; color?: string; onClick?: () => void; testId?: string }) => (
  <KpiCard label={label} value={value} sub={sub} accent={color} onClick={onClick} testId={testId} />
);

export const Loading = ({ rows = 4 }: { rows?: number }) => (
  <Stack spacing={1} data-testid="loading">{Array.from({ length: rows }).map((_, i) => <Skeleton key={i} variant="rounded" height={i === 0 ? 120 : 36} />)}</Stack>
);

/** A structured, honest error: insufficient data is not an outage, forbidden is not "try again". */
export const ErrorState = ({ error, onRetry }: { error: unknown; onRetry?: () => void }) => {
  const e = getInventoryError(error);
  const severity = e.code === 'INSUFFICIENT_DATA' ? 'info' : e.code === 'FORBIDDEN' ? 'warning' : 'error';
  return (
    <Alert severity={severity} data-testid="inventory-error" data-code={e.code}
      action={onRetry && e.code !== 'FORBIDDEN' ? <Chip label="Retry" size="small" onClick={onRetry} clickable /> : undefined}>
      <b>{e.code === 'INSUFFICIENT_DATA' ? 'Insufficient data' : e.code === 'UNREACHABLE' || e.code === 'INVENTORY_UNAVAILABLE' ? 'Service unavailable' : e.code === 'FORBIDDEN' ? 'Restricted' : 'Something went wrong'}</b>: {e.message}
    </Alert>
  );
};

export const Empty = ({ children }: { children: ReactNode }) => (
  <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }} data-testid="empty">{children}</Typography>
);

/** What the stock numbers on this screen are: the Inventory AI holds stock per product (and region), not per store, and in the demonstration it is generated. It is never split across stores here; store-level positions live in Sales & Inventory Planning's own (also demonstration) network. */
export const StockBasisNotice = () => (
  <Alert severity="info" icon={false} sx={{ borderRadius: 2.5 }} data-testid="stock-basis"
    action={<Button size="small" component={RouterLink} to="/sales-planning?tab=inventory" sx={{ whiteSpace: 'nowrap' }}>Store-level planning</Button>}>
    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
      <ProvenanceChip kind="DEMO" label="Demonstration stock" />
      <span>Stock, demand history, purchase orders and lead times here are <b>generated demonstration data</b> held per product and region. Warehouse stock is not split across stores on this screen; the catalogue and prices are real. Recommendations are drafts: nothing is ordered or moved.</span>
    </Stack>
  </Alert>
);