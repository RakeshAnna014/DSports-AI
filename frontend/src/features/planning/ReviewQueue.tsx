import { useMemo, useState } from 'react';
import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, FormControlLabel, InputLabel, MenuItem, Select, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isApprover, planningApi, planningError, type BulkResult, type ReviewItem } from '@/api/planning';
import { ConfidenceMeter, DetailDrawer, EmptyState, ErrorPanel, GroupedBars, LoadingBlock, ProvenanceChip, SectionCard, series } from '@/components/analytics';
import { PlanStatusChip, day, n, pctText, usePlanning, when } from './parts';

type Action = 'APPROVE' | 'RETURN' | 'REJECT';
const ACTION_LABEL: Record<Action, string> = { APPROVE: 'Approve', RETURN: 'Return for revision', REJECT: 'Reject' };

const Breakdown = ({ plan, onClose }: { plan: ReviewItem; onClose: () => void }) => {
  const [by, setBy] = useState<'region' | 'zone' | 'store'>('region');
  const q = useQuery({ queryKey: ['pln', 'breakdown', plan.id, by], queryFn: () => planningApi.breakdown(plan.id, by), retry: false });
  const rows = q.data?.rows ?? [];
  return (
    <DetailDrawer open onClose={onClose} title={plan.name} subtitle={`${plan.fy_label} · ${day(plan.first_week)} to ${day(plan.last_week)} · ${plan.lines.toLocaleString('en-IN')} lines`} width={640} testId="rq-breakdown">
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <ProvenanceChip kind={plan.data_scope.synthetic ? 'DEMO' : 'LIVE'} />
          <ToggleButtonGroup size="small" exclusive value={by} onChange={(_e, v) => v && setBy(v)} aria-label="Group by">
            <ToggleButton value="region" data-testid="rq-by-region">Region</ToggleButton><ToggleButton value="zone">Zone</ToggleButton><ToggleButton value="store" data-testid="rq-by-store">Store</ToggleButton>
          </ToggleButtonGroup>
        </Stack>
        {q.isLoading && <LoadingBlock rows={1} />}
        {q.isError && <ErrorPanel message={planningError(q.error)} />}
        {rows.length > 0 && (
          <>
            <GroupedBars testId="rq-bars" groups={rows.slice(0, 24).map((r) => ({ label: r.gkey, values: { model: r.model_units, plan: r.planned_units, target: r.target_units } }))}
              series={[{ key: 'model', name: 'Model forecast', color: series.model }, { key: 'plan', name: "Planned (manager's or model's)", color: series.forecast }, { key: 'target', name: 'Target', color: series.target }]} />
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small" data-testid="rq-breakdown-table"><TableHead><TableRow><TableCell>{by === 'store' ? 'Store' : by === 'zone' ? 'Zone' : 'Region'}</TableCell><TableCell align="right">Lines</TableCell><TableCell align="right">Planned</TableCell><TableCell align="right">Target</TableCell><TableCell align="right">Adjusted</TableCell><TableCell>Low confidence</TableCell></TableRow></TableHead>
                <TableBody>{rows.map((r) => (
                  <TableRow key={r.gkey}><TableCell>{r.gkey}</TableCell><TableCell align="right">{n(r.lines)}</TableCell><TableCell align="right">{n(r.planned_units)}</TableCell><TableCell align="right">{r.target_units === null ? '—' : n(r.target_units)}</TableCell>
                    <TableCell align="right">{n(r.adjusted_lines)}</TableCell><TableCell><ConfidenceMeter level={r.lines && r.low_confidence_lines / r.lines >= 0.5 ? 'LOW' : 'MEDIUM'} reason={`${r.low_confidence_lines} of ${r.lines} lines are low confidence`} /></TableCell></TableRow>))}</TableBody></Table>
            </Box>
          </>
        )}
      </Stack>
    </DetailDrawer>
  );
};

const BulkDialog = ({ ids, action, onClose, onDone }: { ids: string[]; action: Action; onClose: () => void; onDone: () => void }) => {
  const [note, setNote] = useState('');
  const [ack, setAck] = useState(false);
  const [result, setResult] = useState<BulkResult | null>(null);
  const needsReason = action !== 'APPROVE';
  // The preview only reports eligibility and consequences (the server needs some reason text to preview a return or a rejection); it is not refreshed while typing and not after the action was applied.
  const preview = useQuery({ queryKey: ['bulk-preview', ids, action], queryFn: () => planningApi.bulkReview(ids, action, needsReason ? 'preview' : undefined, true), retry: false, enabled: !result, refetchOnWindowFocus: false });
  const apply = useMutation({ mutationFn: () => planningApi.bulkReview(ids, action, note.trim() || undefined, false, ack), onSuccess: (r) => { setResult(r); onDone(); } });
  const p = preview.data;
  const ready = !!p && p.all_eligible && (!needsReason || note.trim().length >= 3) && (!p.requires_acknowledgement || ack);
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth data-testid="rq-dialog">
      <DialogTitle>{ACTION_LABEL[action]} {ids.length} plan{ids.length === 1 ? '' : 's'}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 0.5 }}>
          {preview.isLoading && <LoadingBlock rows={1} />}
          {preview.isError && <ErrorPanel message={planningError(preview.error)} />}
          {p && !result && (
            <>
              <Alert severity="info" icon={false}>Review what will happen to each plan. Nothing is applied until you confirm, and nothing here orders stock, moves stock, publishes a product or changes a price.</Alert>
              {p.warning && <Alert severity="warning" data-testid="rq-warning">{p.warning}</Alert>}
              <Table size="small" data-testid="rq-preview"><TableHead><TableRow><TableCell>Plan</TableCell><TableCell>Now</TableCell><TableCell>Result</TableCell></TableRow></TableHead>
                <TableBody>{p.results.map((r) => (
                  <TableRow key={r.plan_id} data-eligible={r.eligible ? '1' : '0'}><TableCell>{r.name ?? r.plan_id} {r.synthetic && <ProvenanceChip kind="DEMO" />}</TableCell><TableCell>{r.status && <PlanStatusChip status={r.status} />}</TableCell>
                    <TableCell>{r.eligible ? <Typography variant="body2">{r.consequence}</Typography> : <Typography variant="body2" color="error">Cannot proceed: {r.reason}</Typography>}</TableCell></TableRow>))}</TableBody></Table>
              {!p.all_eligible && <Alert severity="warning">At least one selected plan cannot take this action, so nothing will be applied to any of them. Deselect it and try again.</Alert>}
              {needsReason && <TextField size="small" multiline minRows={2} label={`Reason (required to ${action === 'RETURN' ? 'return' : 'reject'})`} value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ 'data-testid': 'rq-note' }} />}
              {p.requires_acknowledgement && <FormControlLabel control={<Checkbox checked={ack} onChange={(e) => setAck(e.target.checked)} inputProps={{ 'data-testid': 'rq-ack' } as React.InputHTMLAttributes<HTMLInputElement>} />}
                label={<Typography variant="body2">I understand these plans are built on demonstration data, so this is not a real operational decision.</Typography>} />}
            </>
          )}
          {apply.isError && <ErrorPanel testId="rq-apply-error" message={planningError(apply.error)} />}
          {result && (
            <Stack spacing={1} data-testid="rq-result">
              <Alert severity={result.applied ? 'success' : 'warning'}>{result.applied ? `${ACTION_LABEL[action]} applied to ${result.results.length} plan(s). A record of who decided and why is in each plan's history.` : 'Some plans could not be changed:'}</Alert>
              {result.results.map((r) => <Typography key={r.plan_id} variant="body2">{r.name ?? r.plan_id}: {r.applied ? `now ${r.status_after?.toLowerCase().replace('_', ' ')}` : r.error ?? 'not applied'}</Typography>)}
            </Stack>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="rq-close">{result ? 'Close' : 'Cancel'}</Button>
        {!result && <Button variant="contained" color={action === 'APPROVE' ? 'primary' : 'warning'} disabled={!ready || apply.isPending} onClick={() => apply.mutate()} data-testid="rq-confirm">{apply.isPending ? 'Applying…' : `${ACTION_LABEL[action]} ${ids.length} plan${ids.length === 1 ? '' : 's'}`}</Button>}
      </DialogActions>
    </Dialog>
  );
};

/** Plans waiting for a decision, by region and store, with the evidence a reviewer needs and a bulk action that previews every consequence first. The server decides eligibility; this screen only shows it. */
const ReviewQueue = () => {
  const { roles, goTab } = usePlanning();
  const qc = useQueryClient();
  const [status, setStatus] = useState('');
  const [region, setRegion] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [dialog, setDialog] = useState<{ action: Action; ids: string[] } | null>(null);
  const [open, setOpen] = useState<ReviewItem | null>(null);
  const q = useQuery({ queryKey: ['pln', 'review-queue', status, region], queryFn: () => planningApi.reviewQueue(status || undefined, region || undefined), retry: false });
  const locs = useQuery({ queryKey: ['pln', 'locations'], queryFn: planningApi.locations, retry: false });
  const regions = (locs.data?.items ?? []).filter((l) => l.level === 'REGION');
  const approver = isApprover(roles);
  const items = useMemo(() => q.data?.items ?? [], [q.data]);
  const decidable = useMemo(() => items.filter((i) => i.status === 'SUBMITTED' || i.status === 'UNDER_REVIEW'), [items]);
  const all = decidable.length > 0 && decidable.every((i) => picked.includes(i.id));
  const toggle = (id: string) => setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]));
  return (
    <Stack spacing={1.5} data-testid="review-queue">
      <SectionCard title="Plans awaiting review" subtitle={q.data?.notice ?? 'Approving records a decision about the plan. It does not order stock, move stock, publish products or change prices.'}
        action={(
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="rq-s">Show</InputLabel>
              <Select labelId="rq-s" label="Show" value={status} onChange={(e) => { setStatus(e.target.value); setPicked([]); }} SelectDisplayProps={{ 'data-testid': 'rq-status' } as React.HTMLAttributes<HTMLDivElement>}>
                <MenuItem value="">Awaiting review</MenuItem><MenuItem value="APPROVED">Approved</MenuItem><MenuItem value="RETURNED">Returned</MenuItem><MenuItem value="REJECTED">Rejected</MenuItem></Select></FormControl>
            <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="rq-r">Region</InputLabel>
              <Select labelId="rq-r" label="Region" value={region} onChange={(e) => { setRegion(e.target.value); setPicked([]); }} SelectDisplayProps={{ 'data-testid': 'rq-region' } as React.HTMLAttributes<HTMLDivElement>}>
                <MenuItem value="">All regions</MenuItem>{regions.map((r) => <MenuItem key={r.id} value={r.id}>{r.name}</MenuItem>)}</Select></FormControl>
          </Stack>
        )}>
        {!approver && <Alert severity="info" sx={{ mb: 1 }} data-testid="rq-readonly">You can see the queue, but only a regional manager, category manager or administrator can approve, return or reject plans.</Alert>}
        {q.isLoading && <LoadingBlock rows={1} />}
        {q.isError && <ErrorPanel testId="rq-error" message={planningError(q.error)} onRetry={() => void q.refetch()} />}
        {q.data && items.length === 0 && <EmptyState testId="rq-empty" title="Nothing is waiting for review" body="Plans appear here once a planner submits them. Submitted plans keep their forecast, target and adjustments frozen until a reviewer decides." />}
        {items.length > 0 && (
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small" stickyHeader data-testid="rq-table">
              <TableHead><TableRow>
                <TableCell padding="checkbox">{approver && <Checkbox size="small" checked={all} indeterminate={!all && picked.length > 0} onChange={() => setPicked(all ? [] : decidable.map((i) => i.id))} inputProps={{ 'aria-label': 'Select all plans', 'data-testid': 'rq-all' } as React.InputHTMLAttributes<HTMLInputElement>} />}</TableCell>
                <TableCell>Plan</TableCell><TableCell>Status</TableCell><TableCell>Data</TableCell><TableCell>Regions</TableCell><TableCell align="right">Lines</TableCell><TableCell align="right">Planned</TableCell><TableCell align="right">Target</TableCell><TableCell>Evidence</TableCell><TableCell>Submitted</TableCell><TableCell />
              </TableRow></TableHead>
              <TableBody>{items.map((i) => {
                const decidableRow = i.status === 'SUBMITTED' || i.status === 'UNDER_REVIEW';
                return (
                  <TableRow key={i.id} hover data-testid="rq-row" selected={picked.includes(i.id)}>
                    <TableCell padding="checkbox">{approver && decidableRow && <Checkbox size="small" checked={picked.includes(i.id)} onChange={() => toggle(i.id)} inputProps={{ 'aria-label': `Select ${i.name}`, 'data-testid': 'rq-pick' } as React.InputHTMLAttributes<HTMLInputElement>} />}</TableCell>
                    <TableCell><Typography variant="body2" fontWeight={800}>{i.name}</Typography><Typography variant="caption" color="text.secondary">{i.fy_label} · rev {i.revision} · {day(i.first_week)} to {day(i.last_week)}</Typography></TableCell>
                    <TableCell><PlanStatusChip status={i.status} /></TableCell>
                    <TableCell><ProvenanceChip kind={i.data_scope.synthetic ? 'DEMO' : 'LIVE'} /></TableCell>
                    <TableCell><Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>{i.regions.map((r) => <Chip key={r} size="small" label={r} variant="outlined" />)}</Stack></TableCell>
                    <TableCell align="right">{n(i.lines)}<Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{i.stores} stores</Typography></TableCell>
                    <TableCell align="right">{n(i.planned_units)}{i.adjusted_lines > 0 && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{n(i.adjusted_lines)} adjusted</Typography>}</TableCell>
                    <TableCell align="right">{i.target_units === null ? '—' : n(i.target_units)}</TableCell>
                    <TableCell><ConfidenceMeter level={i.low_confidence_share >= 50 ? 'LOW' : 'MEDIUM'} reason={`${pctText(i.low_confidence_share, 0)} of lines are low confidence; ${i.no_forecast_lines} have no forecast`} /></TableCell>
                    <TableCell><Typography variant="caption">{when(i.submitted_at)}</Typography><Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>by {i.submitted_by_ref ?? '—'}</Typography></TableCell>
                    <TableCell><Stack direction="row" spacing={0.5}><Button size="small" onClick={() => setOpen(i)} data-testid="rq-breakdown-open">Regions & stores</Button><Button size="small" onClick={() => goTab('workspace', { plan: i.id })} data-testid="rq-open">Open</Button></Stack></TableCell>
                  </TableRow>
                );
              })}</TableBody>
            </Table>
          </Box>
        )}
        {approver && decidable.length > 0 && (
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 1.5, p: 1, borderRadius: 2, bgcolor: '#F8FAFC' }} data-testid="rq-bar">
            <Typography variant="body2" sx={{ flex: 1 }}>{picked.length === 0 ? 'Select plans to review them together.' : `${picked.length} plan${picked.length === 1 ? '' : 's'} selected`}</Typography>
            <Button variant="contained" disabled={picked.length === 0} onClick={() => setDialog({ action: 'APPROVE', ids: picked })} data-testid="rq-approve">Approve…</Button>
            <Button disabled={picked.length === 0} onClick={() => setDialog({ action: 'RETURN', ids: picked })} data-testid="rq-return">Return…</Button>
            <Button color="warning" disabled={picked.length === 0} onClick={() => setDialog({ action: 'REJECT', ids: picked })} data-testid="rq-reject">Reject…</Button>
          </Stack>
        )}
      </SectionCard>
      {dialog && <BulkDialog ids={dialog.ids} action={dialog.action} onClose={() => setDialog(null)} onDone={() => { setPicked([]); void qc.invalidateQueries({ queryKey: ['pln'] }); }} />}
      {open && <Breakdown plan={open} onClose={() => setOpen(null)} />}
    </Stack>
  );
};

export default ReviewQueue;
