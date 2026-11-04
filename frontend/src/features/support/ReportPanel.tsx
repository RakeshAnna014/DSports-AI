import { useState } from 'react';
import { Alert, Box, Chip, Paper, Skeleton, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { supportError } from '@/api/support';
import { supportStaffAi, type ReportParams, type ReportRange } from '@/api/supportStaffAi';
import { label } from './parts';

const RANGES: { value: ReportRange; text: string }[] = [
  { value: 'today', text: 'Today' }, { value: 'yesterday', text: 'Yesterday' }, { value: 'last7', text: 'Last 7 days' }, { value: 'last30', text: 'Last 30 days' }, { value: 'custom', text: 'Custom' },
];

const Metric = ({ id, title, value, hint }: { id: string; title: string; value: React.ReactNode; hint: string }) => (
  <Paper variant="outlined" sx={{ p: 1.25, minWidth: 140, flex: 1 }} data-testid={`report-${id}`}>
    <Typography variant="caption" color="text.secondary" fontWeight={700} sx={{ textTransform: 'uppercase' }}>{title}</Typography>
    <Typography variant="h5" fontWeight={900}>{value}</Typography>
    <Typography variant="caption" color="text.secondary">{hint}</Typography>
  </Paper>
);

/** Date-filtered ticket report plus AI insights. Numbers are aggregated by the backend (never in the browser); insights label every statement OBSERVATION or HYPOTHESIS. */
const ReportPanel = () => {
  const [range, setRange] = useState<ReportRange>('last7');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const params: ReportParams = { range, from, to };
  const ready = range !== 'custom' || (!!from && !!to && from <= to);
  const report = useQuery({ queryKey: ['support', 'staff', 'report', params], queryFn: () => supportStaffAi.report(params), enabled: ready, retry: false });
  const insights = useQuery({ queryKey: ['support', 'staff', 'insights', params], queryFn: () => supportStaffAi.insights(params), enabled: ready, retry: false, staleTime: 60_000 });
  const r = report.data;
  const max = Math.max(1, ...(r?.daily.map((d) => Math.max(d.created, d.resolved)) ?? [1]));
  return (
    <Paper variant="outlined" sx={{ p: 1.5, mb: 2 }} data-testid="support-report">
      <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <Typography variant="subtitle1" fontWeight={900}>Ticket report</Typography>
        <ToggleButtonGroup size="small" exclusive value={range} onChange={(_, v: ReportRange | null) => v && setRange(v)} aria-label="Report period">
          {RANGES.map((x) => <ToggleButton key={x.value} value={x.value} data-testid={`range-${x.value}`}>{x.text}</ToggleButton>)}
        </ToggleButtonGroup>
        {range === 'custom' && (
          <>
            <TextField size="small" type="date" label="From" InputLabelProps={{ shrink: true }} value={from} onChange={(e) => setFrom(e.target.value)} inputProps={{ 'data-testid': 'report-from' }} />
            <TextField size="small" type="date" label="To (inclusive)" InputLabelProps={{ shrink: true }} value={to} onChange={(e) => setTo(e.target.value)} inputProps={{ 'data-testid': 'report-to' }} />
          </>
        )}
      </Stack>
      {range === 'custom' && !ready && <Alert severity="info" data-testid="report-custom-hint">Choose a start date and an end date that is not before it.</Alert>}
      {report.isLoading && ready && <Skeleton variant="rounded" height={90} />}
      {report.isError && <Alert severity="warning" data-testid="report-error">{supportError(report.error).message}</Alert>}
      {r && (
        <>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.75 }} data-testid="report-period">
            {r.from === r.to ? r.from : `${r.from} to ${r.to}`} · days counted in {r.timeZone}
          </Typography>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            <Metric id="created" title="Created" value={r.createdInPeriod} hint="opened in the period" />
            <Metric id="resolved" title="Resolved" value={r.resolvedInPeriod} hint="resolved in the period" />
            <Metric id="reopened" title="Reopened" value={r.reopenedInPeriod} hint="reopened in the period" />
            <Metric id="escalated" title="Person requested" value={r.escalatedInPeriod} hint="requests, not acceptances" />
            <Metric id="cohort" title="Still unresolved" value={r.createdInPeriodStillUnresolved} hint={`of the ${r.createdInPeriod} created in the period`} />
            <Metric id="now" title="Unresolved now" value={r.unresolvedNow} hint={`${r.openNow} open · ${r.inProgressNow} in progress · ${r.unassignedNow} unassigned`} />
          </Stack>
          <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 1 }} data-testid="report-categories">
            {Object.entries(r.createdByCategory).map(([k, n]) => <Chip key={k} size="small" variant="outlined" label={`${label(k)}: ${n}`} />)}
            {r.createdInPeriod === 0 && <Typography variant="body2" color="text.secondary" data-testid="report-empty">No tickets were created in this period.</Typography>}
          </Stack>
          {r.daily.length > 1 && (
            <Box sx={{ mt: 1.25 }} data-testid="report-trend" aria-label="Daily created and resolved">
              <Stack direction="row" alignItems="flex-end" spacing={0.5} sx={{ height: 70, overflowX: 'auto' }}>
                {r.daily.map((d) => (
                  <Box key={d.date} title={`${d.date}: ${d.created} created, ${d.resolved} resolved`} sx={{ display: 'flex', alignItems: 'flex-end', gap: '2px', minWidth: 14 }} data-testid="trend-day">
                    <Box sx={{ width: 6, height: `${(d.created / max) * 100}%`, minHeight: d.created ? 2 : 0, bgcolor: 'primary.main' }} />
                    <Box sx={{ width: 6, height: `${(d.resolved / max) * 100}%`, minHeight: d.resolved ? 2 : 0, bgcolor: 'success.main' }} />
                  </Box>
                ))}
              </Stack>
              <Typography variant="caption" color="text.secondary">Blue: created · Green: resolved, per day. {r.slaNote}</Typography>
            </Box>
          )}
        </>
      )}
      <Box sx={{ mt: 1.5 }} data-testid="support-insights">
        <Typography variant="subtitle2" fontWeight={900}>AI insights</Typography>
        {insights.isLoading && ready && <Skeleton variant="rounded" height={60} />}
        {insights.isError && <Alert severity="warning" data-testid="insights-error">{supportError(insights.error).message}</Alert>}
        {insights.data && (
          <Stack spacing={0.5} sx={{ mt: 0.5 }}>
            {insights.data.items.map((it, i) => (
              <Typography key={i} component="div" variant="body2" data-testid="insight-item">
                <Chip size="small" label={it.label} color={it.label === 'OBSERVATION' ? 'success' : 'warning'} variant="outlined" sx={{ mr: 0.75, height: 20 }} />
                {it.text} <Typography component="span" variant="caption" color="text.secondary">({it.denominator}{it.to_confirm ? `; to confirm: ${it.to_confirm}` : ''})</Typography>
              </Typography>
            ))}
            <Typography variant="body2" data-testid="insight-chatbot"><b>Assistant effectiveness:</b> {insights.data.chatbot.note}</Typography>
            {insights.data.recommendations.map((x, i) => <Typography key={i} variant="body2" data-testid="insight-recommendation"><b>Suggestion ({x.label.toLowerCase()}):</b> {x.text} <Typography component="span" variant="caption" color="text.secondary">({x.basis})</Typography></Typography>)}
            <Typography variant="caption" color="text.secondary">{insights.data.limitations.join(' ')}</Typography>
          </Stack>
        )}
      </Box>
    </Paper>
  );
};

export default ReportPanel;
