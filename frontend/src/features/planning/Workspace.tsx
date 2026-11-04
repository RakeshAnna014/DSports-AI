import { useMemo, useState } from 'react';
import {
  Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, FormControlLabel, IconButton, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell,
  TableHead, TablePagination, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import { QueryStatsOutlined, SearchOutlined } from '@mui/icons-material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { REASON_CODES, conflictsOf, planningApi, planningError, type Conflict, type Line, type LineEdit } from '@/api/planning';
import FilterBar from './FilterBar';
import { ConfChip, day, n, pctText, pretty, signed, usePlanning, usedForecast } from './parts';

const keyOf = (l: Pick<Line, 'product_id' | 'location_id' | 'week_start'>) => `${l.product_id}|${l.location_id}|${l.week_start}`;
type Draft = { target?: string; manager?: string };
const parse = (s: string): number | null | 'bad' => { if (s.trim() === '') return null; const v = Number(s); return Number.isFinite(v) && v >= 0 && v <= 1_000_000 ? v : 'bad'; };

const Investigate = ({ planId, line, onClose }: { planId: string; line: Line; onClose: () => void }) => {
  const q = useQuery({ queryKey: ['pln', 'investigate', planId, keyOf(line)], queryFn: () => planningApi.investigate(planId, line.product_id, line.location_id, line.week_start), retry: false });
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth data-testid="investigate-dialog">
      <DialogTitle>Why does {line.product_name} at {line.store_name} differ in the week of {day(line.week_start)}?</DialogTitle>
      <DialogContent>
        {q.isLoading && <Skeleton variant="rounded" height={120} />}
        {q.isError && <Alert severity="warning">{planningError(q.error)}</Alert>}
        {q.data && (
          <Stack spacing={1}>
            <Alert severity={q.data.flagged ? 'warning' : 'info'} icon={false}>{q.data.flagged ? `Flagged: the difference is at least ${q.data.thresholds.percent}% and ${q.data.thresholds.units} units.` : 'Not flagged: the difference is within the thresholds or the week is open.'}</Alert>
            <Typography variant="subtitle2" fontWeight={900}>What the records show</Typography>
            {q.data.facts.map((f) => <Typography key={f} variant="body2" data-testid="fact">• {f}</Typography>)}
            <Typography variant="subtitle2" fontWeight={900}>Possible reasons (hypotheses, not findings)</Typography>
            {q.data.hypotheses.length === 0 ? <Typography variant="body2" color="text.secondary">None suggested by the records.</Typography> : q.data.hypotheses.map((h) => <Typography key={h} variant="body2" data-testid="hypothesis">• {h}</Typography>)}
            <Typography variant="caption" color="text.secondary">{q.data.limits.join(' ')}</Typography>
          </Stack>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  );
};

const Impact = ({ planId, line, onClose }: { planId: string; line: Line; onClose: () => void }) => {
  const q = useQuery({ queryKey: ['pln', 'impact', planId, keyOf(line), line.line_version], queryFn: () => planningApi.impact(planId, line.product_id, line.location_id), retry: false });
  const d = q.data;
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="impact-dialog">
      <DialogTitle>Stock effect for {line.product_name} at {line.store_name}</DialogTitle>
      <DialogContent>
        {q.isLoading && <Skeleton variant="rounded" height={100} />}
        {q.isError && <Alert severity="warning">{planningError(q.error)}</Alert>}
        {d && !d.available && <Alert severity="info">{d.reason}</Alert>}
        {d?.available && d.model && d.plan && (
          <Table size="small"><TableHead><TableRow><TableCell /><TableCell align="right">With the model forecast</TableCell><TableCell align="right">With the plan's forecast</TableCell></TableRow></TableHead>
            <TableBody>{(['forecast_30', 'risk_level', 'coverage_days', 'order_qty', 'safety_stock', 'stockout_date'] as const).map((k) => (
              <TableRow key={k}><TableCell>{pretty(k)}</TableCell><TableCell align="right">{String(d.model?.[k] ?? '—')}</TableCell><TableCell align="right" data-testid={`impact-${k}`}>{String(d.plan?.[k] ?? '—')}</TableCell></TableRow>))}</TableBody></Table>
        )}
        {d?.note && <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>{d.note}</Typography>}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  );
};

const Growth = ({ planId, onClose, onDone }: { planId: string; onClose: () => void; onDone: () => void }) => {
  const { filters } = usePlanning();
  const [g, setG] = useState('10');
  const [basis, setBasis] = useState<'MODEL' | 'MANAGER'>('MODEL');
  const [overwrite, setOverwrite] = useState(false);
  const go = useMutation({ mutationFn: () => planningApi.growthTargets(planId, { growth_pct: Number(g), basis, overwrite, filters }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth data-testid="growth-dialog">
      <DialogTitle>Set targets from a growth rate</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <Alert severity="info" icon={false}>Sets each target to the baseline plus the growth, for every line matching the current filters. A target is what you aim for; it does not change the forecast.</Alert>
        <TextField size="small" type="number" label="Growth %" value={g} onChange={(e) => setG(e.target.value)} inputProps={{ 'data-testid': 'growth-pct' }} />
        <FormControl size="small"><InputLabel id="gb-l">Baseline</InputLabel>
          <Select labelId="gb-l" label="Baseline" value={basis} onChange={(e) => setBasis(e.target.value as 'MODEL' | 'MANAGER')}><MenuItem value="MODEL">Model forecast</MenuItem><MenuItem value="MANAGER">Manager forecast (else model)</MenuItem></Select></FormControl>
        <FormControlLabel control={<Checkbox size="small" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)} />} label="Replace targets that already exist" />
        {go.isError && <Alert severity="warning">{planningError(go.error)}</Alert>}
        {go.data && <Alert severity="success" data-testid="growth-result">{go.data.lines_set} target(s) set; {go.data.lines_without_baseline} line(s) had no baseline and were skipped.</Alert>}
      </Stack></DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button><Button variant="contained" disabled={go.isPending || !Number.isFinite(Number(g))} onClick={() => go.mutate()} data-testid="growth-apply">Apply</Button></DialogActions>
    </Dialog>
  );
};

/** The weekly planning grid: one server-paginated query fills it. Model forecast, latest forecast, manager forecast, approved forecast, target and actual are separate columns, always. */
const Workspace = () => {
  const { planId, filters } = usePlanning();
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [sort, setSort] = useState('product');
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [reasonOpen, setReasonOpen] = useState(false);
  const [reason, setReason] = useState<string>('PROMOTION');
  const [note, setNote] = useState('');
  const [conflicts, setConflicts] = useState<Conflict[]>([]);
  const [saved, setSaved] = useState<number | null>(null);
  const [why, setWhy] = useState<Line | null>(null);
  const [impact, setImpact] = useState<Line | null>(null);
  const [growth, setGrowth] = useState(false);
  const [filterKey, setFilterKey] = useState('');
  const fk = JSON.stringify(filters);
  if (fk !== filterKey) { setFilterKey(fk); if (page !== 0) setPage(0); }
  const q = useQuery({ queryKey: ['pln', 'lines', planId, filters, sort, page, size], queryFn: () => planningApi.lines(planId as string, filters, sort, page, size), enabled: !!planId, retry: false });
  const rows = useMemo(() => q.data?.items ?? [], [q.data]);
  const editable = !!q.data?.plan.editable;
  const byKey = useMemo(() => new Map(rows.map((r) => [keyOf(r), r])), [rows]);
  const set = (l: Line, patch: Draft) => setDrafts((d) => ({ ...d, [keyOf(l)]: { ...d[keyOf(l)], ...patch } }));
  const dirty = Object.entries(drafts).filter(([k, d]) => byKey.has(k) && ((d.target !== undefined && d.target !== String(byKey.get(k)!.target_units ?? '')) || (d.manager !== undefined && d.manager !== String(byKey.get(k)!.manager_forecast ?? ''))));
  const invalid = dirty.some(([, d]) => (d.target !== undefined && parse(d.target) === 'bad') || (d.manager !== undefined && parse(d.manager) === 'bad'));
  const managerChanged = dirty.some(([k, d]) => d.manager !== undefined && d.manager !== String(byKey.get(k)!.manager_forecast ?? '') && parse(d.manager) !== null);
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['pln'] }); };
  const save = useMutation({
    mutationFn: () => {
      const edits: LineEdit[] = dirty.map(([k, d]) => {
        const l = byKey.get(k)!;
        const e: LineEdit = { product_id: l.product_id, location_id: l.location_id, week_start: l.week_start, expected_line_version: l.line_version };
        if (d.target !== undefined && d.target !== String(l.target_units ?? '')) e.target_units = parse(d.target) as number | null;
        if (d.manager !== undefined && d.manager !== String(l.manager_forecast ?? '')) { e.manager_forecast = parse(d.manager) as number | null; if (e.manager_forecast !== null) { e.reason_code = reason; e.note = note.trim() || undefined; } }
        return e;
      });
      return planningApi.editLines(planId as string, edits);
    },
    onSuccess: (r) => { setSaved(r.changed); setDrafts({}); setConflicts([]); setReasonOpen(false); setNote(''); refresh(); },
    onError: (e) => { setConflicts(conflictsOf(e)); setReasonOpen(false); },
  });
  const exportCsv = async () => {
    const all: Line[] = [];
    for (let p = 0; p < 10; p++) { const r = await planningApi.lines(planId as string, filters, sort, p, 200); all.push(...r.items); if (all.length >= r.total) break; }
    const head = ['product', 'sku', 'store', 'region', 'week_start', 'model_forecast', 'latest_forecast', 'manager_forecast', 'approved_forecast', 'target_units', 'actual_units', 'confidence'];
    const csv = [head.join(','), ...all.map((l) => [l.product_name, l.sku, l.location_id, l.region, l.week_start, l.model_forecast, l.latest_forecast, l.manager_forecast, l.approved_forecast, l.target_units, l.actual_units, l.confidence]
      .map((v) => (v === null ? '' : `"${String(v).replace(/"/g, '""')}"`)).join(','))].join('\n');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    a.download = `plan-${planId?.slice(0, 8)}.csv`;
    a.click();
  };
  if (!planId) return <Alert severity="info" data-testid="no-plan">Choose a plan above, or create one from the current forecast, to plan weekly units per product and store.</Alert>;
  return (
    <Stack spacing={1.25} data-testid="workspace">
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center"><FilterBar />
        <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="ws-sort-l">Sort</InputLabel>
          <Select labelId="ws-sort-l" label="Sort" value={sort} onChange={(e) => setSort(e.target.value)}>
            {[['product', 'Product'], ['store', 'Store'], ['week', 'Week'], ['model', 'Model forecast'], ['target', 'Target'], ['variance', 'Largest variance']].map(([v, t]) => <MenuItem key={v} value={v}>{t}</MenuItem>)}</Select></FormControl></Stack>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        {editable && <Button size="small" variant="outlined" onClick={() => setGrowth(true)} data-testid="ws-growth">Targets from growth %</Button>}
        <Button size="small" variant="outlined" onClick={() => void exportCsv()} data-testid="ws-export">Export CSV</Button>
        {editable && <Button size="small" variant="contained" disabled={dirty.length === 0 || invalid || save.isPending} onClick={() => (managerChanged ? setReasonOpen(true) : save.mutate())} data-testid="ws-save">Save {dirty.length} change(s)</Button>}
        {dirty.length > 0 && <Button size="small" onClick={() => setDrafts({})}>Discard</Button>}
        {!editable && q.data && <Alert severity="info" icon={false} sx={{ py: 0 }} data-testid="ws-frozen">This plan is {pretty(q.data.plan.status)} and cannot be edited. Create a revision to change it.</Alert>}
      </Stack>
      {saved !== null && <Alert severity="success" onClose={() => setSaved(null)} data-testid="ws-saved">{saved} line(s) saved.</Alert>}
      {invalid && <Alert severity="warning" data-testid="ws-invalid">One of the numbers is not valid: use a number from 0 to 1,000,000, or leave the cell empty to clear it.</Alert>}
      {conflicts.length > 0 && (
        <Alert severity="error" data-testid="ws-conflict" action={<Button size="small" onClick={() => { setDrafts({}); setConflicts([]); refresh(); }}>Reload</Button>}>
          Nothing was saved: {conflicts.length} line(s) changed or are outside your scope. {conflicts.slice(0, 3).map((c) => `${c.location_id} ${day(c.week_start)}: ${c.message}`).join(' · ')}
        </Alert>
      )}
      {q.isLoading && <Skeleton variant="rounded" height={260} />}
      {q.isError && <Alert severity="warning" data-testid="ws-error">{planningError(q.error)}</Alert>}
      {q.data && (
        <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
          <Table size="small" stickyHeader>
            <TableHead><TableRow>
              <TableCell>Product</TableCell><TableCell>Store</TableCell><TableCell>Week</TableCell>
              <TableCell align="right"><Tooltip title="The statistical forecast frozen in this plan. It never changes."><span>Model forecast</span></Tooltip></TableCell>
              <TableCell align="right"><Tooltip title="The current forecast run's number for this week (can differ after a re-run)."><span>Latest model</span></Tooltip></TableCell>
              <TableCell align="right">Manager forecast</TableCell><TableCell align="right">Approved forecast</TableCell><TableCell align="right">Target</TableCell><TableCell align="right">Actual</TableCell>
              <TableCell align="right">Forecast var.</TableCell><TableCell align="right">Target var.</TableCell><TableCell align="right">Stock + incoming</TableCell><TableCell>Confidence</TableCell><TableCell />
            </TableRow></TableHead>
            <TableBody>
              {rows.map((l) => {
                const d = drafts[keyOf(l)] ?? {};
                const f = usedForecast(l);
                const fv = l.actual_units !== null && f !== null ? l.actual_units - f : null;
                const tv = l.actual_units !== null && l.target_units !== null ? l.actual_units - l.target_units : null;
                return (
                  <TableRow key={keyOf(l)} hover data-testid="ws-row" data-key={keyOf(l)}>
                    <TableCell><Typography variant="body2" fontWeight={700}>{l.product_name}</Typography><Typography variant="caption" color="text.secondary">{l.sku} · {l.sport}/{l.category}</Typography></TableCell>
                    <TableCell>{l.store_name}<Typography variant="caption" color="text.secondary" display="block">{l.region}</Typography></TableCell>
                    <TableCell>{day(l.week_start)}{!l.week_closed && <Typography variant="caption" color="text.secondary" display="block">open week</Typography>}</TableCell>
                    <TableCell align="right" data-testid="ws-model">{l.model_forecast === null ? <Tooltip title="Insufficient data: the model gave no forecast."><span>—</span></Tooltip> : n(l.model_forecast, 1)}{l.model_lo !== null && <Typography variant="caption" color="text.secondary" display="block">{n(l.model_lo, 1)}–{n(l.model_hi, 1)}</Typography>}</TableCell>
                    <TableCell align="right">{n(l.latest_forecast, 1)}</TableCell>
                    <TableCell align="right">{editable
                      ? <TextField size="small" value={d.manager ?? String(l.manager_forecast ?? '')} onChange={(e) => set(l, { manager: e.target.value })} error={d.manager !== undefined && parse(d.manager) === 'bad'} inputProps={{ 'data-testid': 'ws-manager', style: { textAlign: 'right', width: 62 } }} />
                      : n(l.manager_forecast, 1)}{l.adj_reason && <Typography variant="caption" color="text.secondary" display="block" title={l.adj_note ?? ''}>{pretty(l.adj_reason)}</Typography>}</TableCell>
                    <TableCell align="right" data-testid="ws-approved">{n(l.approved_forecast, 1)}</TableCell>
                    <TableCell align="right">{editable
                      ? <TextField size="small" value={d.target ?? String(l.target_units ?? '')} onChange={(e) => set(l, { target: e.target.value })} error={d.target !== undefined && parse(d.target) === 'bad'} inputProps={{ 'data-testid': 'ws-target', style: { textAlign: 'right', width: 62 } }} />
                      : n(l.target_units, 1)}</TableCell>
                    <TableCell align="right" data-testid="ws-actual">{n(l.actual_units, 0)}{l.actual_units !== null && <Typography variant="caption" color="text.secondary" display="block">{l.adjustment_units ? `${l.authoritative_units} ${signed(l.adjustment_units)} corr.` : pretty(l.actual_source)}</Typography>}</TableCell>
                    <TableCell align="right" data-testid="ws-fvar">{signed(fv, 1)}</TableCell>
                    <TableCell align="right" data-testid="ws-tvar">{signed(tv, 1)}{l.actual_units !== null && l.target_units ? <Typography variant="caption" color="text.secondary" display="block">{pctText((l.actual_units / l.target_units) * 100, 0)}</Typography> : null}</TableCell>
                    <TableCell align="right">{n(l.sellable_stock)} + {n(l.incoming_stock)}</TableCell>
                    <TableCell><ConfChip c={l.confidence} /></TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      <Tooltip title="Stock effect of this forecast"><span><IconButton size="small" onClick={() => setImpact(l)} data-testid="ws-impact"><QueryStatsOutlined fontSize="small" /></IconButton></span></Tooltip>
                      <Tooltip title={l.week_closed ? 'Investigate the variance' : 'The week is not closed'}><span><IconButton size="small" disabled={!l.week_closed || l.actual_units === null} onClick={() => setWhy(l)} data-testid="ws-why"><SearchOutlined fontSize="small" /></IconButton></span></Tooltip>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          {rows.length === 0 && <Box sx={{ p: 3, textAlign: 'center' }} data-testid="ws-empty"><Typography color="text.secondary">No line matches these filters.</Typography></Box>}
          <TablePagination component="div" count={q.data.total} page={page} rowsPerPage={size} rowsPerPageOptions={[25, 50, 100, 200]} onPageChange={(_, p) => setPage(p)} onRowsPerPageChange={(e) => { setSize(Number(e.target.value)); setPage(0); }} data-testid="ws-pagination" />
        </Paper>
      )}
      <Dialog open={reasonOpen} onClose={() => setReasonOpen(false)} maxWidth="xs" fullWidth data-testid="reason-dialog">
        <DialogTitle>Why are you adjusting the forecast?</DialogTitle>
        <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
          <Typography variant="body2">The model's number is kept; your adjustment is stored beside it with this reason and your name.</Typography>
          <FormControl size="small"><InputLabel id="rc-l">Reason</InputLabel>
            <Select labelId="rc-l" label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} SelectDisplayProps={{ 'data-testid': 'reason-code' } as React.HTMLAttributes<HTMLDivElement>}>
              {REASON_CODES.map((r) => <MenuItem key={r} value={r}>{pretty(r)}</MenuItem>)}</Select></FormControl>
          <TextField size="small" multiline minRows={2} label={reason === 'OTHER' ? 'Explanation (required)' : 'Explanation (optional)'} value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ 'data-testid': 'reason-note' }} />
        </Stack></DialogContent>
        <DialogActions><Button onClick={() => setReasonOpen(false)}>Cancel</Button><Button variant="contained" disabled={save.isPending || (reason === 'OTHER' && note.trim().length < 5)} onClick={() => save.mutate()} data-testid="reason-save">Save</Button></DialogActions>
      </Dialog>
      {why && <Investigate planId={planId} line={why} onClose={() => setWhy(null)} />}
      {impact && <Impact planId={planId} line={impact} onClose={() => setImpact(null)} />}
      {growth && <Growth planId={planId} onClose={() => setGrowth(false)} onDone={refresh} />}
    </Stack>
  );
};

export default Workspace;
