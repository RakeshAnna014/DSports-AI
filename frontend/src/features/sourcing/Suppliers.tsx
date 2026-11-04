import { safeHref } from '@/utils/safeUrl';
import { useState } from 'react';
import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Link, MenuItem, Paper, Skeleton, Stack, Switch, Table, TableBody, TableCell, TableHead, TextField, TableRow, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { sourcingApi, sourcingError, type SupplierStage } from '@/api/sourcing';
import { ProvenanceChip, STAGES, StageChip, pretty, stageLabel } from './parts';

const TYPES = ['MANUFACTURER', 'AUTHORISED_DISTRIBUTOR', 'WHOLESALER', 'REGIONAL_DISTRIBUTOR', 'IMPORTER', 'DIRECT_SUPPLIER'];
const list = (s: string) => s.split(',').map((x) => x.trim()).filter(Boolean);

const AddSupplier = ({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) => {
  const [f, setF] = useState({ name: '', type: 'WHOLESALER', website: '', country: '', region: '', email: '', phone: '', categories: '', brands: '', authorised: '', note: '' });
  const [isTest, setIsTest] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  const save = useMutation({
    mutationFn: () => sourcingApi.createSupplier({
      is_test: isTest || undefined, name: f.name, type: f.type, website: f.website || undefined, country: f.country || undefined, region: f.region || undefined, categories: list(f.categories), brands: list(f.brands),
      authorised_brands: list(f.authorised), authorisation_note: f.note || undefined, contact: Object.fromEntries([['email', f.email], ['phone', f.phone]].filter(([, v]) => v)),
    }),
    onSuccess: () => { onSaved(); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="supplier-dialog">
      <DialogTitle>Add a supplier</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Alert severity="info" icon={false}>Record only what you know. Brand authorisation is listed only if you have seen evidence of it.</Alert>
          <TextField size="small" label="Name" value={f.name} onChange={set('name')} inputProps={{ 'data-testid': 's-name' }} />
          <TextField select size="small" label="Type" value={f.type} onChange={set('type')}>{TYPES.map((t) => <MenuItem key={t} value={t}>{pretty(t)}</MenuItem>)}</TextField>
          <Stack direction="row" spacing={1.5}><TextField size="small" label="Website" value={f.website} onChange={set('website')} fullWidth /><TextField size="small" label="Country" value={f.country} onChange={set('country')} /><TextField size="small" label="Region" value={f.region} onChange={set('region')} /></Stack>
          <Stack direction="row" spacing={1.5}><TextField size="small" label="Contact e-mail" value={f.email} onChange={set('email')} fullWidth /><TextField size="small" label="Phone" value={f.phone} onChange={set('phone')} /></Stack>
          <TextField size="small" label="Product categories (comma separated)" value={f.categories} onChange={set('categories')} />
          <TextField size="small" label="Brands they supply (comma separated)" value={f.brands} onChange={set('brands')} />
          <TextField size="small" label="Brands with authorisation evidence (comma separated)" value={f.authorised} onChange={set('authorised')} />
          <TextField size="small" label="Authorisation evidence note" value={f.note} onChange={set('note')} />
          <FormControlLabel control={<Checkbox size="small" checked={isTest} onChange={(e) => setIsTest(e.target.checked)} inputProps={{ 'data-testid': 's-test' } as React.InputHTMLAttributes<HTMLInputElement>} />}
            label={<Typography variant="body2">This is test or practice data (kept out of live figures)</Typography>} />
          {save.isError && <Alert severity="warning" data-testid="s-error">{sourcingError(save.error)}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={f.name.trim().length < 2 || save.isPending} onClick={() => save.mutate()} data-testid="s-save">Save supplier</Button></DialogActions>
    </Dialog>
  );
};

const NEXT: Record<SupplierStage, SupplierStage | null> = { DISCOVERED: 'WEBSITE_CHECKED', WEBSITE_CHECKED: 'IDENTITY_VERIFIED', IDENTITY_VERIFIED: 'BRAND_AUTHORISATION_VERIFIED', BRAND_AUTHORISATION_VERIFIED: null };

/** The verification ladder: a supplier moves up one rung at a time, with a note (and ideally evidence) recorded each time. The last two rungs need an approver; the server enforces that. */
const SupplierDetailDialog = ({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) => {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['src', 'supplier', id], queryFn: () => sourcingApi.supplier(id), retry: false });
  const [note, setNote] = useState('');
  const [url, setUrl] = useState('');
  const move = useMutation({ mutationFn: (stage: SupplierStage) => sourcingApi.setSupplierStage(id, stage, note.trim(), url.trim()), onSuccess: () => { setNote(''); setUrl(''); void qc.invalidateQueries({ queryKey: ['src'] }); onChanged(); } });
  const d = q.data;
  const next = d ? NEXT[d.supplier.verification_status] : null;
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth data-testid="supplier-detail">
      <DialogTitle>{d?.supplier.name ?? 'Supplier'}</DialogTitle>
      <DialogContent>
        {q.isLoading && <Skeleton variant="rounded" height={120} />}
        {q.isError && <Alert severity="warning">{sourcingError(q.error)}</Alert>}
        {d && (
          <Stack spacing={1.25}>
            <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap><ProvenanceChip provenance={d.supplier.provenance} /><StageChip stage={d.supplier.verification_status} /></Stack>
            <Typography variant="caption" color="text.secondary">{d.provenance_label}</Typography>
            <Typography variant="body2">{pretty(d.supplier.type)} · {[d.supplier.region, d.supplier.country].filter(Boolean).join(', ') || 'location not recorded'} · {d.supplier.website ?? 'no website recorded'}</Typography>
            {Object.keys(d.supplier.contact ?? {}).length > 0 && <Typography variant="body2" data-testid="supplier-contact">{Object.entries(d.supplier.contact).map(([k, v]) => `${pretty(k)}: ${v}`).join(' · ')}</Typography>}
            {d.supplier.discovery_note && <Alert severity="warning" icon={false}>{d.supplier.discovery_note}{d.supplier.discovery_url && <> <Link href={safeHref(d.supplier.discovery_url)} target="_blank" rel="noopener noreferrer">source</Link></>}</Alert>}
            <Box data-testid="supplier-ladder">
              <Typography variant="subtitle2" fontWeight={900}>Verification</Typography>
              {STAGES.map((s) => {
                const done = (d.supplier.verification_history ?? []).find((h) => h.stage === s);
                return <Typography key={s} variant="body2" color={done ? 'text.primary' : 'text.secondary'}>{done ? '✔' : '○'} {stageLabel(s)}{done ? ` — ${done.at.slice(0, 10)}${done.note ? `: ${done.note}` : ''}` : ''}</Typography>;
              })}
              <Typography variant="caption" color="text.secondary" display="block">Quotation received, quotation verified and buyer approved are recorded on each offer, not here. Brand authorisation is listed only after evidence is recorded.</Typography>
              {next && (
                <Stack direction="row" spacing={1} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
                  <TextField size="small" label="What did you check? (required)" value={note} onChange={(e) => setNote(e.target.value)} sx={{ minWidth: 260 }} inputProps={{ 'data-testid': 'stage-note' }} />
                  <TextField size="small" label="Evidence link (optional)" value={url} onChange={(e) => setUrl(e.target.value)} sx={{ minWidth: 220 }} />
                  <Button variant="contained" size="small" disabled={note.trim().length < 3 || move.isPending} onClick={() => move.mutate(next)} data-testid="stage-advance">Mark: {stageLabel(next)}</Button>
                </Stack>
              )}
              {move.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="stage-error">{sourcingError(move.error)}</Alert>}
            </Box>
            <Box>
              <Typography variant="subtitle2" fontWeight={900}>Offers from this supplier ({d.offers.length})</Typography>
              {d.offers.length === 0 ? <Typography variant="body2" color="text.secondary" data-testid="supplier-no-offers">This supplier has quoted nothing yet.</Typography>
                : d.offers.map((o) => <Typography key={o.id} variant="body2" data-testid="supplier-offer">{o.offered_product} — {o.currency} {o.unit_price} · {o.label} · {pretty(o.status)}</Typography>)}
            </Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  );
};

const Suppliers = () => {
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [type, setType] = useState('');
  const [stage, setStage] = useState('');
  const [fixtures, setFixtures] = useState(false);
  const [add, setAdd] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const res = useQuery({ queryKey: ['src', 'suppliers', q, type, stage, fixtures], queryFn: () => sourcingApi.suppliers({ q: q || undefined, type: type || undefined, stage: (stage || undefined) as SupplierStage | undefined, include_fixtures: fixtures || undefined, size: 50 }), retry: false });
  return (
    <Stack spacing={1.5} data-testid="src-suppliers">
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        <TextField size="small" label="Search name, brand or category" value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 240 }} inputProps={{ 'data-testid': 'sup-q' }} />
        <TextField select size="small" label="Type" value={type} onChange={(e) => setType(e.target.value)} sx={{ minWidth: 200 }}><MenuItem value="">Any</MenuItem>{TYPES.map((t) => <MenuItem key={t} value={t}>{pretty(t)}</MenuItem>)}</TextField>
        <TextField select size="small" label="Verification" value={stage} onChange={(e) => setStage(e.target.value)} sx={{ minWidth: 220 }}><MenuItem value="">Any</MenuItem>{STAGES.map((t) => <MenuItem key={t} value={t}>{stageLabel(t)}</MenuItem>)}</TextField>
        <FormControlLabel control={<Switch size="small" checked={fixtures} onChange={(e) => setFixtures(e.target.checked)} />} label={<Typography variant="caption">Show demo and test suppliers</Typography>} />
        <Button variant="outlined" onClick={() => setAdd(true)} data-testid="sup-add">Add a supplier</Button>
      </Stack>
      {res.isLoading && <Skeleton variant="rounded" height={120} />}
      {res.isError && <Alert severity="warning">{sourcingError(res.error)}</Alert>}
      {res.data && (res.data.items.length === 0
        ? <Alert severity="info" data-testid="sup-empty">No supplier matches. Run research (it lists the brand's own distributors, unverified), or add one when you receive a quotation.</Alert>
        : (
          <Paper variant="outlined"><Table size="small"><TableHead><TableRow><TableCell>Supplier</TableCell><TableCell>Type</TableCell><TableCell>Location</TableCell><TableCell>Brands</TableCell><TableCell>Verification</TableCell><TableCell align="right">Offers</TableCell></TableRow></TableHead>
            <TableBody>{res.data.items.map((s) => (
              <TableRow key={s.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(s.id)} data-testid="sup-row"><TableCell><b>{s.name}</b> <ProvenanceChip provenance={s.provenance} /><br /><Typography variant="caption" color="text.secondary">{s.website ?? 'no website recorded'}</Typography></TableCell>
                <TableCell>{pretty(s.type)}</TableCell><TableCell>{[s.region, s.country].filter(Boolean).join(', ') || '—'}</TableCell>
                <TableCell>{s.brands.join(', ') || '—'}{s.authorised_brands.length > 0 && <Typography variant="caption" display="block" color="success.main">Authorisation evidence: {s.authorised_brands.join(', ')}</Typography>}</TableCell>
                <TableCell><StageChip stage={s.verification_status} /></TableCell><TableCell align="right">{s.quote_count ?? 0}</TableCell></TableRow>))}</TableBody></Table></Paper>
        ))}
      {open && <SupplierDetailDialog id={open} onClose={() => setOpen(null)} onChanged={() => void qc.invalidateQueries({ queryKey: ['src', 'suppliers'] })} />}
      {add && <AddSupplier onClose={() => setAdd(false)} onSaved={() => void qc.invalidateQueries({ queryKey: ['src', 'suppliers'] })} />}
    </Stack>
  );
};

export default Suppliers;
