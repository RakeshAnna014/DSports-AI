import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import { colors } from '@/design/tokens';
import { ProvenanceChip as ProvChip, RankBars, SectionCard, WorkflowSteps, type ProvenanceKind } from '@/components/analytics';
import type { CalcResult, Compare, Dashboard, OppStatus, ScoreResult, SourceKind } from '@/api/sourcing';
import { pretty, when } from './parts';

const PALETTE = ['#16A34A', '#2E90FA', '#F79009', '#7A5AF8', '#D92D20', '#0E9384', '#667085'];

/** Where the money goes: one stacked bar for the landed cost with each component's amount and share. Amounts come from the deterministic calculator; nothing is estimated here. */
export const CostBreakdown = ({ drivers, testId = 'cost-breakdown' }: { drivers: CalcResult['cost_drivers']; testId?: string }) => {
  const rows = drivers.map((d) => ({ ...d, a: Number(d.amount), s: Number(d.share_pct) })).filter((d) => Number.isFinite(d.a) && d.a > 0);
  if (rows.length === 0) return null;
  return (
    <Box data-testid={testId} sx={{ my: 1 }}>
      <Stack direction="row" sx={{ height: 18, borderRadius: 9, overflow: 'hidden', bgcolor: '#EEF2F8' }} role="img" aria-label="Cost breakdown">
        {rows.map((d, i) => <Box key={d.component} title={`${d.component}: ₹${d.a.toLocaleString('en-IN')} (${d.s}%)`} sx={{ width: `${d.s}%`, bgcolor: PALETTE[i % PALETTE.length], minWidth: 3 }} />)}
      </Stack>
      <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }}>
        {rows.map((d, i) => <Stack key={d.component} direction="row" spacing={0.5} alignItems="center"><Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: PALETTE[i % PALETTE.length] }} /><Typography variant="caption">{d.component} {d.s}%</Typography></Stack>)}
      </Stack>
    </Box>
  );
};

/** The opportunity score as its parts: each dimension's score, its weight and why, so the number is never a black box. */
export const ScoreBars = ({ score }: { score: ScoreResult }) => (
  <Box data-testid="score-bars">
    <RankBars signed={false} format={(v) => `${v.toFixed(0)}/100`} items={score.dimensions.map((d) => ({ label: `${pretty(d.dimension)} · weight ${(d.weight * 100).toFixed(0)}%`, value: d.score, sub: d.why }))} />
    {score.missing.length > 0 && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>Not scored for lack of data: {score.missing.map(pretty).join(', ')}.</Typography>}
  </Box>
);

const KIND_PROV: Record<SourceKind, ProvenanceKind> = { PUBLIC_LISTING: 'LISTING', QUOTE_ENTERED: 'QUOTE', VERIFIED: 'VERIFIED', AI_ESTIMATE: 'ESTIMATE' };

/** Landed cost per unit for each offer, side by side, with what kind of figure each one is (a public listing is not a quotation). Offers that cannot be calculated show "no data". */
export const OfferChart = ({ offers }: { offers: Compare['offers'] }) => {
  const items = offers.map((o) => {
    const v = o.calculation.status === 'OK' ? Number(o.calculation.values.landed_cost_per_unit) : NaN;
    return { key: o.quote.id, label: `${o.quote.supplier_name} · ${o.quote.label}`, value: Number.isFinite(v) ? v : null, sub: o.calculation.status === 'OK' ? `${o.quote.currency} ${o.quote.unit_price} unit price${o.rank_by_landed_cost === 1 ? ' · lowest landed cost' : ''}` : `cannot calculate: ${o.calculation.missing.join(', ') || o.calculation.issues.join('; ')}`, kind: o.quote.source_kind };
  });
  if (items.length < 2) return null;
  return (
    <SectionCard title="Landed cost per unit, by offer" dense testId="offer-chart" subtitle="Lower is better. The chip says what each figure is: a quotation, a public listing or an estimate." sx={{ my: 1 }}>
      <RankBars signed={false} higherIsBetter={false} format={(v) => `₹${v.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`} items={items.map((i) => ({ key: i.key, label: i.label, value: i.value, sub: i.sub }))} />
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }}>{[...new Set(items.map((i) => i.kind))].map((k) => <ProvChip key={k} kind={KIND_PROV[k]} />)}</Stack>
    </SectionCard>
  );
};

const FUNNEL: OppStatus[] = ['DISCOVERED', 'UNDER_REVIEW', 'SHORTLISTED', 'APPROVED_OPPORTUNITY', 'SUPPLIER_RESEARCH', 'QUOTE_COMPARISON', 'APPROVED_FOR_ONBOARDING'];

export const PipelineFunnel = ({ byStatus, onOpen }: { byStatus: Record<string, number>; onOpen?: () => void }) => (
  <SectionCard title="Pipeline" dense testId="src-pipeline" subtitle="Live product candidates by status. A customer search is a demand gap, not a product, and is counted separately.">
    <RankBars signed={false} format={(v) => String(v)} onItem={onOpen ? () => onOpen() : undefined} items={FUNNEL.map((s) => ({ label: pretty(s), value: byStatus[s] ?? 0 }))} />
  </SectionCard>
);

/** What research can and cannot reach right now: configured sources, their last result, and exactly what to set when none is configured. Nothing is shown as connected unless a run stored records from it. */
export const SourceDiagnostics = ({ d, onRuns }: { d: Dashboard; onRuns: () => void }) => {
  const last = d.recent_runs[0];
  const feeds = d.sources.external_feeds;
  return (
    <SectionCard title="Research sources" dense testId="src-diagnostics" provenance={feeds.length ? ['LISTING'] : undefined} action={<Button size="small" onClick={onRuns} sx={{ whiteSpace: 'nowrap' }}>Research runs</Button>}
      subtitle="A source counts as connected only if a run has stored records from it.">
      {feeds.length === 0 && (
        <Stack spacing={0.5} data-testid="src-diag-missing">
          <Typography variant="body2" fontWeight={700}>No external source is configured.</Typography>
          <Typography variant="body2" color="text.secondary">Research then uses customer searches and buyer entries only. To connect a permitted source, set <code>SOURCING_PROVIDERS</code> (or <code>SOURCING_FEEDS</code>) and <code>SOURCING_ALLOWED_HOSTS</code> for the AI service and restart it. Nothing is scraped from a host that is not on the allow-list.</Typography>
        </Stack>
      )}
      {d.sources.configuration_problems.length > 0 && <Typography variant="body2" color="error" data-testid="src-diag-problems">Configuration problems: {d.sources.configuration_problems.join('; ')}</Typography>}
      {feeds.map((f) => {
        const r = last?.sources.find((s) => s.name === f.name);
        const ok = r && r.status === 'ok' && r.records > 0;
        return (
          <Stack key={f.name} direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ py: 0.5 }} data-testid="src-diag-source">
            <Chip size="small" label={f.name} sx={{ fontWeight: 700 }} />
            {r ? <Chip size="small" color={ok ? 'success' : 'warning'} label={ok ? `connected: ${r.records} record(s) stored` : `last run: ${pretty(r.status)}`} /> : <Chip size="small" label="not run yet" />}
            {r?.reason && <Typography variant="caption" color="text.secondary">{r.reason}</Typography>}
          </Stack>
        );
      })}
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>{last ? `Last run ${when(last.started_at)}: ${pretty(last.status)}, ${last.stats?.created ?? 0} new, ${last.stats?.refreshed ?? 0} refreshed.` : 'No research run yet.'} Currencies with a configured exchange rate: {d.sources.fx_currencies.join(', ') || 'none'}.</Typography>
    </SectionCard>
  );
};

const STEPS = ['Discover', 'Review evidence', 'Compare suppliers', 'Calculate landed cost', 'Shortlist', 'Approve', 'Draft onboarding proposal'];
const STEP_OF: Partial<Record<OppStatus, number>> = { DISCOVERED: 0, UNDER_REVIEW: 1, NEEDS_MORE_EVIDENCE: 1, ON_HOLD: 1, SUPPLIER_RESEARCH: 2, QUOTE_COMPARISON: 3, SHORTLISTED: 4, APPROVED_OPPORTUNITY: 5, APPROVED_FOR_ONBOARDING: 6 };

/** Where this product is in the buyer's sequence. The status decides; the stepper only mirrors it (every move is a recorded, role-checked decision). */
export const ProductWorkflow = ({ status }: { status: OppStatus }) => <WorkflowSteps steps={STEPS} current={STEP_OF[status] ?? 0} testId="opp-workflow" />;

export const surfaceBg = { bgcolor: colors.surface, minHeight: '100vh' } as const;
