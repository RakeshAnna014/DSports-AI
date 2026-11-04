import { OfferChart } from './visuals';
import { Fragment, useState } from 'react';
import { Alert, Box, Button, Chip, Collapse, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { sourcingApi, sourcingError, type CalcResult, type Compare } from '@/api/sourcing';
import { KindChip, inr, pretty } from './parts';

const NumField = ({ id, label, value, onChange, width = 130, step = '1' }: { id: string; label: string; value: string; onChange: (v: string) => void; width?: number; step?: string }) => (
  <TextField size="small" type="number" label={label} value={value} onChange={(e) => onChange(e.target.value)} sx={{ width }} inputProps={{ 'data-testid': id, min: 0, step }} />
);
const n = (s: string) => (s.trim() === '' ? undefined : Number(s));
const v = (c: CalcResult | undefined, k: string) => (c?.values?.[k] ?? null) as string | null;

/** Side-by-side supplier comparison and the landed-cost calculator. All arithmetic is done by the service (deterministic, tested); this page only sends inputs and shows results. */
const ComparePanel = ({ oppId, initialPrice, quoteCount }: { oppId: string; initialPrice?: number | null; quoteCount: number }) => {
  const qc = useQueryClient();
  const [qty, setQty] = useState('500');
  const [price, setPrice] = useState(initialPrice ? String(initialPrice) : '');
  const [a, setA] = useState({ duty_pct: '', handling_per_unit: '', packaging_per_unit: '', defect_pct: '', sales_tax_pct: '', target_margin_pct: '', fx_rate: '', fx_source: '', freight_total: '' });
  const [taxIncl, setTaxIncl] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const assumptions = () => ({
    duty_pct: n(a.duty_pct), handling_per_unit: n(a.handling_per_unit), packaging_per_unit: n(a.packaging_per_unit), defect_pct: n(a.defect_pct), sales_tax_pct: n(a.sales_tax_pct),
    target_margin_pct: n(a.target_margin_pct), fx_rate: n(a.fx_rate), fx_source: a.fx_source || undefined, freight_total: n(a.freight_total), price_includes_tax: taxIncl || undefined,
  });
  const cmp = useMutation({ mutationFn: () => sourcingApi.compare(oppId, { quantity: Number(qty), selling_price: n(price), assumptions: assumptions() }) });
  const save = useMutation({
    mutationFn: (quoteId: string) => sourcingApi.calculate(oppId, { quote_id: quoteId, quantity: Number(qty), selling_price: n(price), assumptions: assumptions(), save: true }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['src', 'opp', oppId] }),
  });
  const data: Compare | undefined = cmp.data;
  const set = (k: keyof typeof a) => (val: string) => setA((x) => ({ ...x, [k]: val }));
  return (
    <Stack spacing={1.5} data-testid="compare-panel">
      <Typography variant="subtitle2" fontWeight={900}>Compare offers and calculate landed cost</Typography>
      {quoteCount === 0 && <Alert severity="info">Record at least one supplier offer to compare.</Alert>}
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        <NumField id="cmp-qty" label="Order quantity" value={qty} onChange={setQty} />
        <NumField id="cmp-price" label="Selling price (per unit)" value={price} onChange={setPrice} width={170} step="0.01" />
        <Chip clickable color={taxIncl ? 'primary' : 'default'} label={taxIncl ? 'Price includes tax' : 'Price excludes tax'} onClick={() => setTaxIncl((x) => !x)} />
        <NumField id="cmp-tax" label="Sales tax %" value={a.sales_tax_pct} onChange={set('sales_tax_pct')} />
        <NumField id="cmp-duty" label="Duty %" value={a.duty_pct} onChange={set('duty_pct')} />
        <NumField id="cmp-handling" label="Handling / unit" value={a.handling_per_unit} onChange={set('handling_per_unit')} />
        <NumField id="cmp-packaging" label="Packaging / unit" value={a.packaging_per_unit} onChange={set('packaging_per_unit')} />
        <NumField id="cmp-defect" label="Defect / loss %" value={a.defect_pct} onChange={set('defect_pct')} />
        <NumField id="cmp-target" label="Target margin %" value={a.target_margin_pct} onChange={set('target_margin_pct')} />
        <NumField id="cmp-fx" label="FX rate override" value={a.fx_rate} onChange={set('fx_rate')} step="0.0001" />
        <TextField size="small" label="FX rate source" value={a.fx_source} onChange={(e) => set('fx_source')(e.target.value)} sx={{ width: 170 }} />
        <NumField id="cmp-freight" label="Freight override" value={a.freight_total} onChange={set('freight_total')} width={150} />
        <Button variant="contained" onClick={() => cmp.mutate()} disabled={cmp.isPending || !Number(qty) || quoteCount === 0} data-testid="cmp-run">Compare</Button>
      </Stack>
      {cmp.isError && <Alert severity="warning" data-testid="cmp-error">{sourcingError(cmp.error)}</Alert>}
      {save.isError && <Alert severity="warning">{sourcingError(save.error)}</Alert>}
      {save.isSuccess && <Alert severity="success" data-testid="calc-saved">Calculation saved as a snapshot with these assumptions.</Alert>}
      {data && (
        <Box data-testid="cmp-result">
          {data.summary.explanation && <Alert severity="info" icon={false} sx={{ mb: 1 }} data-testid="cmp-explanation">{data.summary.explanation}</Alert>}
          <OfferChart offers={data.offers} />
          <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead><TableRow><TableCell>Supplier / offer</TableCell><TableCell>Source</TableCell><TableCell align="right">Unit price</TableCell><TableCell align="right">MOQ</TableCell><TableCell align="right">Lead time</TableCell>
                <TableCell align="right">Landed cost / unit</TableCell><TableCell align="right">Margin</TableCell><TableCell>Rank</TableCell><TableCell /></TableRow></TableHead>
              <TableBody>
                {data.offers.map((o) => {
                  const c = o.calculation; const q = o.quote;
                  return (
                    <Fragment key={q.id}>
                      <TableRow data-testid="cmp-row" data-supplier={q.supplier_name} sx={{ opacity: q.status === 'ACTIVE' ? 1 : 0.6 }}>
                        <TableCell><b>{q.supplier_name}</b><br /><Typography variant="caption" color="text.secondary">{q.offered_product}{q.equivalence !== 'IDENTICAL' ? ' · ALTERNATIVE' : ''}</Typography></TableCell>
                        <TableCell><KindChip kind={q.source_kind} label={q.label} />{q.status !== 'ACTIVE' && <Chip size="small" color="error" label={pretty(q.status)} sx={{ ml: 0.5 }} />}</TableCell>
                        <TableCell align="right">{q.currency} {q.unit_price}</TableCell>
                        <TableCell align="right">{q.moq ?? '—'}</TableCell>
                        <TableCell align="right">{q.lead_time_days == null ? 'unknown' : `${q.lead_time_days} d`}</TableCell>
                        <TableCell align="right" data-testid="cmp-landed">{c.status === 'OK' ? inr(v(c, 'landed_cost_per_unit')) : <Chip size="small" color={c.status === 'INVALID' ? 'error' : 'warning'} label={c.status === 'INVALID' ? c.issues[0] : `Incomplete: ${c.missing.join(', ')}`} />}</TableCell>
                        <TableCell align="right">{v(c, 'gross_margin_pct') ? `${v(c, 'gross_margin_pct')}%` : '—'}</TableCell>
                        <TableCell>{o.rank_by_landed_cost ? `#${o.rank_by_landed_cost}` : '—'}</TableCell>
                        <TableCell><Button size="small" onClick={() => setOpen(open === q.id ? null : q.id)} data-testid="cmp-detail">{open === q.id ? 'Hide' : 'Breakdown'}</Button></TableCell>
                      </TableRow>
                      <TableRow><TableCell colSpan={9} sx={{ py: 0, border: open === q.id ? undefined : 0 }}>
                        <Collapse in={open === q.id} unmountOnExit>
                          <Box sx={{ p: 1.5 }} data-testid="cmp-breakdown">
                            {c.status === 'OK' && (
                              <>
                                <Table size="small"><TableBody>
                                  {c.cost_drivers.map((d) => <TableRow key={d.component}><TableCell>{d.component}</TableCell><TableCell align="right">{inr(d.amount)}</TableCell><TableCell align="right">{d.share_pct}%</TableCell></TableRow>)}
                                  <TableRow><TableCell><b>Total procurement cost</b></TableCell><TableCell align="right"><b>{inr(v(c, 'total_procurement_cost'))}</b></TableCell><TableCell /></TableRow>
                                  <TableRow><TableCell>Sellable units</TableCell><TableCell align="right">{v(c, 'sellable_units')}</TableCell><TableCell /></TableRow>
                                  <TableRow><TableCell><b>Landed cost per sellable unit</b></TableCell><TableCell align="right"><b>{inr(v(c, 'landed_cost_per_unit'))}</b></TableCell><TableCell /></TableRow>
                                  {v(c, 'break_even_selling_price') && <TableRow><TableCell>Break-even selling price</TableCell><TableCell align="right" data-testid="calc-breakeven">{inr(v(c, 'break_even_selling_price'))}</TableCell><TableCell /></TableRow>}
                                  {v(c, 'target_margin_selling_price') && <TableRow><TableCell>Selling price for {v(c, 'target_margin_pct')}% margin</TableCell><TableCell align="right" data-testid="calc-target">{inr(v(c, 'target_margin_selling_price'))}</TableCell><TableCell /></TableRow>}
                                  {v(c, 'gross_margin_pct') && <TableRow><TableCell>Gross profit per unit / margin / markup</TableCell><TableCell align="right">{inr(v(c, 'gross_profit_per_unit'))}</TableCell><TableCell align="right">{v(c, 'gross_margin_pct')}% / {v(c, 'markup_pct')}%</TableCell></TableRow>}
                                </TableBody></Table>
                                <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}><b>Assumptions:</b> {c.assumptions.join(' ')}</Typography>
                                {c.issues.map((i) => <Alert key={i} severity="warning" sx={{ mt: 0.5 }}>{i}</Alert>)}
                                {c.missing_for_margin.length > 0 && <Alert severity="info" sx={{ mt: 0.5 }}>Enter a selling price to see the margin.</Alert>}
                                {[...o.warnings].map((w) => <Typography key={w} variant="caption" color="warning.main" display="block">• {w}</Typography>)}
                                <Button size="small" variant="outlined" sx={{ mt: 1 }} onClick={() => save.mutate(q.id)} disabled={save.isPending} data-testid="calc-save">Save this calculation</Button>
                              </>
                            )}
                            {c.status !== 'OK' && <Alert severity={c.status === 'INVALID' ? 'error' : 'warning'}>{[...c.issues, ...c.missing.map((m) => `Missing: ${m}`)].join('; ')}</Alert>}
                          </Box>
                        </Collapse>
                      </TableCell></TableRow>
                    </Fragment>
                  );
                })}
              </TableBody>
            </Table>
          </Paper>
          {data.summary.excluded && <Typography variant="caption" color="text.secondary">Not ranked: {data.summary.excluded.map((e) => `${e.supplier} (${e.why.join(', ')})`).join('; ')}</Typography>}
        </Box>
      )}
    </Stack>
  );
};

export default ComparePanel;
