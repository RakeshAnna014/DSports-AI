import { useState } from 'react';
import { Alert, Button, Chip, Paper, Stack, Table, TableBody, TableCell, TableRow, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { sourcingApi, sourcingError } from '@/api/sourcing';
import { inr } from './parts';
import { CostBreakdown } from './visuals';

const FIELDS: [string, string, string][] = [
  ['quantity', 'Order quantity', 'units'], ['unit_price', 'Supplier unit price', 'in the quote currency'], ['currency', 'Quote currency', '3 letters'], ['fx_rate', 'Exchange rate to INR', 'with its source below'],
  ['moq', 'Minimum order quantity', 'units'], ['freight_total', 'Freight (INR, total)', ''], ['duty_pct', 'Customs duty %', 'on cost + freight'], ['non_recoverable_tax_pct', 'Non-recoverable tax %', 'recoverable tax is not a cost'],
  ['handling_per_unit', 'Handling per unit (INR)', ''], ['packaging_per_unit', 'Packaging per unit (INR)', ''], ['other_fixed_costs', 'Other fixed costs (INR)', ''], ['defect_pct', 'Defect / loss allowance %', 'spread over sellable units'],
  ['selling_price', 'Selling price per unit', ''], ['sales_tax_pct', 'Sales tax %', 'used only if the price includes tax'], ['target_margin_pct', 'Target gross margin %', ''],
];

/** A quick what-if with the same deterministic calculator. Nothing is saved; use an opportunity page to keep a snapshot. */
const CalculatorTab = () => {
  const [v, setV] = useState<Record<string, string>>({ quantity: '500', currency: 'INR' });
  const [fxSource, setFxSource] = useState('');
  const [tax, setTax] = useState(false);
  const run = useMutation({
    mutationFn: () => sourcingApi.calculator({ ...Object.fromEntries(Object.entries(v).filter(([, x]) => x.trim() !== '').map(([k, x]) => [k, k === 'currency' ? x.toUpperCase() : (Number.isFinite(Number(x)) ? Number(x) : x)])),
      price_includes_tax: tax, fx_source: fxSource || undefined }),
  });
  const r = run.data;
  const val = (k: string) => (r?.values?.[k] ?? null) as string | null;
  return (
    <Stack spacing={1.5} data-testid="src-calculator">
      <Alert severity="info" icon={false}>Margin = gross profit ÷ net revenue (tax excluded). Markup = gross profit ÷ landed cost. They are different numbers and both are shown. Missing inputs make the result incomplete instead of being guessed.</Alert>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        {FIELDS.map(([k, label, hint]) => <TextField key={k} size="small" label={label} helperText={hint} value={v[k] ?? ''} onChange={(e) => setV((x) => ({ ...x, [k]: e.target.value }))} sx={{ width: 190 }} inputProps={{ 'data-testid': `calc-${k}` }} />)}
        <TextField size="small" label="Exchange-rate source" value={fxSource} onChange={(e) => setFxSource(e.target.value)} sx={{ width: 220 }} />
        <Chip clickable color={tax ? 'primary' : 'default'} label={tax ? 'Selling price includes tax' : 'Selling price excludes tax'} onClick={() => setTax((x) => !x)} sx={{ alignSelf: 'center' }} />
        <Button variant="contained" onClick={() => run.mutate()} disabled={run.isPending} data-testid="calc-run" sx={{ alignSelf: 'center' }}>Calculate</Button>
      </Stack>
      {run.isError && <Alert severity="warning" data-testid="calc-error">{sourcingError(run.error)}</Alert>}
      {r && (
        <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="calc-result">
          {r.status !== 'OK' && <Alert severity={r.status === 'INVALID' ? 'error' : 'warning'} data-testid="calc-status">{r.status === 'INVALID' ? r.issues.join('; ') : `Incomplete. Missing: ${r.missing.join(', ')}`}</Alert>}
          {r.status === 'OK' && (
            <>
              <CostBreakdown drivers={r.cost_drivers} />
              <Table size="small"><TableBody>
                {r.cost_drivers.map((d) => <TableRow key={d.component}><TableCell>{d.component}</TableCell><TableCell align="right">{inr(d.amount)}</TableCell><TableCell align="right">{d.share_pct}%</TableCell></TableRow>)}
                <TableRow><TableCell><b>Total procurement cost</b></TableCell><TableCell align="right" data-testid="calc-total">{inr(val('total_procurement_cost'))}</TableCell><TableCell /></TableRow>
                <TableRow><TableCell><b>Landed cost per sellable unit</b></TableCell><TableCell align="right" data-testid="calc-landed">{inr(val('landed_cost_per_unit'))}</TableCell><TableCell>{val('sellable_units')} sellable units</TableCell></TableRow>
                <TableRow><TableCell>Break-even selling price</TableCell><TableCell align="right" data-testid="calc-be">{inr(val('break_even_selling_price'))}</TableCell><TableCell /></TableRow>
                {val('target_margin_selling_price') && <TableRow><TableCell>Selling price for the target margin</TableCell><TableCell align="right" data-testid="calc-target-price">{inr(val('target_margin_selling_price'))}</TableCell><TableCell /></TableRow>}
                {val('gross_margin_pct') && <TableRow><TableCell>Gross profit per unit</TableCell><TableCell align="right">{inr(val('gross_profit_per_unit'))}</TableCell><TableCell data-testid="calc-margin">margin {val('gross_margin_pct')}% · markup {val('markup_pct')}%</TableCell></TableRow>}
              </TableBody></Table>
              <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}><b>Assumptions:</b> {r.assumptions.join(' ')}</Typography>
              {r.issues.map((i) => <Alert key={i} severity="warning" sx={{ mt: 0.5 }}>{i}</Alert>)}
            </>
          )}
        </Paper>
      )}
    </Stack>
  );
};

export default CalculatorTab;
