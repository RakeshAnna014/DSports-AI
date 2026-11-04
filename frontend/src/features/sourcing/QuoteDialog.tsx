import { useState } from 'react';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { sourcingApi, sourcingError } from '@/api/sourcing';

const num = (s: string) => (s.trim() === '' ? undefined : Number(s));

/** A buyer records what a supplier actually quoted. The kind says where the figure came from; VERIFIED is never chosen here (a separate action, after checking). */
const QuoteDialog = ({ oppId, onClose, onSaved, includeFixtures = false }: { oppId: string; onClose: () => void; onSaved: () => void; includeFixtures?: boolean }) => {
  const suppliers = useQuery({ queryKey: ['src', 'suppliers', 'all', includeFixtures], queryFn: () => sourcingApi.suppliers({ size: 100, include_fixtures: includeFixtures || undefined }) });
  const [f, setF] = useState({ supplier_id: '', offered_product: '', model: '', currency: 'INR', unit_price: '', moq: '', shipping_cost: '', lead_time_days: '', warranty: '', return_terms: '',
    certifications: '', reference: '', quoted_on: '', valid_until: '', source_kind: 'QUOTE_ENTERED', equivalence: 'IDENTICAL', equivalence_note: '', breaks: '' });
  const set = (k: keyof typeof f, v: string) => setF((x) => ({ ...x, [k]: v }));
  const parseBreaks = (): { min_quantity: number; unit_price: number }[] => f.breaks.split(',').map((p) => p.trim()).filter(Boolean).map((p) => {
    const [q, u] = p.split(':').map((x) => Number(x.trim()));
    return { min_quantity: q, unit_price: u };
  });
  const breaksBad = parseBreaks().some((b) => !Number.isFinite(b.min_quantity) || !Number.isFinite(b.unit_price) || b.min_quantity <= 0 || b.unit_price <= 0);
  const save = useMutation({
    mutationFn: () => sourcingApi.addQuote(oppId, {
      supplier_id: f.supplier_id, offered_product: f.offered_product, model: f.model || undefined, currency: f.currency.toUpperCase(), unit_price: num(f.unit_price), moq: num(f.moq),
      price_breaks: parseBreaks(), shipping_cost: num(f.shipping_cost), lead_time_days: num(f.lead_time_days), warranty: f.warranty || undefined, return_terms: f.return_terms || undefined,
      certifications: f.certifications || undefined, reference: f.reference || undefined, quoted_on: f.quoted_on || undefined, valid_until: f.valid_until || undefined,
      source_kind: f.source_kind, equivalence: f.equivalence, equivalence_note: f.equivalence_note || undefined,
    }),
    onSuccess: () => { onSaved(); onClose(); },
  });
  const ready = f.supplier_id && f.offered_product.length > 1 && f.currency.length === 3 && Number(f.unit_price) > 0 && !breaksBad && (f.equivalence === 'IDENTICAL' || f.equivalence_note.trim());
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth data-testid="quote-dialog">
      <DialogTitle>Record a supplier offer</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Alert severity="info" icon={false}>Enter figures exactly as the supplier gave them. Nothing is estimated for you; leave a field empty if the supplier did not state it.</Alert>
          <TextField select size="small" label="Supplier" value={f.supplier_id} onChange={(e) => set('supplier_id', e.target.value)} SelectProps={{ SelectDisplayProps: { 'data-testid': 'q-supplier' } as never }}>
            {(suppliers.data?.items ?? []).map((s) => <MenuItem key={s.id} value={s.id}>{s.name}{s.origin === 'DEMO' ? ' (DEMO)' : ''}</MenuItem>)}
          </TextField>
          {suppliers.data?.items.length === 0 && <Typography variant="caption" color="warning.main">No supplier exists yet: add one in the Suppliers tab first.</Typography>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" label="Offered product" value={f.offered_product} onChange={(e) => set('offered_product', e.target.value)} fullWidth inputProps={{ 'data-testid': 'q-product' }} />
            <TextField size="small" label="Model" value={f.model} onChange={(e) => set('model', e.target.value)} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField select size="small" label="This offer is" value={f.equivalence} onChange={(e) => set('equivalence', e.target.value)} sx={{ minWidth: 220 }}>
              <MenuItem value="IDENTICAL">The identical product</MenuItem><MenuItem value="EQUIVALENT_ALTERNATIVE">An equivalent alternative</MenuItem>
            </TextField>
            {f.equivalence !== 'IDENTICAL' && <TextField size="small" label="How it differs (required)" value={f.equivalence_note} onChange={(e) => set('equivalence_note', e.target.value)} fullWidth />}
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" label="Currency" value={f.currency} onChange={(e) => set('currency', e.target.value)} sx={{ width: 100 }} inputProps={{ maxLength: 3, 'data-testid': 'q-currency' }} />
            <TextField size="small" type="number" label="Unit price" value={f.unit_price} onChange={(e) => set('unit_price', e.target.value)} inputProps={{ 'data-testid': 'q-price', min: 0, step: '0.01' }} />
            <TextField size="small" type="number" label="MOQ" value={f.moq} onChange={(e) => set('moq', e.target.value)} inputProps={{ min: 1 }} />
            <TextField size="small" type="number" label="Shipping (quote currency)" value={f.shipping_cost} onChange={(e) => set('shipping_cost', e.target.value)} inputProps={{ min: 0 }} />
            <TextField size="small" type="number" label="Lead time (days)" value={f.lead_time_days} onChange={(e) => set('lead_time_days', e.target.value)} inputProps={{ min: 0 }} />
          </Stack>
          <TextField size="small" label="Price breaks, e.g. 500:1950, 1000:1850" value={f.breaks} onChange={(e) => set('breaks', e.target.value)} error={breaksBad} helperText={breaksBad ? 'Use quantity:price pairs separated by commas' : 'Minimum quantity : unit price'} />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" label="Warranty" value={f.warranty} onChange={(e) => set('warranty', e.target.value)} fullWidth />
            <TextField size="small" label="Return terms" value={f.return_terms} onChange={(e) => set('return_terms', e.target.value)} fullWidth />
            <TextField size="small" label="Certifications" value={f.certifications} onChange={(e) => set('certifications', e.target.value)} fullWidth />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" type="date" label="Quoted on" InputLabelProps={{ shrink: true }} value={f.quoted_on} onChange={(e) => set('quoted_on', e.target.value)} />
            <TextField size="small" type="date" label="Valid until" InputLabelProps={{ shrink: true }} value={f.valid_until} onChange={(e) => set('valid_until', e.target.value)} />
            <TextField size="small" label="Supplier's quotation reference" value={f.reference} onChange={(e) => set('reference', e.target.value)} fullWidth />
            <TextField select size="small" label="Where it came from" value={f.source_kind} onChange={(e) => set('source_kind', e.target.value)} sx={{ minWidth: 230 }}>
              <MenuItem value="QUOTE_ENTERED">A supplier quotation</MenuItem><MenuItem value="PUBLIC_LISTING">A public listing</MenuItem><MenuItem value="AI_ESTIMATE">An AI estimate (unconfirmed)</MenuItem>
            </TextField>
          </Stack>
          {save.isError && <Alert severity="warning" data-testid="q-error">{sourcingError(save.error)}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!ready || save.isPending} onClick={() => save.mutate()} data-testid="q-save">Save offer</Button>
      </DialogActions>
    </Dialog>
  );
};

export default QuoteDialog;
