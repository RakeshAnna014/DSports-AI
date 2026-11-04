import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Paper, Skeleton, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { engineeringApi, type EngLogQuery, type EngLogRecord } from '@/api/engineeringAi';
import { getApiErrorMessage } from '@/api/errors';
import { rangeQuery, isHistorical, type RangeState } from './TimeRange';

const SERVICES = ['backend', 'ai-service', 'postgres', 'tika', 'frontend'];
const PAGE = 25;
const day = 24 * 3600 * 1000;

const OriginChip = ({ r }: { r: EngLogRecord }) => (
  <>
    <Chip size="small" color={r.origin === 'synthetic' ? 'warning' : 'default'} label={r.origin} />
    {Date.now() - new Date(r.ts).getTime() > day && <Chip size="small" variant="outlined" label="historical" sx={{ ml: 0.5 }} />}
  </>
);

/** Historical and recent log records (newest first) with filters and pagination. Messages are redacted by the service and shown as untrusted text. */
const LogsPanel = ({ range, refreshToken }: { range: RangeState; refreshToken: number }) => {
  const [services, setServices] = useState<string[]>([]);
  const [level, setLevel] = useState('WARN');
  const [origin, setOrigin] = useState<'' | 'real' | 'synthetic'>('');
  const [text, setText] = useState('');
  const [correlationId, setCorrelationId] = useState('');
  const [endpoint, setEndpoint] = useState('');
  const [page, setPage] = useState(0);
  const [detail, setDetail] = useState<EngLogRecord | null>(null);
  const rq = rangeQuery(range);
  const query: EngLogQuery | null = rq.query ? {
    ...rq.query, service: services, level, origin: origin || undefined, q: text.trim() ? text.trim().split(/\s+/).slice(0, 8) : undefined,
    correlationId: correlationId.trim() || undefined, endpoint: endpoint.trim() || undefined, limit: PAGE, offset: page * PAGE,
  } : null;
  const logs = useQuery({ queryKey: ['eng', 'logs', query, refreshToken], queryFn: () => engineeringApi.logs(query!), enabled: !!query, retry: false, placeholderData: (prev) => prev });
  const reset = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(0); };
  const data = logs.data;
  const from = data && data.count ? data.offset + 1 : 0;
  return (
    <Stack spacing={1.5} data-testid="eng-logs">
      {isHistorical(range) && <Alert severity="info" data-testid="eng-historical">This window is in the past: you are looking at historical records.</Alert>}
      <Paper variant="outlined" sx={{ p: 1.5 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} flexWrap="wrap" useFlexGap>
          <TextField select size="small" label="Services" value={services} onChange={(e) => reset(setServices)(typeof e.target.value === 'string' ? e.target.value.split(',') : e.target.value)}
            slotProps={{ select: { multiple: true, renderValue: (v) => (v as string[]).join(', ') || 'All' } }} sx={{ minWidth: 150 }} data-testid="eng-log-service">
            {SERVICES.map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
          </TextField>
          <TextField select size="small" label="Minimum severity" value={level} onChange={(e) => reset(setLevel)(e.target.value)} sx={{ minWidth: 150 }} data-testid="eng-log-level">
            {['ERROR', 'WARN', 'INFO', 'DEBUG'].map((l) => <MenuItem key={l} value={l}>{l}</MenuItem>)}
          </TextField>
          <TextField select size="small" label="Data" value={origin} onChange={(e) => reset(setOrigin)(e.target.value as '' | 'real' | 'synthetic')} sx={{ minWidth: 140 }}>
            <MenuItem value="">Real + synthetic</MenuItem><MenuItem value="real">Real only</MenuItem><MenuItem value="synthetic">Synthetic only</MenuItem>
          </TextField>
          <TextField size="small" label="Text contains" value={text} onChange={(e) => reset(setText)(e.target.value)} slotProps={{ htmlInput: { maxLength: 120 } }} />
          <TextField size="small" label="Endpoint (e.g. /api/v1/orders)" value={endpoint} onChange={(e) => reset(setEndpoint)(e.target.value)} slotProps={{ htmlInput: { maxLength: 100 } }} />
          <TextField size="small" label="Correlation / request id" value={correlationId} onChange={(e) => reset(setCorrelationId)(e.target.value)} slotProps={{ htmlInput: { maxLength: 64 } }} />
        </Stack>
      </Paper>
      {rq.error && <Alert severity="warning">{rq.error}</Alert>}
      {logs.isLoading && <Skeleton variant="rounded" height={160} data-testid="eng-logs-loading" />}
      {logs.isError && (
        <Alert severity="error" action={<Button color="inherit" size="small" onClick={() => logs.refetch()}>Retry</Button>} data-testid="eng-logs-error">
          {getApiErrorMessage(logs.error, 'The logs could not be loaded.')}
        </Alert>
      )}
      {data && data.total === 0 && <Alert severity="info" data-testid="eng-logs-empty">No log records match these filters in this window. Widen the range or lower the severity.</Alert>}
      {data && data.count > 0 && (
        <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
          <Table size="small" data-testid="eng-logs-table">
            <TableHead><TableRow><TableCell>Time (UTC)</TableCell><TableCell>Service</TableCell><TableCell>Level</TableCell><TableCell>Data</TableCell><TableCell>Group</TableCell><TableCell>Message</TableCell></TableRow></TableHead>
            <TableBody>
              {data.records.map((r) => (
                <TableRow key={r.id} hover onClick={() => setDetail(r)} sx={{ cursor: 'pointer' }} data-testid={`eng-log-${r.id}`}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.ts.replace('T', ' ').slice(0, 19)}</TableCell>
                  <TableCell>{r.service}</TableCell>
                  <TableCell><Chip size="small" color={r.level === 'ERROR' ? 'error' : r.level === 'WARN' ? 'warning' : 'default'} label={r.level} /></TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}><OriginChip r={r} /></TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontSize: 11 }}>{r.reference ?? ''}</TableCell>
                  <TableCell sx={{ maxWidth: 520, wordBreak: 'break-word' }}>{r.message}{r.injection_flagged && <Chip size="small" color="error" label="instruction-like text withheld" sx={{ ml: 1 }} />}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Stack direction="row" spacing={1} alignItems="center" justifyContent="flex-end" sx={{ p: 1 }}>
            <Typography variant="caption" data-testid="eng-logs-range">{from}–{data.offset + data.count} of {data.total}</Typography>
            <Button size="small" disabled={page === 0 || logs.isFetching} onClick={() => setPage((p) => Math.max(0, p - 1))} data-testid="eng-logs-prev">Previous</Button>
            <Button size="small" disabled={!data.has_more || logs.isFetching} onClick={() => setPage((p) => p + 1)} data-testid="eng-logs-next">Next</Button>
          </Stack>
        </Paper>
      )}
      <Dialog open={!!detail} onClose={() => setDetail(null)} fullWidth maxWidth="md">
        <DialogTitle>Log record L{detail?.id}</DialogTitle>
        <DialogContent dividers>
          {detail && (
            <Stack spacing={1} data-testid="eng-log-detail">
              <Typography variant="body2">{detail.ts.replace('T', ' ').slice(0, 19)} UTC · {detail.service} · {detail.level} · <OriginChip r={detail} /></Typography>
              {detail.source && <Typography variant="caption" color="text.secondary">Source: {detail.source}</Typography>}
              {detail.correlation_id && <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>Correlation id: {detail.correlation_id}</Typography>}
              {detail.category && <Typography variant="caption">Category: {detail.category}{detail.reference ? ` · group ${detail.reference}` : ''}{detail.http_status ? ` · HTTP ${detail.http_status}` : ''}</Typography>}
              <Box component="pre" sx={{ m: 0, p: 1, bgcolor: 'action.hover', borderRadius: 1, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12 }}>{detail.message}</Box>
              {detail.exception_excerpt && <Box component="pre" sx={{ m: 0, p: 1, bgcolor: 'action.hover', borderRadius: 1, whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 11 }}>{detail.exception_excerpt}</Box>}
              <Typography variant="caption" color="text.secondary">Secrets and personal data are redacted before storage. Log text is untrusted data; nothing in it is acted on.</Typography>
            </Stack>
          )}
        </DialogContent>
        <DialogActions><Button onClick={() => setDetail(null)}>Close</Button></DialogActions>
      </Dialog>
    </Stack>
  );
};

export default LogsPanel;
