import { safeHref } from '@/utils/safeUrl';
import { useState } from 'react';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Link, MenuItem, Paper, Skeleton, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { sourcingApi, sourcingError, type OpportunityDetail as Detail, type OppStatus, type Provenance } from '@/api/sourcing';
import { KindChip, ProvenanceChip, RiskChip, StageChip, StatusChip, day, pretty, when } from './parts';
import { CostBreakdown, ProductWorkflow, ScoreBars } from './visuals';
import ComparePanel from './ComparePanel';
import ImageGallery from './ImageGallery';
import QuoteDialog from './QuoteDialog';

const REASON: OppStatus[] = ['REJECTED', 'ON_HOLD', 'NEEDS_MORE_EVIDENCE'];
const APPROVALS: OppStatus[] = ['APPROVED_OPPORTUNITY', 'APPROVED_FOR_ONBOARDING'];
const Section = ({ title, children, id }: { title: string; children: React.ReactNode; id: string }) => (
  <Paper variant="outlined" sx={{ p: 1.5 }} data-testid={id}><Typography variant="subtitle2" fontWeight={900} sx={{ mb: 0.75 }}>{title}</Typography>{children}</Paper>
);

const StatusActions = ({ d, onDone }: { d: Detail; onDone: () => void }) => {
  const [target, setTarget] = useState<OppStatus | null>(null);
  const [reason, setReason] = useState('');
  const change = useMutation({
    mutationFn: () => sourcingApi.changeStatus(d.opportunity.id, target as string, reason.trim() || undefined, d.opportunity.version),
    onSuccess: () => { setTarget(null); setReason(''); onDone(); },
  });
  const needReason = !!target && REASON.includes(target);
  return (
    <Box data-testid="opp-actions">
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
        {d.allowed_next.length === 0 && <Typography variant="body2" color="text.secondary">No status change is available to you from {pretty(d.opportunity.status)}.</Typography>}
        {d.allowed_next.map((s) => <Button key={s} size="small" variant={APPROVALS.includes(s) ? 'contained' : 'outlined'} color={s === 'REJECTED' ? 'error' : APPROVALS.includes(s) ? 'success' : 'primary'}
          onClick={() => setTarget(s)} data-testid={`to-${s}`}>{s === 'APPROVED_OPPORTUNITY' ? 'Approve as a business opportunity' : s === 'APPROVED_FOR_ONBOARDING' ? 'Approve for sourcing preparation' : pretty(s)}</Button>)}
      </Stack>
      <Dialog open={!!target} onClose={() => setTarget(null)} data-testid="status-dialog">
        <DialogTitle>Change status to {pretty(target)}</DialogTitle>
        <DialogContent>
          {target === 'APPROVED_OPPORTUNITY' && <Alert severity="warning" sx={{ mb: 1 }}>This records that the product is worth pursuing as a business opportunity. It is not approval to onboard, buy, publish or price it, and it does not make the record catalogue-ready.</Alert>}
          {target === 'APPROVED_FOR_ONBOARDING' && <Alert severity="warning" sx={{ mb: 1 }}>Approval for sourcing preparation is a recommendation to add this product through the catalogue process. It does not authorise a purchase, publish a product or change a price. Catalogue readiness is checked separately.</Alert>}
          <TextField fullWidth multiline minRows={2} size="small" sx={{ mt: 0.5 }} label={needReason ? 'Reason (required)' : 'Reason (optional)'} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ 'data-testid': 'status-reason' }} />
          {change.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="status-error">{sourcingError(change.error)}</Alert>}
        </DialogContent>
        <DialogActions><Button onClick={() => setTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={change.isPending || (needReason && !reason.trim())} onClick={() => change.mutate()} data-testid="status-confirm">Confirm</Button></DialogActions>
      </Dialog>
    </Box>
  );
};

const CLASSIFY: Provenance[] = ['BUYER_ENTERED', 'TEST_FIXTURE', 'DEMO_FIXTURE'];
const Classify = ({ id, current, onDone }: { id: string; current: Provenance; onDone: () => void }) => {
  const [open, setOpen] = useState(false);
  const [to, setTo] = useState<Provenance>('TEST_FIXTURE');
  const [reason, setReason] = useState('');
  const save = useMutation({ mutationFn: () => sourcingApi.classify(id, to, reason.trim()), onSuccess: () => { setOpen(false); onDone(); } });
  return (
    <>
      <Button size="small" onClick={() => setOpen(true)} data-testid="classify-open">Classify origin</Button>
      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="xs" data-testid="classify-dialog">
        <DialogTitle>Where did this record come from?</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1 }}>Currently: <b>{pretty(current)}</b>. A record cannot be re-labelled as found by a live source; that is only set by a research run.</Typography>
          <TextField select fullWidth size="small" label="Classify as" value={to} onChange={(e) => setTo(e.target.value as Provenance)} sx={{ mb: 1 }}>{CLASSIFY.map((p) => <MenuItem key={p} value={p}>{pretty(p)}</MenuItem>)}</TextField>
          <TextField fullWidth multiline minRows={2} size="small" label="Reason (required, audited)" value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ 'data-testid': 'classify-reason' }} />
          {save.isError && <Alert severity="warning" sx={{ mt: 1 }}>{sourcingError(save.error)}</Alert>}
        </DialogContent>
        <DialogActions><Button onClick={() => setOpen(false)}>Cancel</Button><Button variant="contained" disabled={reason.trim().length < 3 || save.isPending} onClick={() => save.mutate()} data-testid="classify-save">Save</Button></DialogActions>
      </Dialog>
    </>
  );
};

const FIN_LABEL: Record<string, string> = { QUOTED: 'Quoted (from a supplier quotation)', ESTIMATED: 'Estimated (from a public listing)', INCOMPLETE: 'Incomplete (inputs missing)', NONE: 'None yet (no quotation)' };

const OpportunityDetail = ({ id, onBack }: { id: string; onBack: () => void }) => {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['src', 'opp', id], queryFn: () => sourcingApi.opportunity(id), retry: false });
  const [tab, setTab] = useState('overview');
  const [quoteOpen, setQuoteOpen] = useState(false);
  const [note, setNote] = useState('');
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['src'] }); };
  const rescore = useMutation({ mutationFn: () => sourcingApi.rescore(id), onSuccess: refresh });
  const addNote = useMutation({ mutationFn: () => sourcingApi.note(id, note), onSuccess: () => { setNote(''); refresh(); } });
  const verify = useMutation({ mutationFn: (qid: string) => sourcingApi.verifyQuote(qid), onSuccess: refresh });
  const [proposalQuote, setProposalQuote] = useState('');
  const [proposalQty, setProposalQty] = useState('500');
  const [proposalPrice, setProposalPrice] = useState('');
  const proposal = useMutation({ mutationFn: () => sourcingApi.prepareProposal(id, { quote_id: proposalQuote, quantity: Number(proposalQty), selling_price: proposalPrice ? Number(proposalPrice) : undefined }), onSuccess: refresh });
  const check = useMutation({ mutationFn: () => sourcingApi.readiness(id, { quote_id: proposalQuote || undefined, quantity: Number(proposalQty) || undefined, selling_price: proposalPrice ? Number(proposalPrice) : undefined }) });
  if (q.isLoading) return <Skeleton variant="rounded" height={300} />;
  if (q.isError) return <Alert severity="warning" data-testid="detail-error">{sourcingError(q.error)}</Alert>;
  const d = q.data!;
  const o = d.opportunity;
  const s = d.score;
  const gap = o.record_kind === 'DEMAND_GAP';
  const readiness = check.data ?? d.readiness;
  const specRows = Object.entries(d.specifications ?? {});
  const calcs = d.calculations as (Detail['calculations'][number] & { quote_id?: string; basis?: Record<string, unknown> })[];
  return (
    <Stack spacing={1.5} data-testid="opp-detail" data-id={o.id}>
      <Button size="small" onClick={onBack} sx={{ alignSelf: 'flex-start' }} data-testid="detail-back">Back to the list</Button>
      <Paper variant="outlined" sx={{ p: 1.75 }}>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.5 }}>
          <Typography variant="h6" fontWeight={900} data-testid="opp-name">{o.name}</Typography>
          <StatusChip status={o.status} /><ProvenanceChip provenance={o.provenance} />
          {gap && <Chip size="small" color="info" variant="outlined" label="Customer demand gap: not a product" data-testid="gap-chip" />}
          {s?.supplier_risk && !gap && <RiskChip level={s.supplier_risk.level} />}
        </Stack>
        <Typography variant="caption" color="text.secondary" display="block">
          {[o.brand && `Brand ${o.brand}`, o.model && `Model ${o.model}`, o.mfr_sku && `SKU ${o.mfr_sku}`, o.gtin && `GTIN ${o.gtin}`, o.manufacturer && `Manufacturer ${o.manufacturer}`, o.sport, o.category].filter(Boolean).join(' · ') || 'Brand, model and identifiers are not known yet.'}
        </Typography>
        <Typography variant="caption" color="text.secondary" display="block">First seen {day(o.first_seen)} · last verified {day(o.last_verified)} · {d.provenance_label}</Typography>
        {d.is_fixture && <Alert severity="warning" sx={{ mt: 1 }} data-testid="demo-banner">{o.provenance === 'DEMO_FIXTURE' ? 'DEMO DATA — NOT A LIVE SUPPLIER QUOTE. This product and its offers exist only to demonstrate the workflow.' : 'TEST DATA — created by an automated test. Not real, and excluded from live figures.'}</Alert>}
        {o.provenance === 'UNKNOWN_PROVENANCE' && <Alert severity="warning" sx={{ mt: 1 }} data-testid="unknown-banner">The origin of this record is not known, so it is excluded from live figures and cannot be approved until a buyer classifies it.</Alert>}
        {!gap && <Box sx={{ mt: 1 }}><ProductWorkflow status={o.status} /></Box>}
        {!gap && !d.is_fixture && (o.status === 'APPROVED_OPPORTUNITY' || o.status === 'APPROVED_FOR_ONBOARDING') && (
          <Alert severity="info" icon={false} sx={{ mt: 1 }} data-testid="pilot-link" action={<Button size="small" component={RouterLink} to={`/sales-planning?tab=scenarios&source=DSPORTS_LIVE&opp=${o.id}`}>Plan a store pilot</Button>}>Approved as a business opportunity: a buyer can evaluate it in selected stores in Sales & Inventory Planning. A pilot is a low-confidence estimate from comparable products; it never publishes, buys or prices anything.</Alert>
        )}
        <Stack direction="row" spacing={1} sx={{ mt: 1.25 }} flexWrap="wrap" useFlexGap alignItems="center"><StatusActions d={d} onDone={refresh} /><Classify id={o.id} current={o.provenance} onDone={refresh} /></Stack>
      </Paper>

      <Tabs value={tab} onChange={(_e, v: string) => setTab(v)} variant="scrollable" scrollButtons="auto" data-testid="detail-tabs">
        {[['overview', 'Overview'], ['specs', 'Specifications'], ['evidence', 'Market evidence'], ['demand', 'Customer demand'], ['catalogue', 'Catalogue match'], ['suppliers', 'Supplier offers'], ['cost', 'Cost & margin'], ['score', 'Score & limits'], ['decisions', 'Decisions & readiness']].map(([k, label]) =>
          <Tab key={k} value={k} label={label} data-testid={`dtab-${k}`} />)}
      </Tabs>

      {tab === 'overview' && (
        <Section id="opp-overview" title="Overview">
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '320px 1fr' }, gap: 2 }}>
            <ImageGallery oppId={o.id} name={o.name} images={d.images ?? []} canEdit={!gap} />
            <Box>
              {o.source_title && <Typography variant="body2" data-testid="opp-source-title"><b>Original source title:</b> {o.source_title}</Typography>}
              {o.product_url && <Typography variant="body2"><b>Source page:</b> <Link href={safeHref(o.product_url)} target="_blank" rel="noopener noreferrer" data-testid="opp-product-url">{o.product_url}</Link></Typography>}
              {o.description && <Typography variant="body2" sx={{ mt: 0.75 }}>{o.description}</Typography>}
              {o.trend_note && <Typography variant="body2" sx={{ mt: 0.5 }}><b>Signal:</b> {o.trend_note}</Typography>}
              <Typography variant="body2" sx={{ mt: 0.5 }} data-testid="opp-relevance"><b>Why it may matter to DSports:</b> {o.relevance ?? 'Not assessed yet.'}</Typography>
              {o.unanswered?.length > 0 && <Box sx={{ mt: 1 }} data-testid="opp-unanswered"><Typography variant="caption" fontWeight={800}>Unanswered questions</Typography>
                <Box component="ul" sx={{ m: 0, pl: 2.25 }}>{o.unanswered.map((u) => <Typography key={u} component="li" variant="body2">{u}</Typography>)}</Box></Box>}
            </Box>
          </Box>
        </Section>
      )}

      {tab === 'specs' && (
        <Section id="opp-specs" title="Specifications">
          {specRows.length === 0 ? <Typography variant="body2" color="text.secondary" data-testid="no-specs">No specification was stated by any source.</Typography> : (
            <Table size="small"><TableBody>{specRows.map(([k, v]) => <TableRow key={k} data-testid="spec-row"><TableCell sx={{ width: 240, fontWeight: 700 }}>{k}</TableCell><TableCell>{v}</TableCell></TableRow>)}</TableBody></Table>
          )}
          {d.missing_specifications?.length > 0 && <Alert severity="info" icon={false} sx={{ mt: 1 }} data-testid="missing-specs">Not stated by any source: {d.missing_specifications.join(', ')}. These are shown as missing; they are never generated or inferred.</Alert>}
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }}>Specifications are copied from the source page's own table. Certifications, dimensions or materials that the source does not state are not assumed.</Typography>
        </Section>
      )}

      {tab === 'evidence' && (
        <Section id="opp-evidence" title={`Market evidence (${d.evidence.length})`}>
          {d.evidence.length === 0 ? <Typography variant="body2" color="text.secondary">No evidence is recorded for this record.</Typography> : d.evidence.map((e) => (
            <Box key={e.id} sx={{ mb: 0.75 }} data-testid="evidence-row">
              <Typography variant="body2" component="div"><Chip size="small" label={pretty(e.kind)} sx={{ mr: 0.75, height: 20 }} />{e.source_type && <Chip size="small" variant="outlined" label={pretty(e.source_type)} sx={{ mr: 0.75, height: 20 }} />}<b>{e.claim}</b> <Typography component="span" variant="caption" color="text.secondary">· {e.source_label} · retrieved {day(e.retrieved_at)} · checked {day(e.verified_at)}</Typography></Typography>
              {e.url && <Link href={safeHref(e.url)} target="_blank" rel="noopener noreferrer" variant="caption">{e.url}</Link>}
              {e.excerpt && <Typography variant="caption" color="text.secondary" display="block">“{e.excerpt}”</Typography>}
            </Box>
          ))}
        </Section>
      )}

      {tab === 'demand' && (
        <Section id="opp-demand" title="Customer demand">
          {s?.inputs_summary?.demand && (s.inputs_summary.demand.searches ?? 0) > 0
            ? <Typography variant="body2" data-testid="demand-summary">{s.inputs_summary.demand.searches} search(es) from {s.inputs_summary.demand.customers} distinct customer(s) in the last {s.inputs_summary.demand_window_days ?? 30} days
              {s.inputs_summary.demand.queries?.length ? `: ${s.inputs_summary.demand.queries.join('; ')}` : ''}. Interest, not proven purchase intent.</Typography>
            : <Typography variant="body2" color="text.secondary" data-testid="no-demand">No customer search is linked to this record.</Typography>}
          {gap && (o.linked_candidate_id
            ? <Alert severity="success" sx={{ mt: 1 }}>Linked to a product candidate.</Alert>
            : <Alert severity="info" sx={{ mt: 1 }}>This is a demand signal, not a product. Link it to a real product from the Customer demand tab once a matching product is found.</Alert>)}
        </Section>
      )}

      {tab === 'catalogue' && (
        <Section id="opp-catalogue" title="Catalogue matching">
          <Typography variant="body2" data-testid="opp-catalogue-match"><b>Catalogue:</b> {pretty(o.catalogue_match?.level)} — {o.catalogue_match?.reason ?? 'not checked'}</Typography>
          {o.catalogue_match?.product_id && <Typography variant="caption" color="text.secondary" display="block">Matched catalogue product id: {o.catalogue_match.product_id}</Typography>}
          {o.possible_dupes?.length > 0 && <Alert severity="info" sx={{ mt: 1 }}>Possible near-duplicates to review (never merged automatically): {o.possible_dupes.map((p) => `${p.name} (${p.score})`).join('; ')}</Alert>}
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }}>A match is a suggestion for a human. Nothing is merged, replaced or published automatically.</Typography>
        </Section>
      )}

      {tab === 'suppliers' && (
        <>
          <Section id="opp-quotes" title={`Supplier offers (${d.quotes.length})`}>
            {d.quotes.length === 0 ? <Typography variant="body2" color="text.secondary" data-testid="no-quotes">No supplier offer is recorded. Add one from a supplier quotation: pages rarely show prices, so a quotation is needed before any margin exists.</Typography> : (
              <Table size="small"><TableHead><TableRow><TableCell>Supplier</TableCell><TableCell>Source</TableCell><TableCell align="right">Unit price</TableCell><TableCell align="right">MOQ</TableCell><TableCell>Validity</TableCell><TableCell /></TableRow></TableHead>
                <TableBody>{d.quotes.map((x) => (
                  <TableRow key={x.id} data-testid="quote-row">
                    <TableCell><b>{x.supplier_name}</b> {x.provenance && !['BUYER_ENTERED', 'LIVE_EXTERNAL_SOURCE'].includes(x.provenance) && <ProvenanceChip provenance={x.provenance} />}<br /><Typography variant="caption" color="text.secondary">{x.offered_product}{x.reference ? ` · ref ${x.reference}` : ''}</Typography></TableCell>
                    <TableCell><KindChip kind={x.source_kind} label={x.label} />{x.verified_by && <Typography variant="caption" display="block">verified by a buyer</Typography>}</TableCell>
                    <TableCell align="right">{x.currency} {x.unit_price}{x.price_breaks.length > 0 && <Typography variant="caption" display="block">{x.price_breaks.map((b) => `${b.min_quantity}+: ${b.unit_price}`).join(', ')}</Typography>}</TableCell>
                    <TableCell align="right">{x.moq ?? '—'}</TableCell>
                    <TableCell>{x.status !== 'ACTIVE' ? <Chip size="small" color="error" label={pretty(x.status)} /> : x.valid_until ? `until ${x.valid_until}` : 'no validity date'}{x.warnings.map((w) => <Typography key={w} variant="caption" color="text.secondary" display="block">{w}</Typography>)}</TableCell>
                    <TableCell>{x.source_kind === 'QUOTE_ENTERED' && <Button size="small" onClick={() => verify.mutate(x.id)} disabled={verify.isPending} data-testid="quote-verify">Mark verified</Button>}</TableCell>
                  </TableRow>))}</TableBody></Table>
            )}
            {verify.isError && <Alert severity="warning" sx={{ mt: 1 }}>{sourcingError(verify.error)}</Alert>}
            {!gap && <Button size="small" variant="outlined" sx={{ mt: 1 }} onClick={() => setQuoteOpen(true)} data-testid="quote-add">Record a supplier offer</Button>}
            {quoteOpen && <QuoteDialog oppId={id} onClose={() => setQuoteOpen(false)} onSaved={refresh} includeFixtures={d.is_fixture} />}
          </Section>
          <Section id="opp-possible-suppliers" title={`Possible suppliers found for this brand (${d.possible_suppliers?.length ?? 0})`}>
            {(d.possible_suppliers?.length ?? 0) === 0 ? <Typography variant="body2" color="text.secondary" data-testid="no-possible-suppliers">No supplier candidate has been found for this brand.</Typography> : d.possible_suppliers.map((p) => (
              <Box key={p.id} sx={{ mb: 0.75 }} data-testid="possible-supplier">
                <Typography variant="body2" component="div"><b>{p.name}</b> · {pretty(p.type)}{p.country ? ` · ${p.country}` : ''} <StageChip stage={p.verification_status} /></Typography>
                {p.discovery_note && <Typography variant="caption" color="text.secondary" display="block">{p.discovery_note}</Typography>}
                {p.discovery_url && <Link href={safeHref(p.discovery_url)} target="_blank" rel="noopener noreferrer" variant="caption">{p.discovery_url}</Link>}
              </Box>))}
            <Typography variant="caption" color="text.secondary" display="block">A candidate is not a verified or authorised supplier and has quoted nothing. Contact it, then record the quotation here.</Typography>
          </Section>
        </>
      )}

      {tab === 'cost' && (
        <>
          <Paper variant="outlined" sx={{ p: 1.5 }}><ComparePanel oppId={id} initialPrice={o.selling_price} quoteCount={d.quotes.length} /></Paper>
          <Section id="opp-calcs" title={`Saved calculations (${calcs.length})`}>
            {calcs.length === 0 ? <Typography variant="body2" color="text.secondary" data-testid="no-calcs">No calculation has been saved for this product. A margin needs a supplier quotation, a quantity and a selling price.</Typography> : calcs.map((c) => {
              const b = (c.result?.basis ?? c.basis ?? {}) as Record<string, unknown>;
              return (
                <Box key={c.id} sx={{ mb: 0.75 }} data-testid="calc-row">
                  {c.id === calcs[0].id && c.result?.cost_drivers?.length > 0 && <CostBreakdown drivers={c.result.cost_drivers} />}
                  <Typography variant="body2" component="div"><Chip size="small" variant="outlined" color={String(b.label ?? '').startsWith('Quoted') ? 'success' : 'warning'} label={String(b.label ?? 'Calculation')} sx={{ mr: 0.75 }} />
                    Landed cost {String(c.result?.values?.landed_cost_per_unit ?? '—')} {String(b.currency ?? '')} · margin {String(c.result?.values?.gross_margin_pct ?? '—')}% (margin on price) · markup {String(c.result?.values?.markup_pct ?? '—')}%</Typography>
                  <Typography variant="caption" color="text.secondary">{String(b.supplier ?? 'supplier not recorded')} · quantity {String(b.quantity ?? '—')} · {when(c.created_at)}</Typography>
                </Box>);
            })}
            <Typography variant="caption" color="text.secondary" display="block">Margin is profit as a share of the selling price; markup is profit as a share of cost. Figures labelled “Estimated” come from a public listing and are not a quotation.</Typography>
          </Section>
        </>
      )}

      {tab === 'score' && (
        <Section id="opp-score" title="Score and its limits">
          {!s ? <Typography variant="body2" color="text.secondary">Not scored yet.</Typography> : (
            <Stack spacing={0.75}>
              {s.status === 'INSUFFICIENT_EVIDENCE' || s.status === 'AWAITING_PRODUCT_DISCOVERY'
                ? <Alert severity="info" data-testid="score-insufficient">{s.status === 'AWAITING_PRODUCT_DISCOVERY' ? 'Awaiting product discovery. ' : 'Insufficient evidence to give a score. '}{s.reasons.join(' ')}</Alert>
                : <Typography variant="h5" fontWeight={900} data-testid="score-value">{s.opportunity}<Typography component="span" variant="body2"> / 100 · priority {pretty(s.priority)}</Typography></Typography>}
              <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
                <Chip size="small" variant="outlined" label={`Evidence confidence: ${pretty(s.confidence)}`} data-testid="score-confidence" />
                <Chip size="small" variant="outlined" label={`Data completeness: ${Math.round(s.data_completeness * 100)}%`} />
                <RiskChip level={s.supplier_risk.level} />
                <Chip size="small" variant="outlined" label={`Financial basis: ${FIN_LABEL[s.financial_status ?? 'NONE']}`} data-testid="score-financial" />
                <Chip size="small" variant="outlined" label={`Model ${s.model_version}`} />
              </Stack>
              <Typography variant="caption" color="text.secondary">Opportunity, evidence confidence, data completeness, supplier risk and the financial basis are separate: a high score never hides weak evidence, a risky supplier or an estimated margin. Scored {when(d.score_at)}.</Typography>
              {s.dimensions.length > 0 && <ScoreBars score={s} />}
              {s.dimensions.length > 0 && <Table size="small"><TableHead><TableRow><TableCell>Dimension</TableCell><TableCell align="right">Score</TableCell><TableCell align="right">Weight</TableCell><TableCell>Why</TableCell></TableRow></TableHead>
                <TableBody>{s.dimensions.map((x) => <TableRow key={x.dimension}><TableCell>{pretty(x.dimension)}</TableCell><TableCell align="right">{x.score}</TableCell><TableCell align="right">{Math.round(x.weight * 100)}%</TableCell><TableCell>{x.why}</TableCell></TableRow>)}</TableBody></Table>}
              {s.missing.length > 0 && <Typography variant="body2" data-testid="score-missing"><b>No data for:</b> {s.missing.map(pretty).join(', ')}. These are left out, not counted as zero.</Typography>}
              {s.supplier_risk.factors.map((f) => <Typography key={f} variant="caption" color="text.secondary" display="block">Risk: {f}</Typography>)}
              <Typography variant="body2"><b>Next step:</b> {s.next_action}</Typography>
            </Stack>
          )}
          {!gap && <Button size="small" sx={{ mt: 0.75 }} onClick={() => rescore.mutate()} disabled={rescore.isPending} data-testid="rescore">{s ? 'Recalculate score' : 'Calculate score'}</Button>}
          {rescore.isError && <Alert severity="warning" sx={{ mt: 1 }}>{sourcingError(rescore.error)}</Alert>}
        </Section>
      )}

      {tab === 'decisions' && (
        <>
          <Section id="opp-readiness" title="Catalogue readiness (independent of the approval status)">
            {!readiness.applicable ? <Typography variant="body2" color="text.secondary">{readiness.summary}</Typography> : (
              <>
                <Alert severity={readiness.ready ? 'success' : 'warning'} icon={false} sx={{ mb: 1 }} data-testid="readiness-summary">{readiness.summary} Approval and readiness are separate: an approved product can still be missing catalogue fields.</Alert>
                {readiness.items.map((i) => <Typography key={i.code} variant="body2" color={i.ok ? 'text.primary' : 'warning.main'} data-testid="readiness-item">{i.ok ? '✔' : '✖'} {i.label}{!i.ok && i.hint ? ` — ${i.hint}` : ''}</Typography>)}
              </>
            )}
          </Section>
          <Section id="opp-proposal" title="Onboarding proposal">
            {d.proposals.map((p) => (
              <Box key={p.id} sx={{ mb: 1 }} data-testid="proposal-row">
                <Chip size="small" color={p.status === 'DRAFT' ? 'warning' : 'success'} label={pretty(p.status)} sx={{ mr: 0.75 }} /> <Typography component="span" variant="caption">{when(p.created_at)}</Typography>
                {p.validation.missing_mandatory.length > 0 && <Typography variant="body2" color="warning.main" data-testid="proposal-missing">Still needed: {p.validation.missing_mandatory.join('; ')}</Typography>}
                <Typography variant="caption" color="text.secondary" display="block">{p.payload.notice}</Typography>
              </Box>
            ))}
            {o.status === 'APPROVED_FOR_ONBOARDING' ? (
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <TextField select size="small" SelectProps={{ native: true }} value={proposalQuote} onChange={(e) => setProposalQuote(e.target.value)} sx={{ minWidth: 220 }} inputProps={{ 'data-testid': 'proposal-quote' }}>
                  <option value="">Choose the supplier offer</option>{d.quotes.map((x) => <option key={x.id} value={x.id}>{x.supplier_name} — {x.currency} {x.unit_price}</option>)}</TextField>
                <TextField size="small" type="number" label="Quantity" value={proposalQty} onChange={(e) => setProposalQty(e.target.value)} sx={{ width: 110 }} />
                <TextField size="small" type="number" label="Selling price" value={proposalPrice} onChange={(e) => setProposalPrice(e.target.value)} sx={{ width: 130 }} />
                <Button size="small" variant="outlined" onClick={() => check.mutate()} disabled={check.isPending} data-testid="readiness-check">Check readiness</Button>
                <Button size="small" variant="contained" disabled={!proposalQuote || !Number(proposalQty) || proposal.isPending} onClick={() => proposal.mutate()} data-testid="proposal-create">Prepare proposal</Button>
              </Stack>
            ) : <Typography variant="body2" color="text.secondary">A proposal can be prepared once the product is approved for sourcing preparation. Approving never publishes anything.</Typography>}
            {proposal.isError && <Alert severity="warning" sx={{ mt: 1 }}>{sourcingError(proposal.error)}</Alert>}
          </Section>
          <Section id="opp-history" title="Notes and decision history">
            <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
              <TextField size="small" fullWidth label="Add an internal note" value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ 'data-testid': 'note-text', maxLength: 2000 }} />
              <Button variant="outlined" disabled={!note.trim() || addNote.isPending} onClick={() => addNote.mutate()} data-testid="note-add">Add</Button>
            </Stack>
            {d.history.map((h) => (
              <Typography key={h.id} variant="body2" data-testid="history-row"><Typography component="span" variant="caption" color="text.secondary">{when(h.at)} · buyer {h.actor_hash.slice(0, 6)} · </Typography>
                <b>{pretty(h.action)}</b>{h.from_status || h.to_status ? ` ${pretty(h.from_status)} → ${pretty(h.to_status)}` : ''}{h.reason ? `: ${h.reason}` : ''}</Typography>
            ))}
          </Section>
        </>
      )}
    </Stack>
  );
};

export default OpportunityDetail;
