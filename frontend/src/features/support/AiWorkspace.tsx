import { useState } from 'react';
import {
  Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, FormControlLabel, InputLabel, MenuItem, Paper, Select, Skeleton, Stack, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { supportApi, supportError } from '@/api/support';
import { supportStaffAi, type ActionHistory, type PlanAction, type StaffPlan } from '@/api/supportStaffAi';
import { label, outcomeLabel, when } from './parts';

const STATE_COLOR = { SUGGESTED: 'default', ATTEMPTED: 'info', SUCCEEDED: 'success', FAILED: 'error' } as const;

/** Runs one confirmed action through the EXISTING staff endpoint with the staff member's own token. The backend decides; this page only reports what it answered. */
const execute = (ref: string, a: PlanAction, body: Record<string, unknown>) => {
  const v = body.expectedVersion as number | undefined;
  switch (a.kind) {
    case 'ASSIGN_SELF': return supportApi.staff.assign(ref, { takeIt: true, expectedVersion: v });
    case 'SET_STATUS': return supportApi.staff.status(ref, String(body.status), body.note ? String(body.note) : undefined, v);
    case 'REPLY_CUSTOMER': return supportApi.staff.reply(ref, String(body.body), v);
    case 'ADD_NOTE': return supportApi.staff.note(ref, String(body.body), v);
    case 'RESOLVE': return supportApi.staff.resolve(ref, String(body.outcome), String(body.note), (body.evidenceRefs as string[]) ?? [], v);
  }
};

const ActionCard = ({ plan, action, history, onDone }: { plan: StaffPlan; action: PlanAction; history?: ActionHistory; onDone: () => void }) => {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState(action.kind === 'REPLY_CUSTOMER' ? String(action.body.body ?? '') : '');
  const [outcome, setOutcome] = useState('');
  const [note, setNote] = useState('');
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const resolve = action.kind === 'RESOLVE';
  const verified = !resolve || (action.must_verify ?? []).every((m) => checked[m]);
  const ready = resolve ? !!outcome && note.trim().length > 0 && verified : action.kind === 'REPLY_CUSTOMER' ? text.trim().length > 0 : true;
  const state = history?.state ?? action.state;

  const run = useMutation({
    mutationFn: async () => {
      const body: Record<string, unknown> = { ...action.body };
      if (action.kind === 'REPLY_CUSTOMER') body.body = text;
      if (resolve) { body.outcome = outcome; body.note = note; }
      await supportStaffAi.record(plan.caseRef, action.id, 'ATTEMPTED');
      try {
        await execute(plan.caseRef, action, body);
      } catch (e) {
        const err = supportError(e);
        await supportStaffAi.record(plan.caseRef, action.id, 'FAILED', { httpStatus: err.status ?? 0, code: err.code });
        throw e;
      }
      await supportStaffAi.record(plan.caseRef, action.id, 'SUCCEEDED', { httpStatus: 200 });
    },
    onSuccess: () => { setResult({ ok: true, message: 'The backend accepted it. The ticket has been re-read below.' }); setOpen(false); onDone(); },
    onError: (e) => { setResult({ ok: false, message: supportError(e).code === 'OPTIMISTIC_LOCKING_CONFLICT' ? 'Someone else changed this ticket. Refresh the plan and try again.' : supportError(e).message }); setOpen(false); onDone(); },
  });

  return (
    <Paper variant="outlined" sx={{ p: 1.25 }} data-testid="ai-action" data-kind={action.kind}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography variant="body2" fontWeight={800} sx={{ flex: 1, minWidth: 180 }}>{action.label}</Typography>
        <Chip size="small" label={state} color={STATE_COLOR[state]} data-testid="ai-action-state" />
      </Stack>
      <Typography variant="caption" color="text.secondary" display="block">Runs: {action.operation}</Typography>
      {action.kind === 'REPLY_CUSTOMER' && (
        <TextField size="small" multiline minRows={4} fullWidth sx={{ mt: 1 }} label="Reply (edit before sending)" value={text} onChange={(e) => setText(e.target.value)} inputProps={{ 'data-testid': 'ai-reply-text' }}
          helperText={`Built from: ${(action.draft_basis ?? []).join('; ')}. ${plan.customerDraft.never_includes ? `Never includes: ${plan.customerDraft.never_includes}.` : ''}`} />
      )}
      {resolve && (
        <Stack spacing={1} sx={{ mt: 1 }}>
          <Alert severity="info" icon={false}>{action.blocked_reason}</Alert>
          {(action.must_verify ?? []).map((m) => (
            <FormControlLabel key={m} control={<Checkbox size="small" checked={!!checked[m]} onChange={(e) => setChecked((c) => ({ ...c, [m]: e.target.checked }))} inputProps={{ 'data-testid': 'ai-verify' } as React.InputHTMLAttributes<HTMLInputElement>} />}
              label={<Typography variant="body2">I confirmed: {m}</Typography>} />
          ))}
          <FormControl size="small" sx={{ maxWidth: 320 }}>
            <InputLabel id="ai-outcome-label">Outcome (you choose)</InputLabel>
            <Select labelId="ai-outcome-label" label="Outcome (you choose)" value={outcome} onChange={(e) => setOutcome(e.target.value)} SelectDisplayProps={{ 'data-testid': 'ai-outcome' } as React.HTMLAttributes<HTMLDivElement>}>
              {(action.outcomes ?? []).map((o) => <MenuItem key={o} value={o}>{outcomeLabel(o)}</MenuItem>)}
            </Select>
          </FormControl>
          <TextField size="small" multiline minRows={2} label="Resolution note (what was verified and decided)" value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ 'data-testid': 'ai-resolve-note' }} />
        </Stack>
      )}
      {result && <Alert severity={result.ok ? 'success' : 'warning'} sx={{ mt: 1 }} data-testid="ai-action-result">{result.message}</Alert>}
      <Box sx={{ mt: 1 }}>
        <Button size="small" variant={resolve ? 'contained' : 'outlined'} color={resolve ? 'success' : 'primary'} disabled={!ready || run.isPending || state === 'ATTEMPTED'} onClick={() => setOpen(true)} data-testid="ai-action-run">{resolve ? 'Review and resolve' : 'Review and run'}</Button>
      </Box>
      {history && history.trail.length > 0 && (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }} data-testid="ai-action-trail">
          {history.trail.map((t) => `${t.state} ${when(t.at)}`).join(' → ')}
        </Typography>
      )}
      <Dialog open={open} onClose={() => setOpen(false)} data-testid="ai-confirm">
        <DialogTitle>Confirm: {action.label}</DialogTitle>
        <DialogContent><Typography variant="body2">{action.confirmation}</Typography>
          {action.kind === 'REPLY_CUSTOMER' && <Typography variant="body2" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>{text}</Typography>}</DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={run.isPending} onClick={() => run.mutate()} data-testid="ai-confirm-yes">Confirm</Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
};

const List = ({ title, items, testId }: { title: string; items: string[]; testId: string }) => (items.length === 0 ? null : (
  <Box data-testid={testId}>
    <Typography variant="caption" fontWeight={800}>{title}</Typography>
    <Box component="ul" sx={{ m: 0, pl: 2.25 }}>{items.map((s, i) => <Typography key={i} component="li" variant="body2">{s}</Typography>)}</Box>
  </Box>
));

/** Per-ticket AI workspace: a plan tailored to this ticket. Advice only; every action needs the staff member's confirmation and is recorded as suggested, attempted, succeeded or failed. */
const AiWorkspace = ({ reference }: { reference: string }) => {
  const qc = useQueryClient();
  const [asked, setAsked] = useState(false);
  const plan = useQuery({ queryKey: ['support', 'staff', 'plan', reference], queryFn: () => supportStaffAi.assist(reference), enabled: asked, retry: false, staleTime: 0, refetchOnWindowFocus: false });
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['support'] }); };
  const p = plan.data;
  const byId = new Map((p?.history ?? []).map((h) => [h.actionId, h]));
  return (
    <Paper variant="outlined" sx={{ p: 1.5 }} data-testid="ai-workspace">
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography variant="subtitle2" fontWeight={900} sx={{ flex: 1 }}>AI resolution plan</Typography>
        <Button size="small" variant="contained" disabled={plan.isFetching} onClick={() => (asked ? void plan.refetch() : setAsked(true))} data-testid="ai-plan-open">{asked ? 'Refresh plan' : 'Suggest a plan'}</Button>
      </Stack>
      <Typography variant="caption" color="text.secondary" display="block">Advice only. The assistant never resolves, replies, refunds or changes a ticket by itself.</Typography>
      {plan.isFetching && <Skeleton variant="rounded" height={120} sx={{ mt: 1 }} />}
      {plan.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="ai-plan-error">{supportError(plan.error).message}</Alert>}
      {p && !plan.isFetching && (
        <Stack spacing={1.25} sx={{ mt: 1 }} data-testid="ai-plan" data-category={p.category}>
          <Typography variant="body2"><b>{p.playbook}.</b> {p.problem}</Typography>
          <Box data-testid="ai-known">
            <Typography variant="caption" fontWeight={800}>Verified facts</Typography>
            <Box component="ul" sx={{ m: 0, pl: 2.25 }}>{p.known.map((k, i) => <Typography key={i} component="li" variant="body2">{k.statement} <Typography component="span" variant="caption" color="text.secondary">[{label(k.source)}]</Typography></Typography>)}</Box>
          </Box>
          <List title="Not known yet" items={p.uncertain} testId="ai-uncertain" />
          <Box data-testid="ai-causes">
            <Typography variant="caption" fontWeight={800}>Possible causes (unconfirmed hypotheses)</Typography>
            {p.likelyCause.hypotheses.map((h, i) => (
              <Typography key={i} component="div" variant="body2"><Chip size="small" color="warning" variant="outlined" label={`HYPOTHESIS · ${h.confidence}`} sx={{ mr: 0.75, height: 20 }} />{h.statement} <Typography component="span" variant="caption" color="text.secondary">Basis: {h.basis}. To confirm: {h.to_confirm}.</Typography></Typography>
            ))}
          </Box>
          <Box data-testid="ai-steps">
            <Typography variant="caption" fontWeight={800}>Suggested steps</Typography>
            <Box component="ol" sx={{ m: 0, pl: 2.5 }}>{p.steps.map((s) => <Typography key={s.order} component="li" variant="body2">{s.text}</Typography>)}</Box>
          </Box>
          {!p.live.available && <Alert severity="info" icon={false} data-testid="ai-live-note">{p.live.reason}</Alert>}
          {p.escalation.recommended && <Alert severity="warning" data-testid="ai-escalation">Consider escalating: {p.escalation.reason}</Alert>}
          <List title="Escalate when" items={p.escalation.conditions} testId="ai-escalate-when" />
          {p.engineering.groups && p.engineering.groups.length > 0 && (
            <Alert severity="info" icon={false} data-testid="ai-engineering">Engineering signal (candidate only): {p.engineering.groups.map((g) => `${g.reference} · ${g.title} (${g.count})`).join('; ')}. {p.engineering.note}</Alert>
          )}
          {p.knowledge.sources.length > 0 && <List title={`Knowledge (${p.knowledge.note ?? 'sample documents'})`} items={p.knowledge.sources.map((s) => `${s.title}: ${s.snippet}`)} testId="ai-knowledge" />}
          <Stack spacing={1}>
            <Typography variant="caption" fontWeight={800}>Actions you can confirm</Typography>
            {p.actions.map((a) => <ActionCard key={`${a.id}-${p.version}`} plan={p} action={a} history={byId.get(a.id)} onDone={() => { refresh(); void plan.refetch(); }} />)}
          </Stack>
          <Typography variant="caption" color="text.secondary">{p.limitations.join(' ')}</Typography>
        </Stack>
      )}
    </Paper>
  );
};

export default AiWorkspace;
