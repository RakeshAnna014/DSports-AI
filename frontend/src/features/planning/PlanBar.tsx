import { useState } from 'react';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, FormControl, InputLabel, MenuItem, Select, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { planningApi, planningError, type Plan, type PlanStatus } from '@/api/planning';
import { PlanStatusChip, ScopeBanner, day, pretty, usePlanning, when } from './parts';

const REASON: PlanStatus[] = ['RETURNED', 'REJECTED'];
const LABEL: Partial<Record<PlanStatus, string>> = { SUBMITTED: 'Submit for review', UNDER_REVIEW: 'Start review', APPROVED: 'Approve', PUBLISHED: 'Publish', RETURNED: 'Return for revision', REJECTED: 'Reject', DRAFT: 'Withdraw' };

const NewPlan = ({ onClose, onCreated }: { onClose: () => void; onCreated: (p: Plan) => void }) => {
  const { source, filters } = usePlanning();
  const [f, setF] = useState({ name: '', period: 'HORIZON', fy: '', number: '' });
  const create = useMutation({
    mutationFn: () => planningApi.createPlan({ name: f.name, source, period: f.period, fy: f.fy ? Number(f.fy) : undefined, number: f.number ? Number(f.number) : undefined,
      scope: { sport: filters.sport, category: filters.category, region: filters.region, stores: filters.store } }),
    onSuccess: (p) => { onCreated(p); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="new-plan-dialog">
      <DialogTitle>New sales plan</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <ScopeBanner source={source} />
        <TextField size="small" label="Plan name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} inputProps={{ 'data-testid': 'plan-name' }} />
        <Stack direction="row" spacing={1.5}>
          <FormControl size="small" sx={{ minWidth: 190 }}><InputLabel id="pp-l">Period</InputLabel>
            <Select labelId="pp-l" label="Period" value={f.period} onChange={(e) => setF({ ...f, period: e.target.value })} SelectDisplayProps={{ 'data-testid': 'plan-period' } as React.HTMLAttributes<HTMLDivElement>}>
              <MenuItem value="HORIZON">Whole forecast horizon</MenuItem><MenuItem value="FY">Financial year</MenuItem><MenuItem value="Q">Financial quarter</MenuItem><MenuItem value="M">Financial month</MenuItem></Select></FormControl>
          {f.period !== 'HORIZON' && <TextField size="small" type="number" label="Financial year (start)" value={f.fy} onChange={(e) => setF({ ...f, fy: e.target.value })} sx={{ width: 170 }} />}
          {(f.period === 'Q' || f.period === 'M') && <TextField size="small" type="number" label={f.period === 'Q' ? 'Quarter 1-4' : 'Month 1-12'} value={f.number} onChange={(e) => setF({ ...f, number: e.target.value })} sx={{ width: 130 }} />}
        </Stack>
        <Typography variant="caption" color="text.secondary">The plan copies the current forecast for the chosen period and the filters now selected (sport, category, region, store). The model forecast is frozen in the plan; targets and manager forecasts are separate columns.</Typography>
        {create.isError && <Alert severity="warning" data-testid="plan-error">{planningError(create.error)}</Alert>}
      </Stack></DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={f.name.trim().length < 3 || create.isPending} onClick={() => create.mutate()} data-testid="plan-create">Create plan</Button></DialogActions>
    </Dialog>
  );
};

/** The selected plan: its status, who can do what next (the server decides and checks again), revision, audit. */
const PlanBar = () => {
  const { source, planId, setPlanId, roles } = usePlanning();
  const qc = useQueryClient();
  const plans = useQuery({ queryKey: ['pln', 'plans'], queryFn: () => planningApi.plans(), retry: false });
  const plan = useQuery({ queryKey: ['pln', 'plan', planId], queryFn: () => planningApi.plan(planId as string), enabled: !!planId, retry: false });
  const [target, setTarget] = useState<PlanStatus | null>(null);
  const [reason, setReason] = useState('');
  const [creating, setCreating] = useState(false);
  const [audit, setAudit] = useState(false);
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['pln'] }); };
  const move = useMutation({ mutationFn: () => planningApi.transition(planId as string, target as PlanStatus, reason.trim() || undefined, plan.data!.version), onSuccess: () => { setTarget(null); setReason(''); refresh(); } });
  const revise = useMutation({ mutationFn: () => planningApi.revise(planId as string), onSuccess: (p) => { setPlanId(p.id); refresh(); } });
  const log = useQuery({ queryKey: ['pln', 'audit', planId, audit], queryFn: () => planningApi.planAudit(planId as string), enabled: audit && !!planId, retry: false });
  const mine = (plans.data ?? []).filter((p) => p.source === source);
  const p = plan.data;
  return (
    <Box data-testid="plan-bar">
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <FormControl size="small" sx={{ minWidth: 260 }}>
          <InputLabel id="plan-l">Plan</InputLabel>
          <Select labelId="plan-l" label="Plan" value={mine.some((x) => x.id === planId) ? planId : ''} onChange={(e) => setPlanId(e.target.value || undefined)} SelectDisplayProps={{ 'data-testid': 'plan-select' } as React.HTMLAttributes<HTMLDivElement>}>
            <MenuItem value="">No plan selected</MenuItem>
            {mine.map((x) => <MenuItem key={x.id} value={x.id}>{x.name} — {pretty(x.status)}{x.revision > 1 ? ` (rev ${x.revision})` : ''}</MenuItem>)}
          </Select>
        </FormControl>
        <Button size="small" variant="outlined" onClick={() => setCreating(true)} data-testid="plan-new">New plan</Button>
        {p && (
          <>
            <PlanStatusChip status={p.status} />
            <Typography variant="caption" color="text.secondary" data-testid="plan-meta">{p.fy_label} · {day(p.first_week)} to {day(p.last_week)} · version {p.version}{p.revision > 1 ? ` · revision ${p.revision}` : ''}</Typography>
            {(p.allowed_next ?? []).map((s) => (
              <Button key={s} size="small" variant={s === 'APPROVED' || s === 'PUBLISHED' ? 'contained' : 'outlined'} color={s === 'REJECTED' ? 'error' : s === 'RETURNED' ? 'warning' : 'primary'}
                onClick={() => setTarget(s)} data-testid={`plan-to-${s}`}>{LABEL[s] ?? pretty(s)}</Button>))}
            {(p.status === 'APPROVED' || p.status === 'PUBLISHED') && roles.length > 0 && <Button size="small" onClick={() => revise.mutate()} disabled={revise.isPending} data-testid="plan-revise">Create a revision</Button>}
            <Button size="small" onClick={() => setAudit(true)} data-testid="plan-audit">History</Button>
          </>
        )}
      </Stack>
      {revise.isError && <Alert severity="warning" sx={{ mt: 1 }}>{planningError(revise.error)}</Alert>}
      {creating && <NewPlan onClose={() => setCreating(false)} onCreated={(x) => { setPlanId(x.id); refresh(); }} />}
      <Dialog open={!!target} onClose={() => setTarget(null)} data-testid="transition-dialog">
        <DialogTitle>{target ? LABEL[target] ?? pretty(target) : ''}</DialogTitle>
        <DialogContent>
          {target === 'APPROVED' && <Alert severity="warning" sx={{ mb: 1 }}>Approving freezes the plan: every line's approved forecast becomes the manager's forecast (or the model's). Targets stay targets. Approval orders, publishes and prices nothing.</Alert>}
          {target === 'PUBLISHED' && <Alert severity="info" sx={{ mb: 1 }}>Publishing makes this approved plan the plan of record for reporting and inventory planning. No external system is changed.</Alert>}
          <TextField fullWidth multiline minRows={2} size="small" sx={{ mt: 0.5 }} label={target && REASON.includes(target) ? 'Reason (required)' : 'Note (optional)'} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ 'data-testid': 'transition-reason' }} />
          {move.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="transition-error">{planningError(move.error)}</Alert>}
        </DialogContent>
        <DialogActions><Button onClick={() => setTarget(null)}>Cancel</Button>
          <Button variant="contained" disabled={move.isPending || (!!target && REASON.includes(target) && !reason.trim())} onClick={() => move.mutate()} data-testid="transition-confirm">Confirm</Button></DialogActions>
      </Dialog>
      <Dialog open={audit} onClose={() => setAudit(false)} maxWidth="md" fullWidth data-testid="audit-dialog">
        <DialogTitle>Plan history</DialogTitle>
        <DialogContent>{(log.data ?? []).map((a) => <Typography key={a.id} variant="body2" data-testid="audit-row"><Typography component="span" variant="caption" color="text.secondary">{when(a.ts)} · {a.roles || 'system'} {a.actor_hash.slice(0, 6)} · </Typography><b>{pretty(a.action)}</b>{a.reason ? `: ${a.reason}` : ''}</Typography>)}
          {log.data?.length === 0 && <Typography variant="body2" color="text.secondary">Nothing recorded yet.</Typography>}</DialogContent>
        <DialogActions><Button onClick={() => setAudit(false)}>Close</Button></DialogActions>
      </Dialog>
    </Box>
  );
};

export default PlanBar;
