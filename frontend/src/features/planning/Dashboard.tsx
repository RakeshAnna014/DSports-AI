import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import { AutoAwesome } from '@mui/icons-material';
import { useQuery } from '@tanstack/react-query';
import { isApprover, planningApi, planningError, type Finding } from '@/api/planning';
import { CoverageBars, DataUnavailable, EmptyState, ErrorPanel, GroupedBars, Heatmap, KpiGrid, LoadingBlock, ProvenanceChip, RankBars, SectionCard, TimeSeriesChart, series, signedPct, type ProvenanceKind } from '@/components/analytics';
import FilterBar from './FilterBar';
import { Metric, ScopeBanner, day, n, pctText, pretty, usePlanning, when } from './parts';

const SEV: Record<Finding['severity'], string> = { HIGH: '#D92D20', MEDIUM: '#F79009', LOW: '#2E90FA', INFO: '#667085' };

/** Every number is read from persisted records for the selected plan and filters. A metric with no support in the data is shown as unavailable and says why, never as a placeholder. */
const Dashboard = () => {
  const { source, planId, filters, goTab, setFilters, roles } = usePlanning();
  const prov: ProvenanceKind = source === 'POC_SYNTHETIC' ? 'DEMO' : 'LIVE';
  const q = useQuery({ queryKey: ['pln', 'dashboard', source, planId, filters], queryFn: () => planningApi.dashboard(source, planId, filters), retry: false });
  const planRef = q.data?.plan?.id ?? planId;
  const weekly = useQuery({ queryKey: ['pln', 'weekly-chart', planRef, filters], queryFn: () => planningApi.performance(planRef as string, 'week', filters, 'PLAN'), enabled: !!planRef, retry: false });
  const byCat = useQuery({ queryKey: ['pln', 'dash-category', planRef, filters], queryFn: () => planningApi.performance(planRef as string, 'category', filters, 'PLAN'), enabled: !!planRef, retry: false });
  const byStore = useQuery({ queryKey: ['pln', 'dash-store', planRef, filters], queryFn: () => planningApi.performance(planRef as string, source === 'POC_SYNTHETIC' ? 'store' : 'region', filters, 'PLAN'), enabled: !!planRef, retry: false });
  const regional = useQuery({ queryKey: ['pln', 'regional', source, 26, ''], queryFn: () => planningApi.regional(source, 26, undefined), retry: false });
  const risk = useQuery({ queryKey: ['pln', 'dash-risk', planRef], queryFn: () => planningApi.allocations(planRef as string, { risk: 'HIGH', only_actions: true }, 0, 8), enabled: !!planRef, retry: false });
  const analysis = useQuery({ queryKey: ['pln', 'analysis', planRef], queryFn: () => planningApi.latestAnalysis(planRef as string), enabled: !!planRef, retry: false });
  const queue = useQuery({ queryKey: ['pln', 'review-count'], queryFn: () => planningApi.reviewQueue(), enabled: isApprover(roles), retry: false });
  if (q.isLoading) return <LoadingBlock testId="dash-loading" />;
  if (q.isError) return <ErrorPanel testId="dash-error" message={planningError(q.error)} onRetry={() => void q.refetch()} />;
  const d = q.data!;
  const m = d.metrics;
  const acc = m.forecast_accuracy;
  const closedRows = (weekly.data?.rows ?? []).filter((r) => r.actual !== null && r.actual !== undefined);
  const hasActual = m.actual_units !== undefined && m.actual_units !== null;
  const reg = regional.data;
  const regions = reg ? [...new Set(reg.matrix.map((x) => x.region))].sort() : [];
  const sports = reg ? [...new Set(reg.matrix.map((x) => x.sport))].sort() : [];
  const cellUnits = (r: string, s: string) => { const rows = reg!.matrix.filter((x) => x.region === r && x.sport === s); return rows.length ? rows.reduce((a, x) => a + x.net_units, 0) : null; };
  const variances = (byStore.data?.rows ?? []).filter((r) => r.forecast_variance !== null && r.forecast_variance !== undefined).sort((a, b) => Math.abs(b.forecast_variance ?? 0) - Math.abs(a.forecast_variance ?? 0)).slice(0, 8);
  const topFinding = (analysis.data?.findings ?? []).filter((f) => f.severity !== 'INFO').slice(0, 3);
  return (
    <Stack spacing={1.5} data-testid="dashboard">
      <ScopeBanner source={source} />
      <FilterBar />
      <Typography variant="caption" color="text.secondary" data-testid="dash-freshness">
        Sales data through {day(d.freshness.data_through)} · forecast generated {when(d.freshness.last_forecast)} (history to week of {day(d.freshness.forecast_as_of_week)})
        {d.plan ? ` · plan "${d.plan.name}" (${pretty(d.plan.status)}, ${d.plan.fy_label}), ${d.period?.weeks ?? 0} week(s) ${day(d.period?.first_week)} to ${day(d.period?.last_week)}` : ''}
      </Typography>
      {!d.plan && <Alert severity="info" data-testid="dash-no-plan" action={<Button size="small" onClick={() => goTab('readiness')}>Open data and forecasts</Button>}>{d.message ?? 'There is no plan yet.'}</Alert>}

      <KpiGrid min={190}>
        <Metric id="model" title="Model forecast" value={n(m.model_forecast_units)} hint="units, whole plan period" provenance="FORECAST" />
        <Metric id="plan" title="Plan forecast" value={n(m.plan_forecast_units)} hint="approved, else manager's, else model's" provenance="FORECAST" />
        <Metric id="target" title="Sales target" value={m.target_units === undefined || m.target_units === null ? 'None set' : n(m.target_units)} hint={`${n(m.lines_with_target)} of ${n(m.lines)} lines have a target`} provenance="MANUAL" />
        <Metric id="actual" title="Actual (closed weeks)" value={hasActual ? n(m.actual_units) : 'No closed week'} hint={`${n(m.lines_with_actual)} lines with an actual`} provenance={prov} />
        <Metric id="attainment" title="Target attainment" value={pctText(m.target_attainment_pct)} hint="actual ÷ target, closed weeks with a target" />
        <Metric id="accuracy" title="Forecast accuracy" value={acc?.sufficient ? `WAPE ${pctText((acc.wape ?? 0) * 100, 0)}` : 'Not enough data'}
          hint={acc?.sufficient ? `baseline ${pctText((acc.baseline_wape ?? 0) * 100, 0)} · bias ${pctText((acc.bias ?? 0) * 100, 1)} · ${acc.basis}` : 'withheld: too few observations'} provenance={source === 'POC_SYNTHETIC' ? 'DEMO' : undefined} />
      </KpiGrid>
      <KpiGrid min={190}>
        <Metric id="risk" title="Stores at stock-out risk" value={n(d.queues.stores_at_stockout_risk)} hint={d.queues.stores_at_stockout_risk === undefined || d.queues.stores_at_stockout_risk === null ? 'generate the allocation' : 'high-risk positions'} tone={(d.queues.stores_at_stockout_risk ?? 0) > 0 ? 'warn' : 'default'} />
        <Metric id="replenish" title="Products to replenish" value={n(d.queues.products_to_replenish)} hint={`${n(d.queues.units_to_order)} units (draft)`} />
        <Metric id="excess" title="Positions with excess stock" value={n(d.queues.stock_lines_with_surplus)} hint="surplus over the 90-day forecast" />
        <Metric id="review" title="Forecast needs review" value={n(d.queues.forecasts_low_confidence)} hint={`${n(d.queues.series_without_forecast)} series have no forecast`} />
        <Metric id="awaiting" title="Plans awaiting approval" value={n(d.queues.plans_awaiting_approval)} hint={`${n(d.queues.plans_in_draft)} in draft`} />
      </KpiGrid>

      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', lg: '3fr 2fr' } }}>
        <SectionCard title="Forecast, target and actual by week" testId="dash-chart" provenance={[prov, 'FORECAST']} subtitle="The model forecast stays visible next to the plan forecast. A gap in the actual line means the week is not closed: there is no actual yet, which is not zero sales.">
          {weekly.isLoading && <LoadingBlock rows={0} />}
          {weekly.data ? (
            <TimeSeriesChart testId="dash-line" label="Forecast, target and actual by week" marker={closedRows.length ? { x: closedRows[closedRows.length - 1].gkey, label: 'last closed week' } : undefined}
              points={weekly.data.rows.map((r) => ({ x: r.gkey, values: { model: r.model, forecast: r.forecast, target: r.target, actual: r.actual } }))}
              series={[{ key: 'model', name: 'Model forecast', color: series.model, dashed: true }, { key: 'forecast', name: 'Plan forecast', color: series.forecast }, { key: 'target', name: 'Target', color: series.target }, { key: 'actual', name: 'Actual (closed weeks)', color: series.actual, area: true }]} />
          ) : !weekly.isLoading && <Typography variant="body2" color="text.secondary">Select a plan to see its weekly forecast, target and actual.</Typography>}
          {weekly.data && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>A gap in the actual line means the week is not closed: there is no actual yet, which is not zero sales.</Typography>}
        </SectionCard>

        <SectionCard title="AI insights" testId="dash-insights" provenance={['FORECAST', 'ESTIMATE']} action={<Button size="small" sx={{ whiteSpace: 'nowrap' }} startIcon={<AutoAwesome />} onClick={() => goTab('insights')} data-testid="dash-open-insights">{analysis.data?.id ? 'Open analysis' : 'Analyse plan'}</Button>}
          subtitle="Computed from stored records. Facts and hypotheses are kept apart; open the analysis for the evidence.">
          {!planRef && <Typography variant="body2" color="text.secondary">Create a plan to get an analysis.</Typography>}
          {planRef && analysis.isLoading && <LoadingBlock rows={0} />}
          {planRef && analysis.data && !analysis.data.id && <Typography variant="body2" color="text.secondary" data-testid="dash-no-analysis">No analysis has been run for this plan yet.</Typography>}
          {topFinding.length > 0 && (
            <Stack spacing={1}>
              {topFinding.map((f) => (
                <Box key={f.id} sx={{ borderLeft: `4px solid ${SEV[f.severity]}`, pl: 1.25 }} data-testid="dash-finding">
                  <Typography variant="body2" fontWeight={700}>{f.title}</Typography>
                  <Typography variant="caption" color="text.secondary">{f.statement}</Typography>
                </Box>
              ))}
            </Stack>
          )}
          {analysis.data?.id && topFinding.length === 0 && <Typography variant="body2" color="text.secondary">The latest analysis found nothing that crosses its thresholds.</Typography>}
        </SectionCard>
      </Box>

      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
        <SectionCard title="Forecast, target and actual by category" provenance={[prov, 'FORECAST']} testId="dash-categories">
          {byCat.isLoading && <LoadingBlock rows={0} />}
          {byCat.data && byCat.data.rows.length > 0 && (
            <GroupedBars unit="units" onGroup={(label) => { const [sport, category] = label.split(' / '); setFilters({ ...filters, sport, category }); goTab('workspace'); }}
              groups={byCat.data.rows.slice(0, 12).map((r) => ({ label: r.gkey, values: { forecast: r.forecast, target: r.target, actual: r.actual } }))}
              series={[{ key: 'forecast', name: 'Plan forecast', color: series.forecast }, { key: 'target', name: 'Target', color: series.target }, { key: 'actual', name: 'Actual', color: series.actual }]} />
          )}
          {byCat.data && byCat.data.rows.length === 0 && <EmptyState title="No categories in this selection" />}
          {!planRef && <DataUnavailable what="Category comparison" why="It compares a plan's forecast, target and actual." needs="Create a plan." />}
        </SectionCard>
        <SectionCard title={source === 'POC_SYNTHETIC' ? 'Largest forecast variances by store' : 'Largest forecast variances by region'} provenance={[prov]} testId="dash-variance"
          subtitle="Actual minus forecast over closed weeks that have both. Click to open plan versus actual.">
          {byStore.isLoading && <LoadingBlock rows={0} />}
          {variances.length > 0
            ? <RankBars format={(v) => n(v, 0)} onItem={() => goTab('performance', { grain: source === 'POC_SYNTHETIC' ? 'store' : 'region' })} items={variances.map((r) => ({ label: r.gkey, value: r.forecast_variance, sub: `forecast ${n(r.forecast_variance !== null && r.f_a ? r.f_a : null)} · ${signedPct(r.forecast_variance_pct)}` }))} />
            : planRef && !byStore.isLoading && <DataUnavailable what="Forecast variance" why="No week of this plan has closed with an actual yet, so there is nothing to compare. A missing actual is not shown as zero." needs="Wait for plan weeks to close, or import closed-week actuals." testId="dash-variance-unavailable" />}
        </SectionCard>
      </Box>

      <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', lg: '3fr 2fr' } }}>
        <SectionCard title="Sales mix: region by sport" provenance={[prov]} testId="dash-heatmap" subtitle={`Net units, last ${reg?.weeks ? 26 : 26} closed weeks. Click a cell to open the regional analysis.`}>
          {regional.isLoading && <LoadingBlock rows={0} />}
          {reg && regions.length > 0 && <Heatmap rows={regions} cols={sports} cell={(r, s) => ({ value: cellUnits(r, s) })} onCell={() => goTab('analysis')} format={(v) => n(v)} legend="Darker is more units." />}
          {reg && regions.length === 0 && <DataUnavailable what="Regional mix" why="There are no closed weeks of sales in this scope." />}
          {regional.isError && <ErrorPanel testId="dash-heatmap-error" message={planningError(regional.error)} onRetry={() => void regional.refetch()} />}
        </SectionCard>
        <SectionCard title="Stock-out risk" provenance={['FORECAST', prov]} testId="dash-risk" action={<Button size="small" sx={{ whiteSpace: 'nowrap' }} onClick={() => goTab('inventory')}>Inventory planning</Button>}
          subtitle="High-risk positions from the plan's stock recommendation (a draft). Days of cover against the shortage threshold.">
          {risk.isLoading && <LoadingBlock rows={0} />}
          {risk.data && risk.data.items.length > 0 && <CoverageBars low={14} high={90} items={risk.data.items.map((a) => ({ label: `${a.product_name} · ${a.store_name}`, days: a.coverage_days, sub: `short ${n(a.shortage)} · on hand ${n(a.on_hand)} + incoming ${n(a.incoming)}` }))} />}
          {risk.data && risk.data.items.length === 0 && (risk.data.summary.recs === 0
            ? <DataUnavailable what="Stock-out risk" why="No inventory recommendation has been generated for this plan." needs="Open Inventory planning and generate the recommendations (a draft; nothing is ordered)." testId="dash-risk-unavailable" />
            : <Typography variant="body2" color="text.secondary">No position is at high risk.</Typography>)}
        </SectionCard>
      </Box>

      {isApprover(roles) && (queue.data?.items.length ?? 0) > 0 && (
        <SectionCard title="Waiting for your review" testId="dash-review" action={<Button size="small" variant="contained" onClick={() => goTab('review')} data-testid="dash-open-review">Open review queue</Button>}>
          <Typography variant="body2" component="div">{queue.data!.items.length} plan(s) are waiting for a decision. {queue.data!.items.filter((i) => i.data_scope.synthetic).length > 0 && <>Some are built on demonstration data <ProvenanceChip kind="DEMO" />.</>}</Typography>
        </SectionCard>
      )}
      {d.freshness.last_ingest && <Typography variant="caption" color="text.secondary">Last DSports sales ingest: {String(d.freshness.last_ingest.status)} at {when(String(d.freshness.last_ingest.finished_at ?? d.freshness.last_ingest.started_at))}, {String(d.freshness.last_ingest.lines_read)} line(s) read.</Typography>}
    </Stack>
  );
};

export default Dashboard;
