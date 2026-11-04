import { useState } from 'react';
import { Alert, Box, Button, Chip, FormControlLabel, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { AutoAwesome } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { planningApi, planningError, type Analysis, type Finding } from '@/api/planning';
import { ConfidenceMeter, DataUnavailable, EmptyState, ErrorPanel, LoadingBlock, ProvenanceChip, SectionCard, provenanceOf, type ProvenanceKind } from '@/components/analytics';
import { colors } from '@/design/tokens';
import { ScopeBanner, day, n, pretty, usePlanning, when } from './parts';

const SEVERITY: Record<Finding['severity'], { color: string; label: string }> = { HIGH: { color: '#D92D20', label: 'High' }, MEDIUM: { color: '#F79009', label: 'Medium' }, LOW: { color: '#2E90FA', label: 'Low' }, INFO: { color: '#667085', label: 'Info' } };

const Bullets = ({ items, testId }: { items: string[]; testId?: string }) => (
  <Box component="ul" sx={{ m: 0, pl: 2.25, '& li': { mb: 0.25 } }} data-testid={testId}>{items.map((x) => <li key={x}><Typography variant="body2">{x}</Typography></li>)}</Box>
);

const FindingCard = ({ f, analysisId, editable, canEdit, onChanged }: { f: Finding; analysisId: string; editable: boolean; canEdit: boolean; onChanged: (a: Analysis) => void }) => {
  const { goTab, setFilters, filters } = usePlanning();
  const sev = SEVERITY[f.severity];
  const adopt = useMutation({ mutationFn: () => planningApi.adoptFinding(analysisId, f.id), onSuccess: onChanged });
  const dismiss = useMutation({ mutationFn: () => planningApi.dismissFinding(analysisId, f.id), onSuccess: onChanged });
  const open = (e: Finding['evidence'][number]) => {
    const p = e.params ?? {};
    if (p.category || p.sport) setFilters({ ...filters, ...(p.sport ? { sport: p.sport } : {}), ...(p.category ? { category: p.category } : {}) });
    goTab(e.tab, p.grain ? { grain: p.grain } : undefined);
  };
  const busy = adopt.isPending || dismiss.isPending;
  return (
    <Box data-testid="finding" data-kind={f.kind} data-severity={f.severity} sx={{ border: `1px solid ${colors.line}`, borderLeft: `5px solid ${sev.color}`, borderRadius: 2.5, p: 1.75, bgcolor: '#fff' }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.5 }}>
        <Chip size="small" label={sev.label} sx={{ bgcolor: sev.color, color: '#fff', fontWeight: 800, height: 20 }} />
        <Typography fontWeight={800}>{f.title}</Typography>
        {f.provenance.map((p) => <ProvenanceChip key={p} kind={provenanceOf(p) as ProvenanceKind} />)}
        <Box sx={{ flex: 1 }} />
        <ConfidenceMeter level={f.confidence} reason="How strongly the stored records support this finding" />
        {f.decision && <Chip size="small" color={f.decision === 'ADOPTED' ? 'success' : 'default'} label={f.decision === 'ADOPTED' ? 'Proposal adopted' : 'Dismissed'} data-testid="finding-decision" />}
      </Stack>
      <Typography variant="body2" sx={{ mb: 1 }} data-testid="finding-statement">{f.statement}</Typography>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="caption" fontWeight={800} color="text.secondary" sx={{ textTransform: 'uppercase' }}>Observed (from stored records)</Typography>
          <Bullets items={f.facts} testId="finding-facts" />
        </Box>
        {f.hypotheses.length > 0 && (
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Stack direction="row" spacing={0.75} alignItems="center"><Typography variant="caption" fontWeight={800} color="text.secondary" sx={{ textTransform: 'uppercase' }}>Possible explanations</Typography><ProvenanceChip kind="ESTIMATE" label="Hypothesis, not a finding" /></Stack>
            <Bullets items={f.hypotheses} testId="finding-hypotheses" />
          </Box>
        )}
      </Stack>
      <Stack spacing={0.5} sx={{ mt: 1 }}>
        {f.impact && <Typography variant="body2"><b>Expected impact:</b> {f.impact}</Typography>}
        {f.assumptions.length > 0 && <Typography variant="caption" color="text.secondary"><b>Assumptions:</b> {f.assumptions.join(' ')}</Typography>}
        {f.risks.length > 0 && <Typography variant="caption" color="text.secondary"><b>Risks and limits:</b> {f.risks.join(' ')}</Typography>}
        <Typography variant="caption" color="text.secondary">Evidence as of: sales through {day(f.freshness.data_through)}, forecast generated {when(f.freshness.forecast_generated)}, plan version {f.freshness.plan_version ?? '—'}.</Typography>
        {f.next_action && <Typography variant="body2"><b>Suggested next step:</b> {f.next_action}</Typography>}
      </Stack>
      {f.evidence.length > 0 && <Stack direction="row" spacing={0.75} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>{f.evidence.map((e) => <Button key={e.label} size="small" variant="outlined" onClick={() => open(e)} data-testid="finding-evidence">{e.label}</Button>)}</Stack>}
      {f.proposal && (
        <Box sx={{ mt: 1.25, p: 1.25, borderRadius: 2, bgcolor: '#F8FAFC', border: `1px dashed ${colors.line}` }} data-testid="finding-proposal">
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap><Typography variant="subtitle2" fontWeight={800}>Proposed manager forecast</Typography><ProvenanceChip kind="ESTIMATE" label="AI-assisted proposal" /></Stack>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>{f.proposal.basis} Reason code on save: {pretty(f.proposal.reason_code)}. Weeks: {f.proposal.weeks.map(day).join(', ')}.</Typography>
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small"><TableHead><TableRow><TableCell>Product</TableCell><TableCell>Store</TableCell><TableCell align="right">Model / week</TableCell><TableCell align="right">Recent actual / week</TableCell><TableCell align="right">Proposed / week</TableCell></TableRow></TableHead>
              <TableBody>{f.proposal.items.map((i) => <TableRow key={i.product_id + i.location_id}><TableCell>{i.name}</TableCell><TableCell>{i.store}</TableCell><TableCell align="right">{n(i.model_per_week, 1)}</TableCell><TableCell align="right">{n(i.recent_per_week, 1)}</TableCell><TableCell align="right"><b>{n(i.proposed_per_week, 1)}</b></TableCell></TableRow>)}</TableBody></Table>
          </Box>
          {!f.decision && (
            <Stack direction="row" spacing={1} sx={{ mt: 1 }} alignItems="center" flexWrap="wrap" useFlexGap>
              <Button size="small" variant="contained" disabled={!editable || !canEdit || busy} onClick={() => adopt.mutate()} data-testid="finding-adopt">Save as manager forecast</Button>
              <Button size="small" disabled={busy} onClick={() => dismiss.mutate()} data-testid="finding-dismiss">Dismiss</Button>
              {!editable && <Typography variant="caption" color="text.secondary">The plan is not editable (create a revision to change it).</Typography>}
              {editable && !canEdit && <Typography variant="caption" color="text.secondary">You do not have edit rights on this plan.</Typography>}
              <Typography variant="caption" color="text.secondary">Saving is an ordinary, audited edit. It does not approve anything.</Typography>
            </Stack>
          )}
          {adopt.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="finding-error">{planningError(adopt.error)}</Alert>}
        </Box>
      )}
      {!f.proposal && !f.decision && (
        <Stack direction="row" spacing={1} sx={{ mt: 1 }}><Button size="small" disabled={busy} onClick={() => dismiss.mutate()} data-testid="finding-dismiss">Dismiss</Button></Stack>
      )}
    </Box>
  );
};

/** AI-assisted planning analysis: deterministic findings from stored records (facts), labelled hypotheses, evidence links, provenance, and proposals that are saved only by a person and never approve anything. */
const Insights = () => {
  const { planId, source, filters } = usePlanning();
  const qc = useQueryClient();
  const [useLlm, setUseLlm] = useState(true);
  const plan = useQuery({ queryKey: ['pln', 'plan', planId], queryFn: () => planningApi.plan(planId as string), enabled: !!planId, retry: false });
  const latest = useQuery({ queryKey: ['pln', 'analysis', planId], queryFn: () => planningApi.latestAnalysis(planId as string), enabled: !!planId, retry: false });
  const set = (a: Analysis) => qc.setQueryData(['pln', 'analysis', planId], a);
  const run = useMutation({ mutationFn: () => planningApi.analyze(planId as string, { ...filters, use_llm: useLlm }), onSuccess: set });
  if (!planId) return <EmptyState testId="ins-no-plan" title="Choose or create a plan to analyse" body="The analysis reads the plan's forecast, the recent actuals, the stock recommendations and the data coverage. Create a plan from the current forecast, or pick one above." />;
  const a = latest.data;
  const editable = !!plan.data?.editable;
  const canEdit = editable;
  const has = !!a?.id;
  return (
    <Stack spacing={1.5} data-testid="insights">
      <ScopeBanner source={source} />
      <SectionCard title="Planning analysis" provenance={source === 'POC_SYNTHETIC' ? ['DEMO', 'FORECAST'] : ['LIVE', 'FORECAST']}
        subtitle="Findings are computed by the planning engine from stored forecasts, actuals and stock. The AI wording only summarises them, and only if every number it writes is in the findings."
        action={(
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <FormControlLabel control={<Switch size="small" checked={useLlm} onChange={(e) => setUseLlm(e.target.checked)} inputProps={{ 'aria-label': 'Use AI wording' }} />} label={<Typography variant="body2">AI wording</Typography>} />
            <Button variant="contained" startIcon={<AutoAwesome />} disabled={run.isPending} onClick={() => run.mutate()} data-testid="ins-run">{run.isPending ? 'Analysing…' : has ? 'Re-run analysis' : 'Analyse this plan'}</Button>
          </Stack>
        )}>
        {run.isError && <ErrorPanel testId="ins-error" message={planningError(run.error)} />}
        {latest.isLoading && <LoadingBlock rows={1} />}
        {latest.isError && <ErrorPanel testId="ins-load-error" message={planningError(latest.error)} onRetry={() => void latest.refetch()} />}
        {!latest.isLoading && !has && !run.isPending && <Typography variant="body2" color="text.secondary" data-testid="ins-none">{a?.message ?? 'No analysis has been run for this plan yet.'}</Typography>}
        {has && a && (
          <Stack spacing={1} data-testid="ins-summary">
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              {a.summary_source === 'LLM'
                ? <ProvenanceChip kind="ESTIMATE" label="AI-worded summary" hint={`Worded by ${a.llm?.model ?? 'a model'}; every number in it was checked against the findings.`} />
                : <ProvenanceChip kind="MANUAL" label="Rules-based summary" hint={a.llm?.grounding_fallback ? 'The AI wording was rejected because it contained a number that is not in the findings.' : 'Written by the planning engine.'} />}
              <Typography variant="caption" color="text.secondary">Run {when(a.created_at)} · sales through {day(a.data_through)} · forecast model {a.model_version ?? '—'} · plan version {a.plan_version ?? '—'}{a.llm?.used ? ` · ${a.llm.tokensIn ?? 0} tokens in, ${a.llm.tokensOut ?? 0} out` : ''}</Typography>
            </Stack>
            {a.llm?.grounding_fallback && <Alert severity="info" icon={false} data-testid="ins-grounding">The AI summary was set aside (it contained a number that is not in the findings); the rules-based summary is shown.</Alert>}
            <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }} data-testid="ins-summary-text">{a.summary}</Typography>
            <Typography variant="caption" color="text.secondary">{a.notice}</Typography>
          </Stack>
        )}
      </SectionCard>
      {has && a && a.findings.length === 0 && <EmptyState testId="ins-no-findings" title="No finding crossed a threshold" body="Differences below the configured thresholds are not flagged. The analyses that could not be run are listed below." />}
      {has && a && a.findings.map((f) => <FindingCard key={f.id} f={f} analysisId={a.id as string} editable={editable} canEdit={canEdit} onChanged={set} />)}
      {has && a && (a.unavailable?.length ?? 0) > 0 && (
        <SectionCard title="Not analysed" subtitle="These analyses need data the plan does not have yet. Nothing is estimated in their place." testId="ins-unavailable">
          <Stack spacing={1}>{a.unavailable!.map((u) => <DataUnavailable key={u.analysis} what={u.analysis} why={u.why} needs={u.needs} testId="ins-unavailable-item" />)}</Stack>
        </SectionCard>
      )}
    </Stack>
  );
};

export default Insights;
