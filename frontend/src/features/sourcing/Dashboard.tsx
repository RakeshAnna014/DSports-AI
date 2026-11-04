import { Alert, Box, Button, Chip, Skeleton, Stack, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { sourcingApi, sourcingError } from '@/api/sourcing';
import { KpiCard, KpiGrid } from '@/components/analytics';
import { PipelineFunnel, SourceDiagnostics } from './visuals';
import { pretty, when } from './parts';

const Metric = ({ id, title, value, hint }: { id: string; title: string; value: number; hint: string }) => <KpiCard testId={`metric-${id}`} label={title} value={value} sub={hint} provenance="LIVE" />;

/** Every number here is counted from persisted LIVE records by the service (demonstration, test and unclassified rows are excluded and counted separately); with no data the page says so. */
const Dashboard = ({ onTab }: { onTab: (t: string) => void }) => {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['src', 'dashboard'], queryFn: sourcingApi.dashboard, retry: false });
  const seed = useMutation({ mutationFn: sourcingApi.seedDemo, onSuccess: () => qc.invalidateQueries({ queryKey: ['src'] }) });
  const run = useMutation({ mutationFn: () => sourcingApi.startRun({}), onSuccess: () => qc.invalidateQueries({ queryKey: ['src'] }) });
  if (q.isLoading) return <Skeleton variant="rounded" height={160} />;
  if (q.isError) return <Alert severity="warning" data-testid="src-dash-error">{sourcingError(q.error)}</Alert>;
  const d = q.data!;
  const empty = d.total_products === 0;
  const hidden = d.excluded.fixtures + d.excluded.unknown_provenance;
  return (
    <Stack spacing={2} data-testid="src-dashboard">
      <Typography variant="caption" color="text.secondary">As of {when(d.as_of)}. Live data only. Customer-demand window: last {d.window_days} days ({d.demand_signals_in_window} unmet search(es) recorded).</Typography>
      {empty && (
        <Alert severity="info" data-testid="src-empty">
          No live products yet. Run research against a configured source, add a product by hand, or load the labelled demonstration data to try the workflow (it is never counted as live).
          <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
            <Button size="small" variant="contained" onClick={() => run.mutate()} disabled={run.isPending} data-testid="src-run-research">Run research</Button>
            <Button size="small" variant="outlined" onClick={() => seed.mutate()} disabled={seed.isPending} data-testid="src-seed-demo">Load demonstration data</Button>
          </Stack>
        </Alert>
      )}
      {(run.isError || seed.isError) && <Alert severity="warning">{sourcingError(run.error ?? seed.error)}</Alert>}
      {!d.sources.external_configured && (
        <Alert severity="warning" data-testid="src-no-sources">
          No external research source is configured, so research can only use customer searches and your own entries. Set SOURCING_PROVIDERS (or SOURCING_FEEDS) and SOURCING_ALLOWED_HOSTS to connect one.
          {d.sources.configuration_problems.length > 0 && ` Configuration problems: ${d.sources.configuration_problems.join('; ')}`}
        </Alert>
      )}
      {d.sources.external_configured && <Alert severity="success" icon={false} data-testid="src-sources">Research sources: {d.sources.external_feeds.map((s) => s.name).join(', ')}.</Alert>}
      <KpiGrid min={190}>
        <Metric id="total" title="Live products" value={d.total_products} hint="product candidates, all statuses" />
        <Metric id="review" title="Awaiting review" value={d.awaiting_review} hint="discovered or under review" />
        <Metric id="shortlisted" title="Shortlisted" value={d.shortlisted} hint="buyer shortlist" />
        <Metric id="approved-opp" title="Approved as opportunity" value={d.approved_opportunity} hint="a business decision only" />
        <Metric id="approved" title="Approved for sourcing prep" value={d.approved_for_onboarding} hint="not authority to buy or publish" />
        <Metric id="images" title="With a product image" value={d.with_product_image} hint={`${d.with_exact_model_image} exact-model`} />
        <Metric id="quotes" title="With supplier offers" value={d.with_supplier_quotes} hint="at least one offer recorded" />
        <Metric id="comparable" title="Comparable offers" value={d.with_comparable_offers} hint="two or more active quotations" />
        <Metric id="margin" title="Quoted margin ≥ 25%" value={d.margin_at_least_25_pct} hint="saved against an entered or verified quotation" />
        <Metric id="blocked" title="Blocked" value={d.blocked_by_missing_information} hint="no offer or no margin yet" />
        <Metric id="gaps" title="Customer demand gaps" value={d.demand_gaps.total} hint="not products; need discovery" />
      </KpiGrid>
      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
        <PipelineFunnel byStatus={d.by_status} onOpen={() => onTab('opportunities')} />
        <SourceDiagnostics d={d} onRuns={() => onTab('runs')} />
      </Box>
      {hidden > 0 && (
        <Alert severity="info" icon={false} data-testid="src-excluded">
          Not counted above: {d.excluded.fixtures} demonstration/test record(s) and {d.excluded.unknown_provenance} record(s) of unknown origin. Open the Product discovery tab and switch on “Show demo, test and unclassified data” to review them.
        </Alert>
      )}
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap data-testid="src-by-status">
        {Object.entries(d.by_status).map(([k, n]) => <Chip key={k} size="small" variant="outlined" label={`${pretty(k)}: ${n}`} />)}
      </Stack>
      <Box>
        <Stack direction="row" alignItems="center" justifyContent="space-between"><Typography variant="subtitle2" fontWeight={900}>Recent research runs</Typography>
          <Button size="small" onClick={() => onTab('runs')}>All runs</Button></Stack>
        {d.recent_runs.length === 0 ? <Typography variant="body2" color="text.secondary" data-testid="src-no-runs">No research run yet.</Typography> : d.recent_runs.map((r) => (
          <Typography key={r.id} variant="body2" data-testid="src-run-line">{when(r.started_at)}: <b>{pretty(r.status)}</b>, {r.stats?.created ?? 0} new, {r.stats?.refreshed ?? 0} refreshed
            {r.sources.filter((s) => s.status !== 'ok').map((s) => ` · ${s.name}: ${s.reason}`).join('')}</Typography>
        ))}
      </Box>
      <Typography variant="caption" color="text.secondary">{d.notes.join(' ')}</Typography>
      {!empty && <Button size="small" sx={{ alignSelf: 'flex-start' }} onClick={() => onTab('opportunities')}>Open product discovery</Button>}
    </Stack>
  );
};

export default Dashboard;
