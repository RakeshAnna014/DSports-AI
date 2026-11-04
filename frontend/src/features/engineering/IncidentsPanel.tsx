import { useState } from 'react';
import { useInfiniteQuery, useMutation } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Paper, Skeleton, Snackbar, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { engineeringApi, type EngIncident, type EngIncidentQuery, type EngIncidentRef } from '@/api/engineeringAi';
import { getApiErrorMessage } from '@/api/errors';
import { rangeQuery, isHistorical, type RangeState } from './TimeRange';
import IncidentReport from './IncidentReport';

const STATUS_COLOR: Record<EngIncident['status'], 'error' | 'warning' | 'default' | 'success'> = { NEW: 'error', INCREASING: 'warning', RECURRING: 'default', DECREASING: 'success' };
const PAGE = 10;

/** A real clipboard write, with a fallback for browsers that refuse it; the caller shows success or failure. */
export const copyText = async (text: string): Promise<boolean> => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // fall through to the legacy path
  }
  try {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(area);
    return ok;
  } catch {
    return false;
  }
};

const IncidentCard = ({ incident, refOf, historical }: { incident: EngIncident; refOf: (i: EngIncident) => EngIncidentRef; historical: boolean }) => {
  const [view, setView] = useState<'none' | 'report' | 'resolution'>('none');
  const [copyNote, setCopyNote] = useState<{ ok: boolean; text: string } | null>(null);
  const investigate = useMutation({ mutationFn: () => engineeringApi.investigateIncident(refOf(incident)) });
  const prompt = useMutation({ mutationFn: () => engineeringApi.incidentPrompt(refOf(incident)) });
  const report = investigate.data;
  const investigated = incident.investigation.status === 'INVESTIGATED' || !!report;

  const doInvestigate = (target: 'report' | 'resolution') => {
    setView(target);
    if (!report && !investigate.isPending) investigate.mutate();
  };
  const copy = async () => {
    if (!prompt.data) return;
    const ok = await copyText(prompt.data.prompt);
    setCopyNote(ok ? { ok, text: 'Prompt copied to the clipboard.' } : { ok, text: 'Copy was blocked by the browser. Select the prompt text and copy it manually.' });
  };
  const b = incident.score_breakdown;
  return (
    <Paper variant="outlined" sx={{ p: 1.5, borderColor: incident.status === 'NEW' || incident.status === 'INCREASING' ? 'warning.main' : undefined }} data-testid={`eng-incident-${incident.reference}`}>
      <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.5 }}>
        <Chip size="small" label={incident.reference} variant="outlined" sx={{ fontFamily: 'monospace' }} />
        <Chip size="small" color={STATUS_COLOR[incident.status]} label={incident.status.toLowerCase()} data-testid="eng-incident-status" />
        <Chip size="small" color={incident.level === 'ERROR' ? 'error' : 'warning'} label={incident.level} />
        <Chip size="small" label={incident.category.toLowerCase()} />
        <Chip size="small" color={incident.origin === 'real' ? 'default' : 'warning'} label={incident.origin ?? 'unknown'} />
        {historical && <Chip size="small" variant="outlined" label="historical window" />}
        <Chip size="small" color={investigated ? 'success' : 'default'} variant={investigated ? 'filled' : 'outlined'} label={investigated ? 'investigated' : 'not investigated'} data-testid="eng-investigation-status" />
        <Box sx={{ flexGrow: 1 }} />
        <Tooltip title={`Severity ${b.severity} + recency ${b.recency} + trend ${b.trend} + volume ${b.volume} (capped) + impact ${b.impact} (capped)`}><Typography variant="caption" fontWeight={700}>score {incident.score}</Typography></Tooltip>
      </Stack>
      <Typography variant="body2" fontWeight={700} sx={{ wordBreak: 'break-word' }}>{incident.title}</Typography>
      <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
        <b>{incident.count}</b> record(s){incident.previous_window_count !== null ? ` (previous window: ${incident.previous_window_count})` : ''} · first seen {incident.first_seen.replace('T', ' ').slice(0, 16)} · last seen {incident.last_seen.replace('T', ' ').slice(0, 16)} UTC · {incident.services.join(', ')}
        {incident.endpoints.length > 0 && ` · endpoint ${incident.endpoints.join(', ')}`}
      </Typography>
      <Tooltip title={incident.impact.label}>
        <Typography variant="caption" color="text.secondary" display="block" data-testid="eng-incident-impact">
          Estimated impact: about {incident.impact.affected_requests_estimate} request(s) affected, {incident.impact.http_5xx_records} HTTP 5xx record(s) (an estimate from correlation ids, not a verified customer count)
        </Typography>
      </Tooltip>
      {incident.correlation_ids.length > 0 && <Typography variant="caption" color="text.secondary" display="block" sx={{ fontFamily: 'monospace' }}>requests: {incident.correlation_ids.map((c) => c.slice(0, 8)).join(', ')}</Typography>}
      <Typography variant="caption" color="text.secondary" display="block">Supporting records: {incident.evidence_ids.map((i) => `L${i}`).join(', ')}</Typography>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
        <Button size="small" variant="contained" onClick={() => doInvestigate('report')} disabled={investigate.isPending} data-testid="eng-investigate">Investigate</Button>
        <Button size="small" variant="outlined" onClick={() => doInvestigate('resolution')} disabled={investigate.isPending} data-testid="eng-resolution-btn">Proposed resolution</Button>
        <Button size="small" variant="outlined" onClick={() => prompt.mutate()} disabled={prompt.isPending} data-testid="eng-gen-prompt">{prompt.isPending ? 'Generating…' : 'Generate coding prompt'}</Button>
        <Button size="small" variant="outlined" onClick={copy} disabled={!prompt.data} data-testid="eng-copy-prompt">Copy prompt</Button>
      </Stack>
      {investigate.isPending && <Skeleton variant="rounded" height={90} sx={{ mt: 1 }} data-testid="eng-incident-loading" />}
      {investigate.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="eng-incident-error">{getApiErrorMessage(investigate.error, 'The investigation could not be completed.')}</Alert>}
      {report && view === 'report' && <Box sx={{ mt: 1.5 }}><IncidentReport report={report} /></Box>}
      {report && view === 'resolution' && <Box sx={{ mt: 1.5 }}><IncidentReport report={report} only="resolution" /></Box>}
      {prompt.isError && <Alert severity="warning" sx={{ mt: 1 }} data-testid="eng-prompt-error">{getApiErrorMessage(prompt.error, 'The prompt could not be generated.')}</Alert>}
      {prompt.data && (
        <Box sx={{ mt: 1.5 }} data-testid="eng-prompt">
          <Typography variant="caption" color="text.secondary" display="block">{prompt.data.characters} characters · {prompt.data.warnings.join(' ')}</Typography>
          <TextField fullWidth multiline minRows={6} maxRows={18} value={prompt.data.prompt} slotProps={{ htmlInput: { readOnly: true, 'aria-label': 'coding prompt', style: { fontFamily: 'monospace', fontSize: 12 } } }} />
        </Box>
      )}
      <Snackbar open={!!copyNote} autoHideDuration={5000} onClose={() => setCopyNote(null)}>
        <Alert severity={copyNote?.ok ? 'success' : 'error'} onClose={() => setCopyNote(null)} data-testid="eng-copy-note">{copyNote?.text}</Alert>
      </Snackbar>
    </Paper>
  );
};

/** The ranked incident groups of the selected window: ten at a time, more on request. Nothing is silently dropped. */
const IncidentsPanel = ({ range, origin, refreshToken }: { range: RangeState; origin: '' | 'real' | 'synthetic'; refreshToken: number }) => {
  const rq = rangeQuery(range);
  const base: EngIncidentQuery | null = rq.query ? { ...rq.query, origin: origin || undefined, limit: PAGE } : null;
  const list = useInfiniteQuery({
    queryKey: ['eng', 'incidents', base, refreshToken],
    queryFn: ({ pageParam }) => engineeringApi.incidents({ ...base!, offset: pageParam }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.has_more ? last.offset + last.limit : undefined),
    enabled: !!base,
    retry: false,
  });
  const pages = list.data?.pages ?? [];
  const incidents = pages.flatMap((p) => p.incidents);
  const first = pages[0];
  const refOf = (i: EngIncident): EngIncidentRef => ({ signature: i.signature, since: first.window.since, until: first.window.until, origin: i.origin === 'real' || i.origin === 'synthetic' ? i.origin : undefined });
  return (
    <Stack spacing={1.5} data-testid="eng-incidents">
      {rq.error && <Alert severity="warning">{rq.error}</Alert>}
      {list.isLoading && <Skeleton variant="rounded" height={160} data-testid="eng-incidents-loading" />}
      {list.isError && (
        <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => list.refetch()}>Retry</Button>} data-testid="eng-incidents-error">
          {getApiErrorMessage(list.error, 'The incidents could not be loaded.')}
        </Alert>
      )}
      {first && (
        <Typography variant="caption" color="text.secondary" data-testid="eng-incidents-summary">
          {first.total === 0 ? 'No incident groups' : `Showing ${incidents.length} of ${first.total} incident group(s)`} between {first.window.since.replace('T', ' ').slice(0, 16)} and {first.window.until.replace('T', ' ').slice(0, 16)} UTC. {first.note}
        </Typography>
      )}
      {first && first.total === 0 && <Alert severity="success" data-testid="eng-incidents-empty">No WARN or ERROR groups in this window{origin ? ` (${origin} data)` : ''}. Widen the range to look further back.</Alert>}
      {first && first.total > 0 && <Typography variant="caption" color="text.secondary">{first.ranking}</Typography>}
      {incidents.map((i) => <IncidentCard key={i.signature} incident={i} refOf={refOf} historical={isHistorical(range)} />)}
      {list.hasNextPage && (
        <Button variant="outlined" onClick={() => list.fetchNextPage()} disabled={list.isFetchingNextPage} data-testid="eng-incidents-more">
          {list.isFetchingNextPage ? 'Loading…' : `Show more (${(first?.total ?? 0) - incidents.length} more)`}
        </Button>
      )}
    </Stack>
  );
};

export default IncidentsPanel;
