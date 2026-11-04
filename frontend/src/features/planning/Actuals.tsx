import { useState } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ADJUSTMENT_REASONS, isApprover, planningApi, planningError, type Source } from '@/api/planning';
import FilterBar from './FilterBar';
import { ScopeBanner, day, n, pretty, usePlanning } from './parts';

const CSV_HELP = 'sku,store,week_start,gross_units,cancelled_units,returned_units\nT-BAT-1,N-Z1-S1,2026-10-05,24,1,0';

const ImportDialog = ({ onClose, onDone }: { onClose: () => void; onDone: () => void }) => {
  const [text, setText] = useState(CSV_HELP);
  const [batch, setBatch] = useState(`ui-${Date.now().toString(36)}`);
  const [note, setNote] = useState('');
  const go = useMutation({
    mutationFn: () => {
      const [head, ...rest] = text.trim().split(/\r?\n/);
      const cols = head.split(',').map((c) => c.trim());
      const rows = rest.filter((l) => l.trim()).map((l) => {
        const v = l.split(',').map((x) => x.trim());
        const r: Record<string, string | number> = {};
        cols.forEach((c, i) => { r[c === 'store' ? 'location_id' : c] = ['sku', 'location_id', 'week_start', 'store'].includes(c) ? v[i] : Number(v[i]); });
        return r;
      });
      return planningApi.importActuals({ batch_id: batch, rows, note: note || undefined });
    },
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth data-testid="import-dialog">
      <DialogTitle>Import weekly store actuals</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <Alert severity="info" icon={false}>Closed weeks, one row per product, store and week (store channel only: the live online feed is never overwritten). A row with any problem rejects the whole batch. Importing the same batch id twice changes nothing. Every import is audited.</Alert>
        <TextField size="small" label="Batch id (idempotency key)" value={batch} onChange={(e) => setBatch(e.target.value)} inputProps={{ 'data-testid': 'import-batch' }} />
        <TextField multiline minRows={6} size="small" label="CSV: sku,store,week_start,gross_units,cancelled_units,returned_units" value={text} onChange={(e) => setText(e.target.value)} inputProps={{ 'data-testid': 'import-text', style: { fontFamily: 'monospace' } }} />
        <TextField size="small" label="Note (source of the figures)" value={note} onChange={(e) => setNote(e.target.value)} />
        {go.isError && <Alert severity="error" data-testid="import-error">{planningError(go.error)}</Alert>}
        {go.data && <Alert severity="success" data-testid="import-result">{go.data.already_applied ? 'This batch was already imported: nothing changed.' : `${go.data.rows_applied} row(s) imported.`}</Alert>}
      </Stack></DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button><Button variant="contained" disabled={go.isPending || batch.length < 6} onClick={() => go.mutate()} data-testid="import-run">Import</Button></DialogActions>
    </Dialog>
  );
};

const Correct = ({ onClose, onDone }: { onClose: () => void; onDone: () => void }) => {
  const [f, setF] = useState({ product_id: '', location_id: '', week_start: '', delta_units: '', reason_code: 'DATA_CORRECTION', note: '' });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  const go = useMutation({ mutationFn: () => planningApi.adjust({ ...f, delta_units: Number(f.delta_units) }), onSuccess: () => { onDone(); onClose(); } });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="correct-dialog">
      <DialogTitle>Request a correction to an actual</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <Alert severity="info" icon={false}>A correction never overwrites the recorded actual. Once someone else approves it, it is shown as a separate +/- amount beside the original figure.</Alert>
        <TextField size="small" label="Product id" value={f.product_id} onChange={set('product_id')} inputProps={{ 'data-testid': 'corr-product' }} />
        <Stack direction="row" spacing={1.5}><TextField size="small" label="Store id" value={f.location_id} onChange={set('location_id')} inputProps={{ 'data-testid': 'corr-store' }} /><TextField size="small" label="Week start" placeholder="2026-10-05" value={f.week_start} onChange={set('week_start')} inputProps={{ 'data-testid': 'corr-week' }} /></Stack>
        <Stack direction="row" spacing={1.5}><TextField size="small" type="number" label="Units to add (+) or remove (−)" value={f.delta_units} onChange={set('delta_units')} inputProps={{ 'data-testid': 'corr-delta' }} />
          <FormControl size="small" sx={{ minWidth: 220 }}><InputLabel id="cr-l">Reason</InputLabel><Select labelId="cr-l" label="Reason" value={f.reason_code} onChange={(e) => setF((x) => ({ ...x, reason_code: e.target.value }))}>{ADJUSTMENT_REASONS.map((r) => <MenuItem key={r} value={r}>{pretty(r)}</MenuItem>)}</Select></FormControl></Stack>
        <TextField size="small" multiline minRows={2} label="Explanation (at least 10 characters)" value={f.note} onChange={set('note')} inputProps={{ 'data-testid': 'corr-note' }} />
        {go.isError && <Alert severity="warning" data-testid="corr-error">{planningError(go.error)}</Alert>}
      </Stack></DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={go.isPending || f.note.trim().length < 10 || !f.delta_units} onClick={() => go.mutate()} data-testid="corr-send">Request</Button></DialogActions>
    </Dialog>
  );
};

/** Weekly actuals with the sales convention spelled out (gross, cancelled, returned, net), the source, and whether each week is closed; plus the controlled import and correction workflows. */
const Actuals = () => {
  const { source: ctxSource, filters, roles } = usePlanning();
  const qc = useQueryClient();
  const [source, setSource] = useState<Source>(ctxSource);
  const [dim, setDim] = useState('total');
  const [from, setFrom] = useState('2026-08-31');
  const [to, setTo] = useState('2026-10-12');
  const [importing, setImporting] = useState(false);
  const [correcting, setCorrecting] = useState(false);
  const q = useQuery({ queryKey: ['pln', 'actuals', source, from, to, dim, filters], queryFn: () => planningApi.actuals(source, from, to, dim, filters), retry: false });
  const adj = useQuery({ queryKey: ['pln', 'adjustments'], queryFn: () => planningApi.adjustments(), retry: false });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['pln'] }); };
  const decide = useMutation({ mutationFn: (v: { id: string; approve: boolean; note?: string }) => planningApi.decideAdjustment(v.id, v.approve, v.note), onSuccess: refresh });
  return (
    <Stack spacing={1.25} data-testid="actuals">
      <ScopeBanner source={source} />
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        <FormControl size="small" sx={{ minWidth: 190 }}><InputLabel id="as-l">Data scope</InputLabel>
          <Select labelId="as-l" label="Data scope" value={source} onChange={(e) => setSource(e.target.value as Source)} SelectDisplayProps={{ 'data-testid': 'act-source' } as React.HTMLAttributes<HTMLDivElement>}>
            <MenuItem value="POC_SYNTHETIC">Demonstration data</MenuItem><MenuItem value="DSPORTS_LIVE">Live online orders</MenuItem></Select></FormControl>
        <FormControl size="small" sx={{ minWidth: 140 }}><InputLabel id="ad-l">Group by</InputLabel>
          <Select labelId="ad-l" label="Group by" value={dim} onChange={(e) => setDim(e.target.value)}>{['total', 'region', 'zone', 'store', 'sport', 'category', 'product'].map((v) => <MenuItem key={v} value={v}>{pretty(v)}</MenuItem>)}</Select></FormControl>
        <TextField size="small" type="date" label="From week" value={from} onChange={(e) => setFrom(e.target.value)} InputLabelProps={{ shrink: true }} />
        <TextField size="small" type="date" label="To week" value={to} onChange={(e) => setTo(e.target.value)} InputLabelProps={{ shrink: true }} />
        <FilterBar show={['sport', 'category', 'region', 'store']} />
        <Button size="small" variant="outlined" onClick={() => setImporting(true)} data-testid="act-import">Import store actuals</Button>
        <Button size="small" variant="outlined" onClick={() => setCorrecting(true)} data-testid="act-correct">Request a correction</Button>
      </Stack>
      {q.isLoading && <Skeleton variant="rounded" height={160} />}
      {q.isError && <Alert severity="warning" data-testid="act-error">{planningError(q.error)}</Alert>}
      {q.data && (
        <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
          <Alert severity="info" icon={false}>{q.data.notice} Data through {q.data.data_through ?? 'unknown'}.</Alert>
          <Table size="small">
            <TableHead><TableRow><TableCell>Week</TableCell><TableCell>{pretty(dim)}</TableCell><TableCell align="right">Gross units</TableCell><TableCell align="right">Cancelled</TableCell><TableCell align="right">Returned</TableCell><TableCell align="right">Net units</TableCell><TableCell align="right">Net revenue</TableCell><TableCell>Source</TableCell><TableCell>State</TableCell></TableRow></TableHead>
            <TableBody>{q.data.rows.map((r) => (
              <TableRow key={`${r.week_start}|${r.gkey}`} data-testid="act-row"><TableCell>{day(r.week_start)}</TableCell><TableCell>{r.gkey === 'ALL' ? 'All' : r.gkey}</TableCell><TableCell align="right">{n(r.gross_units)}</TableCell><TableCell align="right">{n(r.cancelled_units)}</TableCell>
                <TableCell align="right">{n(r.returned_units)}</TableCell><TableCell align="right" data-testid="act-net">{n(r.net_units)}</TableCell><TableCell align="right">{n(r.net_revenue)}</TableCell><TableCell>{r.sources.map((s) => pretty(s)).join(', ')}</TableCell>
                <TableCell><Chip size="small" variant="outlined" color={r.week_closed ? 'success' : 'warning'} label={r.week_closed ? 'Closed' : 'Open'} /></TableCell></TableRow>))}</TableBody>
          </Table>
          {q.data.rows.length === 0 && <Box sx={{ p: 3, textAlign: 'center' }} data-testid="act-empty"><Typography color="text.secondary">No sales are recorded for these weeks and filters.</Typography></Box>}
        </Paper>
      )}
      <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="adjustments">
        <Typography variant="subtitle2" fontWeight={900}>Corrections to actuals</Typography>
        {(adj.data ?? []).length === 0 && <Typography variant="body2" color="text.secondary">No correction has been requested.</Typography>}
        {(adj.data ?? []).map((a) => (
          <Stack key={a.id} direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ py: 0.25 }} data-testid="adj-row">
            <Chip size="small" color={a.status === 'APPROVED' ? 'success' : a.status === 'REJECTED' ? 'error' : 'warning'} label={pretty(a.status)} />
            <Typography variant="body2">{a.product_name ?? a.product_id.slice(0, 8)} · {a.store_name ?? a.location_id} · week {day(a.week_start)}: <b>{a.delta_units > 0 ? '+' : ''}{a.delta_units}</b> unit(s), {pretty(a.reason_code)}. {a.note}</Typography>
            {a.status === 'PENDING' && isApprover(roles) && (<><Button size="small" onClick={() => decide.mutate({ id: a.id, approve: true })} data-testid="adj-approve">Approve</Button><Button size="small" color="error" onClick={() => decide.mutate({ id: a.id, approve: false, note: 'Not supported by the records' })}>Reject</Button></>)}
          </Stack>))}
        {decide.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="adj-error">{planningError(decide.error)}</Alert>}
      </Paper>
      {importing && <ImportDialog onClose={() => setImporting(false)} onDone={refresh} />}
      {correcting && <Correct onClose={() => setCorrecting(false)} onDone={refresh} />}
    </Stack>
  );
};

export default Actuals;
