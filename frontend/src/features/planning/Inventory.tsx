import { useState } from 'react';
import { Alert, Box, Button, Chip, FormControl, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TablePagination, TableRow, Tooltip, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isApprover, planningApi, planningError } from '@/api/planning';
import FilterBar from './FilterBar';
import { CoverageBars, SectionCard } from '@/components/analytics';
import { Metric, ScopeBanner, day, n, pretty, usePlanning } from './parts';

const RISK: Record<string, 'error' | 'warning' | 'success' | 'default'> = { HIGH: 'error', MEDIUM: 'warning', LOW: 'success' };

/** Store-level stock requirement, replenishment and transfers from the Inventory AI's own rules, driven by the plan's forecast. Everything is a DRAFT: nothing is ordered or moved. */
const Inventory = () => {
  const { planId, filters, roles, source } = usePlanning();
  const qc = useQueryClient();
  const [risk, setRisk] = useState('');
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const q = useQuery({ queryKey: ['pln', 'alloc', planId, filters, risk, action, page, size], queryFn: () => planningApi.allocations(planId as string, { ...filters, risk: risk || undefined, action: action || undefined, only_actions: !risk && !action }, page, size), enabled: !!planId, retry: false });
  const gen = useMutation({ mutationFn: () => planningApi.generateAllocation(planId as string, filters), onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pln'] }); } });
  const decideA = useMutation({ mutationFn: (v: { id: string; approve: boolean }) => planningApi.decideAllocation(v.id, v.approve), onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pln', 'alloc'] }); } });
  const decideT = useMutation({ mutationFn: (v: { id: string; approve: boolean }) => planningApi.decideTransfer(v.id, v.approve), onSuccess: () => { void qc.invalidateQueries({ queryKey: ['pln', 'alloc'] }); } });
  if (!planId) return <Alert severity="info">Choose a plan: the stock plan follows its forecast.</Alert>;
  const s = q.data?.summary;
  const approver = isApprover(roles);
  return (
    <Stack spacing={1.25} data-testid="inventory">
      <ScopeBanner source={source} />
      <Alert severity="info" icon={false}>Recommendations use the Inventory AI's rules (lead time, 95% service level safety stock, supplier MOQ and case pack applied once per product across stores). They are drafts: approving one records a decision and orders or moves nothing. Stock and supplier terms are demonstration reference data.</Alert>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
        <FilterBar show={['sport', 'category', 'region', 'store', 'q']} />
        <FormControl size="small" sx={{ minWidth: 120 }}><InputLabel id="ir-l">Risk</InputLabel><Select labelId="ir-l" label="Risk" value={risk} onChange={(e) => { setRisk(e.target.value); setPage(0); }} SelectDisplayProps={{ 'data-testid': 'inv-risk' } as React.HTMLAttributes<HTMLDivElement>}>
          <MenuItem value="">Any</MenuItem>{['HIGH', 'MEDIUM', 'LOW'].map((r) => <MenuItem key={r} value={r}>{pretty(r)}</MenuItem>)}</Select></FormControl>
        <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="ia-l">Action</InputLabel><Select labelId="ia-l" label="Action" value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }}>
          <MenuItem value="">Needs action</MenuItem>{['ORDER', 'EXPEDITE_INCOMING', 'TRANSFER_IN', 'REVIEW', 'NONE'].map((r) => <MenuItem key={r} value={r}>{pretty(r)}</MenuItem>)}</Select></FormControl>
        <Button variant="contained" size="small" onClick={() => gen.mutate()} disabled={gen.isPending} data-testid="inv-generate">{gen.isPending ? 'Calculating…' : s?.recs ? 'Recalculate' : 'Generate recommendations'}</Button>
      </Stack>
      {gen.isError && <Alert severity="warning" data-testid="inv-error">{planningError(gen.error)}</Alert>}
      {gen.data && <Alert severity="success" data-testid="inv-generated">{gen.data.recommendations} position(s) assessed, {gen.data.transfers} transfer(s) proposed. {gen.data.notice}</Alert>}
      {s && s.recs > 0 && (
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          <Metric id="inv-high" title="High-risk positions" value={n(s.high_risk)} tone={s.high_risk > 0 ? 'warn' : 'default'} hint="may run out before a new order lands" />
          <Metric id="inv-order" title="Products to order" value={n(s.products_to_order)} hint={`${n(s.units_to_order)} units in total`} />
          <Metric id="inv-surplus" title="Positions with surplus" value={n(s.with_surplus)} hint="over the 90-day forecast" />
          <Metric id="inv-transfers" title="Transfers proposed" value={n(s.transfers)} hint={`${n(s.transfer_units)} units`} />
        </Stack>
      )}
      {q.isLoading && <Skeleton variant="rounded" height={160} />}
      {q.isError && <Alert severity="warning">{planningError(q.error)}</Alert>}
      {q.data && s && s.recs === 0 && <Alert severity="info" data-testid="inv-none">No recommendations yet for this plan. Generate them to see store-level requirements.</Alert>}
      {q.data && q.data.items.some((r) => r.coverage_days !== null) && (
        <SectionCard title="Days of cover, lowest first" testId="inv-coverage" provenance={source === 'POC_SYNTHETIC' ? ['DEMO', 'FORECAST'] : ['LIVE', 'FORECAST']}
          subtitle="Sellable stock (on hand minus reserved) plus incoming, against the forecast. Markers show the shortage and excess thresholds. These are drafts: nothing is ordered or moved.">
          <CoverageBars low={14} high={90} items={[...q.data.items].filter((r) => r.coverage_days !== null).sort((a, b) => (a.coverage_days ?? 0) - (b.coverage_days ?? 0)).slice(0, 10).map((r) => ({ label: `${r.product_name} · ${r.store_name}`, days: r.coverage_days, sub: `sellable ${n(r.on_hand)} + incoming ${n(r.incoming)} · ${pretty(r.action)}` }))} />
        </SectionCard>
      )}      {q.data && q.data.items.length > 0 && (
        <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>Product</TableCell><TableCell>Store</TableCell><TableCell>Risk</TableCell><TableCell align="right">Sellable</TableCell><TableCell align="right">Incoming</TableCell><TableCell align="right">Required</TableCell><TableCell align="right">Cover (days)</TableCell>
              <TableCell>Action</TableCell><TableCell align="right">Quantity</TableCell><TableCell>Order by</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>{q.data.items.map((r) => (
              <TableRow key={r.id} hover data-testid="inv-row">
                <TableCell><Typography variant="body2" fontWeight={700}>{r.product_name}</Typography><Typography variant="caption" color="text.secondary">{r.sku}</Typography></TableCell>
                <TableCell>{r.store_name}<Typography variant="caption" color="text.secondary" display="block">{r.region}</Typography></TableCell>
                <TableCell>{r.risk_level ? <Chip size="small" color={RISK[r.risk_level]} label={pretty(r.risk_level)} /> : '—'}</TableCell>
                <TableCell align="right">{n(r.on_hand)}{r.reserved ? <Typography variant="caption" color="text.secondary" display="block">{r.reserved} reserved</Typography> : null}</TableCell><TableCell align="right">{n(r.incoming)}</TableCell><TableCell align="right">{n(r.required_stock)}</TableCell><TableCell align="right">{n(r.coverage_days)}</TableCell>
                <TableCell><Tooltip title={r.reason}><span>{pretty(r.action)}</span></Tooltip></TableCell><TableCell align="right" data-testid="inv-qty">{n(r.recommended_qty)}</TableCell><TableCell>{day(r.order_by)}</TableCell>
                <TableCell><Chip size="small" variant="outlined" label={pretty(r.status)} /></TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.status === 'DRAFT' && approver && r.action !== 'NONE' && (<><Button size="small" onClick={() => decideA.mutate({ id: r.id, approve: true })} data-testid="inv-approve">Approve</Button><Button size="small" color="error" onClick={() => decideA.mutate({ id: r.id, approve: false })}>Reject</Button></>)}</TableCell>
              </TableRow>))}</TableBody>
          </Table>
          <TablePagination component="div" count={q.data.total} page={page} rowsPerPage={size} rowsPerPageOptions={[25, 50, 100]} onPageChange={(_, p) => setPage(p)} onRowsPerPageChange={(e) => { setSize(Number(e.target.value)); setPage(0); }} />
        </Paper>
      )}
      {(decideA.isError || decideT.isError) && <Alert severity="warning">{planningError(decideA.error ?? decideT.error)}</Alert>}
      {(decideA.data || decideT.data) && <Alert severity="success" data-testid="inv-decided">{(decideA.data ?? decideT.data)?.notice}</Alert>}
      {q.data && q.data.transfers.length > 0 && (
        <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="inv-transfers-table">
          <Typography variant="subtitle2" fontWeight={900}>Store-to-store transfers (instead of buying more)</Typography>
          {q.data.transfers.map((t) => (
            <Box key={t.id} sx={{ py: 0.5 }} data-testid="transfer-row">
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="body2"><b>{t.qty} × {t.product_name}</b>: {t.from_name} → {t.to_name}, {t.lead_days} day(s), arrives {day(t.arrival_date)}</Typography><Chip size="small" variant="outlined" label={pretty(t.status)} />
                {t.status === 'DRAFT' && approver && (<><Button size="small" onClick={() => decideT.mutate({ id: t.id, approve: true })} data-testid="transfer-approve">Approve</Button><Button size="small" color="error" onClick={() => decideT.mutate({ id: t.id, approve: false })}>Reject</Button></>)}
              </Stack>
              <Typography variant="caption" color="text.secondary">{t.reason}</Typography>
            </Box>))}
        </Paper>
      )}
    </Stack>
  );
};

export default Inventory;
