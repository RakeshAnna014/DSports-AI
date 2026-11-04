import { useEffect, useState } from 'react';
import { Alert, Box, Button, Container, Paper, Skeleton, Stack, TextField, Typography } from '@mui/material';
import { SmartToy } from '@mui/icons-material';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import RaiseTicketDialog from './RaiseTicketDialog';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { supportApi, supportError } from '@/api/support';
import { useAuthStore } from '@/store/authStore';
import { useAiStore } from '@/store/aiStore';
import { fromApi, type CaseView } from '@/types/support';
import { CaseTimeline, SupportCaseCard, SupportCaseList, when } from './parts';

const toView = (s: Awaited<ReturnType<typeof supportApi.mine>>[number]): CaseView => ({ ...s });

const CaseDetail = ({ reference }: { reference: string }) => {
  const qc = useQueryClient();
  const detail = useQuery({ queryKey: ['support', 'case', reference], queryFn: () => supportApi.get(reference), retry: false });
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const openForCase = useAiStore((s) => s.openForCase);
  const assistantTurns = useAiStore((s) => s.entries.length);
  // When the assistant changes the case (a confirmed message, review request or reopen), this page shows the backend's new state.
  useEffect(() => {
    if (assistantTurns > 0) void qc.invalidateQueries({ queryKey: ['support'] });
  }, [assistantTurns, qc]);
  const done = (msg?: string) => () => { setText(''); setError(null); void qc.invalidateQueries({ queryKey: ['support'] }); if (msg) setError(null); };
  const fail = (e: unknown) => setError(supportError(e).message);
  const message = useMutation({ mutationFn: () => supportApi.message(reference, text), onSuccess: done(), onError: fail });
  const escalate = useMutation({ mutationFn: () => supportApi.escalate(reference, text || 'I would like a person to review this case.'), onSuccess: done(), onError: fail });
  const reopen = useMutation({ mutationFn: () => supportApi.reopen(reference, text || 'The problem is not solved.'), onSuccess: done(), onError: fail });

  if (detail.isLoading) return <Skeleton variant="rounded" height={220} />;
  if (detail.isError) return <Alert severity="warning" data-testid="support-detail-error">{supportError(detail.error).message}</Alert>;
  const raw = detail.data!;
  const c = fromApi(raw);
  const resolved = c.status === 'RESOLVED';
  return (
    <Stack spacing={1.5} data-testid="support-detail">
      <SupportCaseCard c={c} />
      <Button variant="outlined" startIcon={<SmartToy />} onClick={() => void openForCase({ caseNumber: c.caseNumber, subject: c.subject })} data-testid="case-ai-help" sx={{ alignSelf: 'flex-start' }}>
        {resolved ? 'Ask the assistant about this case' : 'Get AI help with this case'}
      </Button>
      <Paper variant="outlined" sx={{ p: 1.5 }}>
        <Typography variant="subtitle2" fontWeight={800} sx={{ mb: 0.5 }}>What you told us</Typography>
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{raw.description}</Typography>
        {raw.investigationSummary && (
          <>
            <Typography variant="subtitle2" fontWeight={800} sx={{ mt: 1.25, mb: 0.5 }}>What the assistant checked when the case was opened</Typography>
            <Typography variant="body2" color="text.secondary">{raw.investigationSummary}</Typography>
          </>
        )}
        {raw.missingInformation.length > 0 && (
          <Typography variant="caption" display="block" sx={{ mt: 0.75 }} color="text.secondary">Not verified: {raw.missingInformation.join('; ')}</Typography>
        )}
      </Paper>
      <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="support-messages">
        <Typography variant="subtitle2" fontWeight={800} sx={{ mb: 0.75 }}>Messages</Typography>
        <Stack spacing={0.75}>
          {raw.messages.map((m) => (
            <Box key={m.id} sx={{ alignSelf: m.from === 'CUSTOMER' ? 'flex-end' : 'flex-start', maxWidth: '88%', bgcolor: m.from === 'CUSTOMER' ? 'primary.50' : 'action.hover', borderRadius: 2, px: 1.25, py: 0.75 }}>
              <Typography variant="caption" color="text.secondary">{m.from === 'CUSTOMER' ? 'You' : 'DSports support'} · {when(m.createdAt)}</Typography>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{m.body}</Typography>
            </Box>
          ))}
        </Stack>
      </Paper>
      <Paper variant="outlined" sx={{ p: 1.5 }}>
        <Typography variant="subtitle2" fontWeight={800} sx={{ mb: 0.75 }}>History</Typography>
        <CaseTimeline events={raw.timeline} />
      </Paper>
      {error && <Alert severity="warning" data-testid="support-action-error">{error}</Alert>}
      <Paper variant="outlined" sx={{ p: 1.5 }}>
        <TextField fullWidth multiline minRows={2} size="small" label={resolved ? 'Why it is not solved' : 'Your message'} value={text} onChange={(e) => setText(e.target.value)} inputProps={{ 'data-testid': 'support-text', maxLength: 2000 }} />
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
          {!resolved && <Button variant="contained" disabled={!text.trim() || message.isPending} onClick={() => message.mutate()} data-testid="support-send">Send message</Button>}
          {!resolved && c.escalation.state === 'NONE' && <Button variant="outlined" disabled={escalate.isPending} onClick={() => escalate.mutate()} data-testid="support-ask-person">Ask for a person to review</Button>}
          {resolved && c.canReopen && <Button variant="contained" disabled={!text.trim() || reopen.isPending} onClick={() => reopen.mutate()} data-testid="support-reopen">Reopen this case</Button>}
        </Stack>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }}>
          Asking for a person records a request. It does not mean anyone has taken the case yet; this page shows when someone has.
        </Typography>
      </Paper>
    </Stack>
  );
};

/** The customer's own support cases. The backend returns only this customer's cases; a case of anyone else is simply "not found". */
const MyCasesPage = () => {
  const { ref } = useParams();
  const nav = useNavigate();
  const [params, setParams] = useSearchParams();
  const [raisingLocal, setRaising] = useState(false);
  const raising = raisingLocal || params.get('new') === '1';   // ?new=1 lets other pages (account menu, footer) deep-link to the form
  const closeRaise = () => { setRaising(false); if (params.has('new')) { params.delete('new'); setParams(params, { replace: true }); } };
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const list = useQuery({ queryKey: ['support', 'mine'], queryFn: () => supportApi.mine(false), enabled: isAuthenticated, retry: false });

  if (!isAuthenticated) {
    return (
      <Container maxWidth="sm" sx={{ py: 6, textAlign: 'center' }} data-testid="support-signin">
        <Typography variant="h5" fontWeight={900} sx={{ mb: 1 }}>Support cases</Typography>
        <Typography color="text.secondary" sx={{ mb: 2 }}>Sign in to see your support cases.</Typography>
        <Button variant="contained" component={RouterLink} to="/login">Sign in</Button>
      </Container>
    );
  }
  return (
    <Container maxWidth="lg" sx={{ py: 3 }} data-testid="support-page">
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 2 }}>
        <Typography variant="h5" fontWeight={900} sx={{ flex: 1 }}>Support cases</Typography>
        <Button variant="contained" onClick={() => setRaising(true)} data-testid="raise-ticket">Raise a ticket</Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>You can also describe the problem to the assistant (Ask DSports AI) and it will ask you to confirm before opening a ticket.</Typography>
      <RaiseTicketDialog open={raising} onClose={closeRaise} onCreated={(n) => { closeRaise(); nav(`/support/${n}`); }} />
      {list.isError && <Alert severity="warning" data-testid="support-list-error">{supportError(list.error).message}</Alert>}
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '340px 1fr' }, gap: 2, alignItems: 'start' }}>
        <Box>
          {list.isLoading ? <Skeleton variant="rounded" height={160} /> : (
            <SupportCaseList cases={(list.data ?? []).map(toView)} onOpen={(c) => nav(`/support/${c.caseNumber}`)} />
          )}
        </Box>
        <Box>{ref ? <CaseDetail reference={ref} key={ref} /> : <Typography color="text.secondary" data-testid="support-pick">Choose a case to see its messages and history.</Typography>}</Box>
      </Box>
      <Typography variant="caption" color="text.disabled" display="block" sx={{ mt: 2 }}>
        Case numbers and statuses come from the DSports system. There is no official response-time commitment on this page.
      </Typography>
    </Container>
  );
};

export default MyCasesPage;
