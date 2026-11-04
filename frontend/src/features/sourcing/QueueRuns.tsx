import { safeHref } from '@/utils/safeUrl';
import { useState } from 'react';
import { Alert, Box, Button, Chip, Link, Paper, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { sourcingApi, sourcingError } from '@/api/sourcing';
import { StatusChip, pretty, when } from './parts';

export const Queue = ({ onOpen }: { onOpen: (id: string) => void }) => {
  const q = useQuery({ queryKey: ['src', 'queue'], queryFn: sourcingApi.queue, retry: false });
  if (q.isLoading) return <Skeleton variant="rounded" height={120} />;
  if (q.isError) return <Alert severity="warning">{sourcingError(q.error)}</Alert>;
  const d = q.data!;
  return (
    <Stack spacing={1} data-testid="src-queue">
      <Typography variant="body2" color="text.secondary">{d.can_approve ? 'You can approve opportunities for onboarding.' : 'Approving needs a category manager or administrator; you can prepare opportunities for them.'}</Typography>
      {d.items.length === 0 ? <Alert severity="info" data-testid="queue-empty">Nothing is shortlisted or waiting for a decision.</Alert> : (
        <Paper variant="outlined"><Table size="small"><TableHead><TableRow><TableCell>Product</TableCell><TableCell>Status</TableCell><TableCell>Score</TableCell><TableCell>Margin (est.)</TableCell><TableCell>Supplier risk</TableCell></TableRow></TableHead>
          <TableBody>{d.items.map((o) => (
            <TableRow key={o.id} hover sx={{ cursor: 'pointer' }} onClick={() => onOpen(o.id)} data-testid="queue-row"><TableCell>{o.name}</TableCell><TableCell><StatusChip status={o.status} /></TableCell>
              <TableCell>{o.opp_score == null ? 'Not scored' : o.opp_score.toFixed(0)}</TableCell><TableCell>{o.margin == null ? '—' : `${o.margin.toFixed(1)}%`}</TableCell><TableCell>{pretty(o.risk ?? 'UNKNOWN')}</TableCell></TableRow>))}</TableBody></Table></Paper>
      )}
    </Stack>
  );
};

const STAT_LABELS: [string, string][] = [['products_extracted', 'products read'], ['created', 'new'], ['refreshed', 'refreshed'], ['duplicates', 'already known'], ['invalid', 'not products'], ['images_valid', 'images valid'],
  ['images_failed', 'images failed'], ['suppliers_created', 'suppliers found'], ['catalogue_exact', 'exact catalogue matches'], ['demand_gaps_new', 'new demand gaps']];

const RunResultsList = ({ runId }: { runId: string }) => {
  const q = useQuery({ queryKey: ['src', 'run-results', runId], queryFn: () => sourcingApi.runResults(runId), retry: false });
  if (q.isLoading) return <Skeleton variant="rounded" height={40} />;
  if (q.isError) return <Alert severity="warning">{sourcingError(q.error)}</Alert>;
  const ps = q.data!.products;
  return ps.length === 0 ? <Typography variant="caption" color="text.secondary" data-testid="run-no-results">This run retrieved no product records.</Typography> : (
    <Box data-testid="run-results">{ps.map((p) => <Typography key={p.id} variant="caption" display="block">• {p.name}{p.source_url && <> — <Link href={safeHref(p.source_url)} target="_blank" rel="noopener noreferrer">{p.source_url}</Link></>}</Typography>)}</Box>
  );
};

export const Runs = () => {
  const qc = useQueryClient();
  const [sport, setSport] = useState('');
  const [keywords, setKeywords] = useState('');
  const [openResults, setOpenResults] = useState<string | null>(null);
  // A run is asynchronous: it is accepted at once and finishes in the background, so the list is refreshed every few seconds while one is still running.
  const runs = useQuery({ queryKey: ['src', 'runs'], queryFn: sourcingApi.runs, retry: false, refetchInterval: (query) => (query.state.data?.some((r) => r.status === 'RUNNING') ? 3_000 : 15_000) });
  const start = useMutation({
    mutationFn: () => sourcingApi.startRun({ sport: sport || undefined, keywords: keywords.split(',').map((k) => k.trim()).filter((k) => k.length > 1) }),
    onSettled: () => qc.invalidateQueries({ queryKey: ['src'] }),
  });
  return (
    <Stack spacing={1.5} data-testid="src-runs">
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        <TextField size="small" label="Sport (optional)" value={sport} onChange={(e) => setSport(e.target.value)} inputProps={{ 'data-testid': 'run-sport' }} />
        <TextField size="small" label="Keywords (comma separated)" value={keywords} onChange={(e) => setKeywords(e.target.value)} sx={{ minWidth: 240 }} />
        <Button variant="contained" onClick={() => start.mutate()} disabled={start.isPending} data-testid="run-start">{start.isPending ? 'Starting…' : 'Run research'}</Button>
      </Stack>
      {start.isError && <Alert severity="warning" data-testid="run-error">{sourcingError(start.error)}</Alert>}
      {start.data && (() => {
        const latest = runs.data?.find((r) => r.id === start.data.id) ?? start.data;       // the accepted run finishes later: follow its stored state, not the 202 response
        return (
          <Alert severity={latest.status === 'SUCCEEDED' ? 'success' : latest.status === 'RUNNING' ? 'info' : 'warning'} data-testid="run-result" data-status={latest.status}>
            Run {pretty(latest.status)}{latest.status === 'RUNNING' ? ': reading the configured sources. The list below updates when it finishes.' : `: ${latest.stats.created ?? 0} new, ${latest.stats.refreshed ?? 0} refreshed.`}</Alert>);
      })()}
      {runs.isLoading && <Skeleton variant="rounded" height={100} />}
      {runs.data && (runs.data.length === 0 ? <Alert severity="info" data-testid="runs-empty">No research has been run yet.</Alert> : runs.data.map((r) => (
        <Paper key={r.id} variant="outlined" sx={{ p: 1.25 }} data-testid="run-row">
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Chip size="small" color={r.status === 'SUCCEEDED' ? 'success' : r.status === 'FAILED' ? 'error' : r.status === 'RUNNING' ? 'info' : 'warning'} label={pretty(r.status)} />
            <Typography variant="body2">{when(r.started_at)} → {r.finished_at ? when(r.finished_at) : 'running'}</Typography>
            <Typography variant="caption" color="text.secondary">{JSON.stringify(r.params)}</Typography>
            {r.status !== 'RUNNING' && <Button size="small" onClick={() => setOpenResults(openResults === r.id ? null : r.id)} data-testid="run-results-toggle">{openResults === r.id ? 'Hide results' : 'Show results'}</Button>}
          </Stack>
          <Typography variant="caption" display="block" color="text.secondary" data-testid="run-stats">{STAT_LABELS.filter(([k]) => r.stats?.[k] !== undefined).map(([k, label]) => `${r.stats[k]} ${label}`).join(' · ') || 'No counts yet'}</Typography>
          <Box sx={{ mt: 0.5 }}>{r.sources.map((s) => (
            <Box key={s.name}>
              <Typography variant="caption" display="block" color={s.status === 'ok' ? 'text.secondary' : 'warning.main'} data-testid="run-source">{s.name}: {s.status} ({s.records} record(s)){s.reason ? ` — ${s.reason}` : ''}</Typography>
              {(s.skipped ?? []).map((k) => <Typography key={k.url} variant="caption" display="block" color="text.secondary" sx={{ pl: 2 }}>skipped {k.url}: {k.why}</Typography>)}
            </Box>))}</Box>
          {r.error && <Typography variant="caption" color="error">{r.error}</Typography>}
          {openResults === r.id && <Box sx={{ mt: 0.75 }}><RunResultsList runId={r.id} /></Box>}
        </Paper>)))}
    </Stack>
  );
};

export const Proposals = ({ onOpen }: { onOpen: (id: string) => void }) => {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['src', 'proposals'], queryFn: sourcingApi.proposals, retry: false });
  const review = useMutation({ mutationFn: (id: string) => sourcingApi.reviewProposal(id), onSuccess: () => qc.invalidateQueries({ queryKey: ['src'] }) });
  if (q.isLoading) return <Skeleton variant="rounded" height={100} />;
  if (q.isError) return <Alert severity="warning">{sourcingError(q.error)}</Alert>;
  return (
    <Stack spacing={1} data-testid="src-proposals">
      <Alert severity="info" icon={false}>A proposal is a persisted draft for the catalogue team. It does not create or publish a product, set a price or authorise a purchase.</Alert>
      {q.data!.length === 0 && <Alert severity="info" data-testid="proposals-empty">No proposal has been prepared. Approve an opportunity for onboarding, then prepare one from its page.</Alert>}
      {q.data!.map((p) => (
        <Paper key={p.id} variant="outlined" sx={{ p: 1.25 }} data-testid="proposal-card">
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography fontWeight={800}>{p.opportunity_name}</Typography><Chip size="small" color={p.status === 'DRAFT' ? 'warning' : 'success'} label={pretty(p.status)} />
            <Button size="small" onClick={() => onOpen(p.opportunity_id)}>Open the opportunity</Button>
            {p.status === 'READY_FOR_REVIEW' && <Button size="small" variant="outlined" onClick={() => review.mutate(p.id)} data-testid="proposal-review">Mark reviewed</Button>}
          </Stack>
          <Typography variant="body2">Supplier: {p.payload.supplier?.supplier ?? '—'} · landed cost {p.payload.purchase?.landed_cost_per_unit ?? '—'} · proposed price {p.payload.pricing?.proposed_selling_price ?? '—'} · margin {p.payload.pricing?.gross_margin_pct ?? '—'}%</Typography>
          {p.validation.missing_mandatory.length > 0 && <Typography variant="body2" color="warning.main">Still needed: {p.validation.missing_mandatory.join('; ')}</Typography>}
        </Paper>))}
      {review.isError && <Alert severity="warning">{sourcingError(review.error)}</Alert>}
    </Stack>
  );
};
