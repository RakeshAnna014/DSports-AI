import { Box, Chip, Paper, Stack, Typography } from '@mui/material';
import { CheckCircle, HourglassTop, PersonSearch, SupportAgent } from '@mui/icons-material';
import type { CaseStatus, CaseView, EscalationView, TimelineEvent } from '@/types/support';

export const STATUS_TEXT: Record<CaseStatus, string> = {
  OPEN: 'Received, waiting for a support executive to start on it',
  INVESTIGATING: 'Being looked at',
  WAITING_FOR_CUSTOMER: 'Waiting for a reply from you',
  WAITING_FOR_EXTERNAL_PARTY: 'Waiting for an answer from another party (for example the courier or the payment provider)',
  RESOLVED: 'Resolved',
};
const STATUS_COLOR: Record<CaseStatus, 'default' | 'info' | 'warning' | 'success'> = {
  OPEN: 'default', INVESTIGATING: 'info', WAITING_FOR_CUSTOMER: 'warning', WAITING_FOR_EXTERNAL_PARTY: 'warning', RESOLVED: 'success',
};
const PRIORITY_COLOR: Record<string, string> = { LOW: '#667085', MEDIUM: '#B54708', HIGH: '#D92D20', URGENT: '#912018' };
const OUTCOME_LABEL: Record<string, string> = {
  ANSWERED: 'Answered', REFUND_PROCESSED_VIA_PAYMENT_FLOW: 'A refund was processed', RETURN_APPROVED_VIA_RETURNS_FLOW: 'A return was approved',
  REPLACEMENT_ARRANGED: 'A replacement was arranged', DELIVERY_CONFIRMED: 'The delivery was confirmed', NOT_ELIGIBLE: 'Not eligible', NO_ACTION_NEEDED: 'No action was needed',
  CUSTOMER_WITHDREW: 'You withdrew it', DUPLICATE_OF_ANOTHER_CASE: 'A duplicate of another case',
};
const EVENT_LABEL: Record<string, string> = {
  CREATED: 'Case opened', MESSAGE_ADDED: 'Message added', STATUS_CHANGED: 'Status changed', ESCALATION_REQUESTED: 'Review by a person requested',
  ESCALATION_ACCEPTED: 'A support executive took the case', ASSIGNED: 'Assigned to the support team', RESOLVED: 'Resolved', REOPENED: 'Reopened', NOTE_ADDED: 'Internal note',
};

export const label = (s: string) => s.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
export const outcomeLabel = (o?: string | null) => (o ? OUTCOME_LABEL[o] ?? label(o) : '');
export const when = (iso?: string | null) => (iso ? new Date(iso).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—');

export const StatusChip = ({ status }: { status: CaseStatus }) => (
  <Chip size="small" color={STATUS_COLOR[status]} label={label(status)} data-testid="case-status" data-status={status} sx={{ fontWeight: 800 }} />
);

export const PriorityChip = ({ priority }: { priority: string }) => (
  <Chip size="small" variant="outlined" label={priority} data-testid="case-priority" sx={{ fontWeight: 800, borderColor: PRIORITY_COLOR[priority], color: PRIORITY_COLOR[priority] }} />
);

/**
 * The most important honesty rule of the support UI: asking for a person is a REQUEST. It is shown as accepted only when the backend says a support
 * executive actually took the case.
 */
export const EscalationStatus = ({ escalation, resolved }: { escalation: EscalationView; resolved?: boolean }) => {
  if (escalation.state === 'NONE') return null;
  const accepted = escalation.state === 'ACCEPTED';
  return (
    <Stack direction="row" spacing={0.75} alignItems="center" data-testid="support-escalation" data-escalation={escalation.state}>
      {accepted ? <SupportAgent fontSize="small" color="success" /> : <PersonSearch fontSize="small" color={resolved ? 'disabled' : 'warning'} />}
      <Typography variant="body2" fontWeight={700}>
        {accepted ? 'A support executive has taken this case.' : 'A person has been asked to review this case. Nobody has taken it yet.'}
      </Typography>
    </Stack>
  );
};

export const CaseTimeline = ({ events }: { events: TimelineEvent[] }) => (
  <Box component="ol" sx={{ listStyle: 'none', m: 0, p: 0, borderLeft: '2px solid', borderColor: 'divider', ml: 0.75 }} data-testid="support-timeline">
    {events.map((e, i) => (
      <Box component="li" key={`${e.type}-${e.at}-${i}`} sx={{ pl: 1.75, pb: 1.25, position: 'relative' }}>
        <Box sx={{ position: 'absolute', left: -6, top: 5, width: 10, height: 10, borderRadius: '50%', bgcolor: e.type === 'RESOLVED' ? 'success.main' : 'primary.main' }} />
        <Typography variant="body2" fontWeight={700}>{EVENT_LABEL[e.type] ?? label(e.type)}{e.fromStatus && e.toStatus ? `: ${label(e.fromStatus)} to ${label(e.toStatus)}` : ''}</Typography>
        {e.detail && <Typography variant="caption" color="text.secondary" display="block">{e.detail}</Typography>}
        <Typography variant="caption" color="text.disabled">{when(e.at)}</Typography>
      </Box>
    ))}
  </Box>
);

export const ResolvedNote = ({ c }: { c: CaseView }) => (
  c.status !== 'RESOLVED' || !c.resolutionOutcome ? null : (
    <Stack direction="row" spacing={0.75} alignItems="flex-start" data-testid="support-resolved">
      <CheckCircle fontSize="small" color="success" />
      <Box>
        <Typography variant="body2" fontWeight={700}>Outcome: {outcomeLabel(c.resolutionOutcome)}</Typography>
        {c.resolutionNote && <Typography variant="body2" color="text.secondary">Note from support: {c.resolutionNote}</Typography>}
      </Box>
    </Stack>
  )
);

/** A compact card: the case number comes from the system, never from the model. */
export const SupportCaseCard = ({ c, onOpen, created }: { c: CaseView; onOpen?: () => void; created?: boolean }) => (
  <Paper variant="outlined" data-testid="support-case-card" sx={{ p: 1.75, borderColor: created ? 'success.main' : undefined }}>
    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.75 }}>
      <SupportAgent color="primary" fontSize="small" />
      <Typography variant="subtitle2" fontWeight={900} data-testid="support-case-number">{c.caseNumber}</Typography>
      <StatusChip status={c.status} />
      <PriorityChip priority={c.priority} />
    </Stack>
    <Typography variant="body2" fontWeight={700}>{c.subject}</Typography>
    <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }}>
      {c.orderNumber ? `Order ${c.orderNumber} · ` : ''}{label(c.category)} · opened {when(c.createdAt)} · updated {when(c.updatedAt)}
    </Typography>
    <Typography variant="body2" sx={{ mb: 0.75 }}>{STATUS_TEXT[c.status]}.</Typography>
    <Stack spacing={0.75}>
      <EscalationStatus escalation={c.escalation} resolved={c.status === 'RESOLVED'} />
      <ResolvedNote c={c} />
      {c.status === 'WAITING_FOR_CUSTOMER' && (
        <Stack direction="row" spacing={0.75} alignItems="center"><HourglassTop fontSize="small" color="warning" /><Typography variant="body2" fontWeight={700}>Support is waiting for your reply.</Typography></Stack>
      )}
    </Stack>
    {c.timeline && c.timeline.length > 0 && <Box sx={{ mt: 1.25 }}><CaseTimeline events={c.timeline.slice(-4)} /></Box>}
    {onOpen && <Chip size="small" label="Open case" clickable onClick={onOpen} sx={{ mt: 0.5 }} data-testid="support-open-case" />}
  </Paper>
);

export const SupportCaseList = ({ cases, onOpen }: { cases: CaseView[]; onOpen?: (c: CaseView) => void }) => (
  cases.length === 0 ? <Typography color="text.secondary" data-testid="support-empty">You have no support cases.</Typography> : (
    <Stack spacing={1} data-testid="support-case-list">
      {cases.map((c) => <SupportCaseCard key={c.id} c={c} onOpen={onOpen ? () => onOpen(c) : undefined} />)}
    </Stack>
  )
);
