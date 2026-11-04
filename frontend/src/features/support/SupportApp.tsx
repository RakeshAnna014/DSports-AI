import { useEffect, useState } from 'react';
import {
  Alert, Box, Button, Chip, FormControl, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TablePagination, TableRow, TextField, Typography, useMediaQuery,
} from '@mui/material';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { supportApi, supportError, type StaffFilters } from '@/api/support';
import { useAuthStore } from '@/store/authStore';
import { colors, gradients } from '@/design/tokens';
import { NEXT_STATUS, OUTCOMES, type CaseStatus, type StaffCase } from '@/types/support';
import AiWorkspace from './AiWorkspace';
import ReportPanel from './ReportPanel';
import { EscalationStatus, PriorityChip, StatusChip, label, outcomeLabel, when } from './parts';

export const isSupportStaff = (roles?: string[]) => !!roles?.some((r) => ['ADMIN', 'SUPPORT_EXECUTIVE', 'ROLE_ADMIN', 'ROLE_SUPPORT_EXECUTIVE'].includes(r));
const QUEUES = ['GENERAL_SUPPORT', 'LOGISTICS', 'PAYMENTS', 'REFUNDS_RETURNS', 'PRODUCT_QUALITY'];
const STATUSES: CaseStatus[] = ['OPEN', 'INVESTIGATING', 'WAITING_FOR_CUSTOMER', 'WAITING_FOR_EXTERNAL_PARTY', 'RESOLVED'];
const PRIORITIES = ['URGENT', 'HIGH', 'MEDIUM', 'LOW'];
const CATEGORIES = ['MISSING_DELIVERY', 'DELAYED_DELIVERY', 'DAMAGED_PRODUCT', 'WRONG_PRODUCT', 'PARTIAL_DELIVERY', 'PAYMENT_ISSUE', 'DUPLICATE_PAYMENT',
  'REFUND_ISSUE', 'RETURN_ISSUE', 'EXCHANGE_REQUEST', 'PRODUCT_COMPLAINT', 'ORDER_ISSUE', 'TECHNICAL_ISSUE', 'GENERAL'];

const Kpi = ({ title, value, hint, testId }: { title: string; value: React.ReactNode; hint?: string; testId: string }) => (
  <Paper variant="outlined" sx={{ p: 1.5, minWidth: 150, flex: 1 }} data-testid={testId}>
    <Typography variant="caption" color="text.secondary" fontWeight={700} sx={{ textTransform: 'uppercase' }}>{title}</Typography>
    <Typography variant="h5" fontWeight={900}>{value}</Typography>
    {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
  </Paper>
);

const Analytics = () => {
  const a = useQuery({ queryKey: ['support', 'staff', 'analytics'], queryFn: supportApi.staff.analytics, retry: false });
  if (a.isLoading) return <Skeleton variant="rounded" height={90} />;
  if (a.isError) return <Alert severity="warning">{supportError(a.error).message}</Alert>;
  const d = a.data!;
  return (
    <Box>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap data-testid="support-analytics">
        <Kpi testId="kpi-open" title="Open" value={d.byStatus.OPEN ?? 0} hint="not started yet" />
        <Kpi testId="kpi-in-progress" title="In progress" value={(d.byStatus.INVESTIGATING ?? 0) + (d.byStatus.WAITING_FOR_CUSTOMER ?? 0) + (d.byStatus.WAITING_FOR_EXTERNAL_PARTY ?? 0)} hint="investigating or waiting" />
        <Kpi testId="kpi-resolved" title="Resolved" value={d.byStatus.RESOLVED ?? 0} />
        <Kpi testId="kpi-unresolved" title="Unresolved" value={d.unresolvedCases} hint={`of ${d.totalCases} cases`} />
        <Kpi testId="kpi-escalation" title="Review requested" value={d.escalationRequestedNotYetAccepted} hint="nobody has taken them yet" />
        <Kpi testId="kpi-unassigned" title="Unassigned" value={d.unassignedUnresolved} />
        <Kpi testId="kpi-reopened" title="Reopened" value={d.reopenedCases} />
        <Kpi testId="kpi-human" title="Resolved by a person" value={d.resolvedByHuman} hint={`automatic: ${d.automaticallyResolved}`} />
        <Kpi testId="kpi-age" title="Oldest unresolved" value={d.oldestUnresolvedAgeHours == null ? '—' : `${Math.round(d.oldestUnresolvedAgeHours)} h`} hint="no SLA is defined" />
      </Stack>
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 1 }} data-testid="analytics-breakdown">
        {Object.entries(d.byCategory).map(([k, n]) => <Chip key={k} size="small" variant="outlined" label={`${label(k)}: ${n}`} />)}
        {Object.entries(d.unresolvedByPriority).map(([k, n]) => <Chip key={k} size="small" color="primary" variant="outlined" label={`${k} (unresolved): ${n}`} />)}
      </Stack>
      <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }} data-testid="analytics-definition">
        Every number is counted from the stored cases. {d.definitions.automaticallyResolved}
      </Typography>
    </Box>
  );
};

const Source = ({ s }: { s: string }) => (
  <Chip size="small" variant="outlined" label={s === 'BACKEND_VERIFIED' ? 'Backend verified' : s === 'AI_INVESTIGATION' ? 'AI investigation (not re-checked)' : label(s)}
    color={s === 'BACKEND_VERIFIED' ? 'success' : 'default'} sx={{ ml: 0.5, height: 20 }} />
);

const Actions = ({ c }: { c: StaffCase }) => {
  const qc = useQueryClient();
  const [text, setText] = useState('');
  const [note, setNote] = useState('');
  const [status, setStatus] = useState('');
  const [outcome, setOutcome] = useState<string>(OUTCOMES[0]);
  const [refs, setRefs] = useState('');
  const [queue, setQueue] = useState(c.assignedQueue ?? '');
  const [priority, setPriority] = useState('');
  const [priorityReason, setPriorityReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const settle = () => { setText(''); setNote(''); setError(null); void qc.invalidateQueries({ queryKey: ['support'] }); };
  const fail = (e: unknown) => setError(supportError(e).code === 'OPTIMISTIC_LOCKING_CONFLICT' ? 'Someone else changed this case. It has been reloaded; check it and try again.' : supportError(e).message);
  const v = c.version;
  const take = useMutation({ mutationFn: () => supportApi.staff.assign(c.id, { takeIt: true, queue: queue || undefined, expectedVersion: v }), onSuccess: settle, onError: fail });
  const setQ = useMutation({ mutationFn: () => supportApi.staff.assign(c.id, { takeIt: false, queue, expectedVersion: v }), onSuccess: settle, onError: fail });
  const changePriority = useMutation({
    mutationFn: () => supportApi.staff.priority(c.id, priority, priorityReason, v),
    onSuccess: () => { setPriority(''); setPriorityReason(''); settle(); }, onError: fail,
  });
  const internal = useMutation({ mutationFn: () => supportApi.staff.note(c.id, note, v), onSuccess: settle, onError: fail });
  const reply = useMutation({ mutationFn: () => supportApi.staff.reply(c.id, text, v), onSuccess: settle, onError: fail });
  const change = useMutation({ mutationFn: () => supportApi.staff.status(c.id, status, undefined, v), onSuccess: () => { setStatus(''); settle(); }, onError: fail });
  const resolve = useMutation({
    mutationFn: () => supportApi.staff.resolve(c.id, outcome, text, refs.split(',').map((r) => r.trim()).filter(Boolean), v),
    onSuccess: settle, onError: fail,
  });
  const resolved = c.status === 'RESOLVED';
  return (
    <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="staff-actions">
      <Typography variant="subtitle2" fontWeight={900} sx={{ mb: 1 }}>Actions</Typography>
      {error && <Alert severity="warning" sx={{ mb: 1 }} data-testid="staff-action-error">{error}</Alert>}
      <Stack spacing={1.5}>
        {!resolved && (
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Button variant="contained" size="small" disabled={take.isPending} onClick={() => take.mutate()} data-testid="staff-take">
              {c.assignedTo ? 'Take over this case' : 'Take this case'}
            </Button>
            <FormControl size="small" sx={{ minWidth: 190 }}>
              <InputLabel id="staff-queue-label">Queue</InputLabel>
              <Select labelId="staff-queue-label" label="Queue" value={queue} onChange={(e) => setQueue(e.target.value)} SelectDisplayProps={{ 'data-testid': 'staff-queue' } as React.HTMLAttributes<HTMLDivElement>}>
                <MenuItem value="">(none)</MenuItem>{QUEUES.map((q) => <MenuItem key={q} value={q}>{label(q)}</MenuItem>)}
              </Select>
            </FormControl>
            <Button size="small" disabled={!queue || setQ.isPending} onClick={() => setQ.mutate()} data-testid="staff-set-queue">Set queue</Button>
          </Stack>
        )}
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <FormControl size="small" sx={{ minWidth: 230 }}>
            <InputLabel id="staff-status-label">Change status</InputLabel>
            <Select labelId="staff-status-label" label="Change status" value={status} onChange={(e) => setStatus(e.target.value)} SelectDisplayProps={{ 'data-testid': 'staff-status' } as React.HTMLAttributes<HTMLDivElement>}>
              {NEXT_STATUS[c.status].map((s) => <MenuItem key={s} value={s}>{label(s)}{c.status === 'RESOLVED' ? ' (reopens the case)' : ''}</MenuItem>)}
            </Select>
          </FormControl>
          <Button size="small" disabled={!status || change.isPending} onClick={() => change.mutate()} data-testid="staff-change-status">Apply</Button>
          <Typography variant="caption" color="text.secondary">Only transitions the lifecycle allows are listed; the backend checks again.</Typography>
        </Stack>
        {!resolved && (
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <FormControl size="small" sx={{ minWidth: 150 }}>
              <InputLabel id="staff-priority-label">Change priority</InputLabel>
              <Select labelId="staff-priority-label" label="Change priority" value={priority} onChange={(e) => setPriority(e.target.value)} SelectDisplayProps={{ 'data-testid': 'staff-priority-select' } as React.HTMLAttributes<HTMLDivElement>}>
                {PRIORITIES.filter((p) => p !== c.priority).map((p) => <MenuItem key={p} value={p}>{p}</MenuItem>)}
              </Select>
            </FormControl>
            <TextField size="small" label="Reason" value={priorityReason} onChange={(e) => setPriorityReason(e.target.value)} sx={{ minWidth: 220 }} inputProps={{ maxLength: 500, 'data-testid': 'staff-priority-reason' }} />
            <Button size="small" disabled={!priority || !priorityReason.trim() || changePriority.isPending} onClick={() => changePriority.mutate()} data-testid="staff-change-priority">Apply</Button>
          </Stack>
        )}
        <TextField size="small" multiline minRows={2} label="Internal note (never shown to the customer)" value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ 'data-testid': 'staff-note-text' }} />
        <Box><Button size="small" disabled={!note.trim() || internal.isPending} onClick={() => internal.mutate()} data-testid="staff-add-note">Add internal note</Button></Box>
        <TextField size="small" multiline minRows={2} label={resolved ? 'Reply to the customer' : 'Reply to the customer, or the resolution note'} value={text} onChange={(e) => setText(e.target.value)} inputProps={{ 'data-testid': 'staff-reply-text' }} />
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
          <Button size="small" variant="outlined" disabled={!text.trim() || reply.isPending} onClick={() => reply.mutate()} data-testid="staff-reply">Send reply to customer</Button>
        </Stack>
        {!resolved && (
          <Paper variant="outlined" sx={{ p: 1.25, bgcolor: 'rgba(22,163,74,0.04)' }}>
            <Typography variant="subtitle2" fontWeight={800}>Resolve</Typography>
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 1 }}>
              Recording an outcome does not move money or change an order. Refunds and return approvals use their own flows. The text above is saved as the resolution note.
            </Typography>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
              <FormControl size="small" sx={{ minWidth: 260 }}>
                <InputLabel id="staff-outcome-label">Outcome</InputLabel>
                <Select labelId="staff-outcome-label" label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)} SelectDisplayProps={{ 'data-testid': 'staff-outcome' } as React.HTMLAttributes<HTMLDivElement>}>
                  {OUTCOMES.map((o) => <MenuItem key={o} value={o}>{outcomeLabel(o)}</MenuItem>)}
                </Select>
              </FormControl>
              <TextField size="small" label="Supporting references (comma separated)" value={refs} onChange={(e) => setRefs(e.target.value)} sx={{ minWidth: 260 }} inputProps={{ 'data-testid': 'staff-refs' }} />
              <Button size="small" color="success" variant="contained" disabled={!text.trim() || resolve.isPending} onClick={() => resolve.mutate()} data-testid="staff-resolve">Resolve case</Button>
            </Stack>
          </Paper>
        )}
      </Stack>
    </Paper>
  );
};

const Detail = ({ reference }: { reference: string }) => {
  const q = useQuery({ queryKey: ['support', 'staff', 'case', reference], queryFn: () => supportApi.staff.get(reference), retry: false });
  if (q.isLoading) return <Skeleton variant="rounded" height={300} />;
  if (q.isError) return <Alert severity="warning" data-testid="staff-detail-error">{supportError(q.error).message}</Alert>;
  const c = q.data!;
  const by = (source: string) => c.evidence.filter((e) => e.source === source);
  return (
    <Stack spacing={1.5} data-testid="staff-detail" data-case={c.caseNumber}>
      <Paper variant="outlined" sx={{ p: 1.75 }}>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.5 }}>
          <Typography variant="h6" fontWeight={900} data-testid="staff-case-number">{c.caseNumber}</Typography>
          <StatusChip status={c.status} /><PriorityChip priority={c.priority} />
          {c.safetyRelated && <Chip size="small" color="error" label="Safety related" />}
          <Chip size="small" variant="outlined" label={c.source === 'AI_ASSISTANT' ? 'Opened via the AI assistant' : 'Opened on the web'} />
        </Stack>
        <Typography variant="subtitle1" fontWeight={800}>{c.subject}</Typography>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }}>
          {c.orderNumber ? `Order ${c.orderNumber} (${c.orderStatusAtCreation ?? '—'} when opened, shipment ${c.shipmentStatusAtCreation ?? '—'}) · ` : ''}{label(c.category)} · customer {c.customerId.slice(0, 8)} · version {c.version}
          {c.assignedQueue ? ` · queue ${label(c.assignedQueue)}` : ''}{c.assignedTo ? ' · assigned' : ' · unassigned'}
        </Typography>
        <EscalationStatus escalation={c.escalation} resolved={c.status === 'RESOLVED'} />
        {c.escalation.state !== 'NONE' && c.escalation.reason && <Typography variant="caption" color="text.secondary">Reason: {c.escalation.reason}</Typography>}
        {c.status === 'RESOLVED' && (
          <Alert severity="success" icon={false} sx={{ mt: 1 }} data-testid="staff-resolved">Resolved ({outcomeLabel(c.resolutionOutcome)}): {c.resolutionNote}</Alert>
        )}
      </Paper>
      <Paper variant="outlined" sx={{ p: 1.5 }}>
        <Typography variant="subtitle2" fontWeight={900}>Customer statement</Typography>
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{c.description}</Typography>
      </Paper>
      <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="staff-evidence">
        <Typography variant="subtitle2" fontWeight={900} sx={{ mb: 0.5 }}>Investigation evidence</Typography>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }}>
          Backend-verified items were read from DSports' own records when the case was opened. AI-investigation items were gathered by the assistant and are not re-checked: verify them before a financial decision.
        </Typography>
        {['BACKEND_VERIFIED', 'AI_INVESTIGATION', 'CUSTOMER', 'STAFF'].map((src) => by(src).length === 0 ? null : (
          <Box key={src} sx={{ mb: 0.75 }} data-testid={`evidence-${src}`}>
            <Typography variant="caption" fontWeight={800}>{label(src)}<Source s={src} /></Typography>
            <Box component="ul" sx={{ m: 0, pl: 2.25 }}>{by(src).map((e, i) => <Typography key={i} component="li" variant="body2">{label(e.kind)}{e.ref ? ` (${e.ref})` : ''}: {e.summary}</Typography>)}</Box>
          </Box>
        ))}
        {c.investigationSummary && <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{c.investigationSummary}</Typography>}
        {c.missingInformation.length > 0 && <Typography variant="body2" sx={{ mt: 0.5 }}><b>Not verified:</b> {c.missingInformation.join('; ')}</Typography>}
        {c.policyCitations.length > 0 && <Typography variant="body2" sx={{ mt: 0.5 }} data-testid="staff-citations"><b>Policy cited:</b> {c.policyCitations.join('; ')}</Typography>}
        {c.resolutionRecommendation && <Typography variant="body2" sx={{ mt: 0.5 }}><b>Assistant's recommendation:</b> {c.resolutionRecommendation}</Typography>}
      </Paper>
      <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="staff-priority">
        <Typography variant="subtitle2" fontWeight={900}>Why this priority</Typography>
        <Box component="ul" sx={{ m: 0, pl: 2.25 }}>{c.priorityReasons.map((r) => <Typography key={r} component="li" variant="body2">{r}</Typography>)}</Box>
        <Typography variant="caption" color="text.secondary">Demo rules, not an official SLA. Tone and complaint history are never used.</Typography>
      </Paper>
      <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="staff-messages">
        <Typography variant="subtitle2" fontWeight={900} sx={{ mb: 0.5 }}>Conversation</Typography>
        <Stack spacing={0.75}>
          {c.messages.map((m) => (
            <Box key={m.id} sx={{ bgcolor: m.internal ? 'rgba(247,144,9,0.12)' : 'action.hover', borderRadius: 2, px: 1.25, py: 0.75 }} data-testid={m.internal ? 'staff-internal-note' : 'staff-message'}>
              <Typography variant="caption" color="text.secondary">{m.internal ? 'INTERNAL NOTE (customer cannot see)' : label(m.authorType)} · {when(m.createdAt)}</Typography>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{m.body}</Typography>
            </Box>
          ))}
        </Stack>
      </Paper>
      <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="staff-history">
        <Typography variant="subtitle2" fontWeight={900} sx={{ mb: 0.5 }}>History (audit)</Typography>
        <Table size="small"><TableHead><TableRow><TableCell>When</TableCell><TableCell>Event</TableCell><TableCell>By</TableCell><TableCell>Detail</TableCell></TableRow></TableHead>
          <TableBody>{c.events.map((e, i) => (
            <TableRow key={i}><TableCell>{when(e.at)}</TableCell><TableCell>{label(e.type)}{e.fromStatus ? ` (${label(e.fromStatus)} to ${label(e.toStatus ?? '')})` : ''}</TableCell>
              <TableCell>{label(e.actorType)}</TableCell><TableCell>{e.detail}{e.customerVisible ? '' : ' (internal)'}</TableCell></TableRow>))}</TableBody></Table>
      </Paper>
      <AiWorkspace reference={c.caseNumber} key={`ai-${c.caseNumber}`} />
      <Actions c={c} key={`${c.id}-${c.version}`} />
    </Stack>
  );
};

/** The protected support console. The page hides itself from customers, but the real enforcement is the backend: every request needs the support role. */
const SupportApp = () => {
  const { id } = useParams();
  const nav = useNavigate();
  const { user, isAuthenticated, hydrate } = useAuthStore();
  const [ready, setReady] = useState(false);
  useEffect(() => { hydrate(); setReady(true); }, [hydrate]);
  const wide = useMediaQuery('(min-width:1100px)');
  const [filters, setFilters] = useState<StaffFilters>({ size: 25 });
  const allowed = isAuthenticated && isSupportStaff(user?.roles);
  const list = useQuery({ queryKey: ['support', 'staff', 'list', filters], queryFn: () => supportApi.staff.list(filters), enabled: allowed, retry: false, refetchInterval: 15_000 });

  if (!ready) return <Box sx={{ minHeight: '100vh', bgcolor: colors.surface }} data-testid="staff-loading" />;
  if (!isAuthenticated) {
    return (
      <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: colors.surface, p: 2 }} data-testid="staff-signin">
        <Stack spacing={2} alignItems="center" sx={{ maxWidth: 420, textAlign: 'center' }}>
          <Typography variant="h5" fontWeight={900}>DSports Support Console</Typography>
          <Typography color="text.secondary">Sign in with a support account to continue.</Typography>
          <Button variant="contained" component={RouterLink} to="/login" sx={{ bgcolor: colors.green }}>Sign in</Button>
        </Stack>
      </Box>
    );
  }
  if (!allowed) {
    return (
      <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: colors.surface, p: 2 }} data-testid="staff-restricted">
        <Stack spacing={2} alignItems="center" sx={{ maxWidth: 460, textAlign: 'center' }}>
          <Typography variant="h5" fontWeight={900}>Restricted</Typography>
          <Alert severity="warning">The support console (all customers' cases, internal notes and case operations) is restricted to DSports support staff.</Alert>
          <Button component={RouterLink} to="/support">Go to my support cases</Button>
        </Stack>
      </Box>
    );
  }
  const set = (patch: StaffFilters) => setFilters((f) => ({ ...f, ...patch, page: 0 }));
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: colors.surface }} data-testid="staff-app">
      <Box component="header" sx={{ background: gradients.hero, color: '#fff', px: { xs: 2, md: 4 }, py: 2 }}>
        <Stack direction="row" alignItems="center" spacing={1.5} flexWrap="wrap" useFlexGap>
          <Box sx={{ flex: 1, minWidth: 220 }}>
            <Typography variant="overline" sx={{ color: colors.lime, fontWeight: 800, letterSpacing: 1.5 }}>INTERNAL · SUPPORT STAFF</Typography>
            <Typography variant="h5" fontWeight={900} data-testid="staff-title">DSports Support Console</Typography>
          </Box>
          <Chip size="small" label="Case data is real; any demo cases are labelled in their subject" sx={{ bgcolor: 'rgba(255,255,255,0.12)', color: '#fff' }} />
          <Button size="small" color="inherit" component={RouterLink} to="/">Storefront</Button>
        </Stack>
      </Box>
      <Box sx={{ p: { xs: 1.5, md: 3 } }}>
        <Box sx={{ mb: 2 }}><Analytics /></Box>
        <ReportPanel />
        <Box sx={{ display: 'grid', gridTemplateColumns: wide ? 'minmax(420px, 1fr) minmax(480px, 1.2fr)' : '1fr', gap: 2, alignItems: 'start' }}>
          <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="staff-list">
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 1.25 }}>
              <TextField size="small" label="Search" value={filters.q ?? ''} onChange={(e) => set({ q: e.target.value })} inputProps={{ 'data-testid': 'filter-q' }} sx={{ minWidth: 160 }} />
              <FormControl size="small" sx={{ minWidth: 140 }}><InputLabel id="filter-status-label">Status</InputLabel>
                <Select labelId="filter-status-label" label="Status" value={filters.status ?? ''} onChange={(e) => set({ status: e.target.value || undefined })} SelectDisplayProps={{ 'data-testid': 'filter-status' } as React.HTMLAttributes<HTMLDivElement>}>
                  <MenuItem value="">All</MenuItem>{STATUSES.map((s) => <MenuItem key={s} value={s}>{label(s)}</MenuItem>)}</Select></FormControl>
              <FormControl size="small" sx={{ minWidth: 120 }}><InputLabel id="filter-priority-label">Priority</InputLabel>
                <Select labelId="filter-priority-label" label="Priority" value={filters.priority ?? ''} onChange={(e) => set({ priority: e.target.value || undefined })} SelectDisplayProps={{ 'data-testid': 'filter-priority' } as React.HTMLAttributes<HTMLDivElement>}>
                  <MenuItem value="">All</MenuItem>{PRIORITIES.map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}</Select></FormControl>
              <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="filter-review-label">Review</InputLabel>
                <Select labelId="filter-review-label" label="Review" value={filters.escalation ?? ''} onChange={(e) => set({ escalation: e.target.value || undefined })} SelectDisplayProps={{ 'data-testid': 'filter-escalation' } as React.HTMLAttributes<HTMLDivElement>}>
                  <MenuItem value="">Any</MenuItem><MenuItem value="REQUESTED">Requested, not taken</MenuItem><MenuItem value="ACCEPTED">Taken by a person</MenuItem></Select></FormControl>
              <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel id="filter-category-label">Category</InputLabel>
                <Select labelId="filter-category-label" label="Category" value={filters.category ?? ''} onChange={(e) => set({ category: e.target.value || undefined })} SelectDisplayProps={{ 'data-testid': 'filter-category' } as React.HTMLAttributes<HTMLDivElement>}>
                  <MenuItem value="">All</MenuItem>{CATEGORIES.map((s) => <MenuItem key={s} value={s}>{label(s)}</MenuItem>)}</Select></FormControl>
              <TextField size="small" type="date" label="Created from" InputLabelProps={{ shrink: true }} value={filters.createdFrom ?? ''} onChange={(e) => set({ createdFrom: e.target.value || undefined })} inputProps={{ 'data-testid': 'filter-from' }} />
              <TextField size="small" type="date" label="Created to" InputLabelProps={{ shrink: true }} value={filters.createdTo ?? ''} onChange={(e) => set({ createdTo: e.target.value || undefined })} inputProps={{ 'data-testid': 'filter-to' }} />
              <Chip clickable label="Unassigned only" color={filters.unassigned ? 'primary' : 'default'} onClick={() => set({ unassigned: !filters.unassigned, assignedTo: undefined })} data-testid="filter-unassigned" />
              <Chip clickable label="Assigned to me" color={filters.assignedTo ? 'primary' : 'default'} onClick={() => set({ assignedTo: filters.assignedTo ? undefined : user?.userId, unassigned: undefined })} data-testid="filter-mine" />
            </Stack>
            {list.isLoading && <Skeleton variant="rounded" height={160} />}
            {list.isError && <Alert severity="warning" data-testid="staff-list-error">{supportError(list.error).message}</Alert>}
            {list.data && (
              <>
                <Table size="small">
                  <TableHead><TableRow><TableCell>Case</TableCell><TableCell>Category</TableCell><TableCell>Subject</TableCell><TableCell>Status</TableCell><TableCell>Priority</TableCell><TableCell>Review</TableCell></TableRow></TableHead>
                  <TableBody>
                    {list.data.items.map((c) => (
                      <TableRow key={c.id} hover selected={id === c.caseNumber || id === c.id} onClick={() => nav(`/support-cases/${c.caseNumber}`)} sx={{ cursor: 'pointer' }} data-testid="staff-case-row" data-case={c.caseNumber}>
                        <TableCell sx={{ whiteSpace: 'nowrap', fontWeight: 700 }}>{c.caseNumber}</TableCell>
                        <TableCell>{label(c.category)}</TableCell>
                        <TableCell sx={{ maxWidth: 220 }}><Typography variant="body2" noWrap title={c.subject}>{c.subject}</Typography></TableCell>
                        <TableCell><StatusChip status={c.status} /></TableCell>
                        <TableCell><PriorityChip priority={c.priority} /></TableCell>
                        <TableCell>{c.escalation.state === 'NONE' ? '' : c.escalation.state === 'ACCEPTED' ? 'Taken' : 'Requested'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
                {list.data.items.length === 0 && <Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }} data-testid="staff-empty">No cases match.</Typography>}
                <TablePagination component="div" count={list.data.total} page={list.data.page} rowsPerPage={list.data.size} rowsPerPageOptions={[10, 25, 50]}
                  onPageChange={(_, p) => setFilters((f) => ({ ...f, page: p }))} onRowsPerPageChange={(e) => setFilters((f) => ({ ...f, size: Number(e.target.value), page: 0 }))}
                  labelDisplayedRows={({ from, to, count }) => `${from}-${to} of ${count} case(s), most urgent first`} data-testid="staff-pagination" />
              </>
            )}
          </Paper>
          <Box>{id ? <Detail reference={id} key={id} /> : <Typography color="text.secondary" data-testid="staff-pick">Choose a case to see its evidence, history and actions.</Typography>}</Box>
        </Box>
      </Box>
    </Box>
  );
};

export default SupportApp;
