import { Alert, Box, Button, Chip, FormControlLabel, Paper, Skeleton, Stack, Switch, Tooltip, Typography } from '@mui/material';
import { Refresh } from '@mui/icons-material';
import type { EngFreshness, EngRefreshResult, EngSourceStatus } from '@/api/engineeringAi';

const COLOR: Record<EngFreshness, 'success' | 'warning' | 'error' | 'default'> = { LIVE: 'success', DELAYED: 'warning', STALE: 'error', SYNTHETIC: 'default' };
const lag = (s?: number) => (s === undefined ? '' : s < 90 ? `${s}s behind` : s < 5400 ? `${Math.round(s / 60)} min behind` : `${Math.round(s / 3600)} h behind`);

interface Props {
  status: { data?: EngSourceStatus; isLoading: boolean; isError: boolean };
  live: boolean;
  onLive: (v: boolean) => void;
  refreshing: boolean;
  onRefresh: () => void;
  lastRefresh: Date | null;
  refreshResult: EngRefreshResult | null;
  refreshError: string | null;
  intervalSeconds: number;
  now: number;
}

/**
 * Where the data comes from and how fresh it is. LIVE / DELAYED / STALE describe the STORED data (the age of the newest record per source), which is
 * what the incident list is built from; a source that could not be re-read is listed with its reason. Synthetic data is always labelled as such.
 */
const SourceHealth = ({ status, live, onLive, refreshing, onRefresh, lastRefresh, refreshResult, refreshError, intervalSeconds, now }: Props) => {
  const sources = status.data?.sources ?? [];
  const age = lastRefresh ? Math.round((now - lastRefresh.getTime()) / 1000) : null;
  const stale = live && (refreshError !== null || (age !== null && age > intervalSeconds * 3));
  const unavailable = (refreshResult?.sources ?? []).filter((s) => s.status === 'unavailable');
  return (
    <Paper variant="outlined" sx={{ p: 1.5, mb: 2 }} data-testid="eng-sources">
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }} justifyContent="space-between">
        <Typography variant="caption" fontWeight={800}>Connected log sources</Typography>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <FormControlLabel sx={{ m: 0 }} control={<Switch size="small" checked={live} onChange={(e) => onLive(e.target.checked)} inputProps={{ 'aria-label': 'live refresh' }} />}
            label={<Typography variant="caption">Live refresh ({intervalSeconds}s)</Typography>} />
          <Button size="small" startIcon={<Refresh />} onClick={onRefresh} disabled={refreshing} data-testid="eng-refresh">{refreshing ? 'Refreshing…' : 'Refresh now'}</Button>
        </Stack>
      </Stack>
      {status.isLoading && <Skeleton height={24} />}
      {status.isError && <Typography variant="caption" color="error" display="block">Sources could not be loaded.</Typography>}
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
        {sources.map((s) => (
          <Tooltip key={`${s.service}${s.origin}${s.source}`} title={`${s.source} · ${s.first_ts.slice(0, 16).replace('T', ' ')} to ${s.last_ts.slice(0, 16).replace('T', ' ')} UTC`}>
            <Chip size="small" variant="outlined" color={s.origin === 'synthetic' ? 'warning' : 'default'} label={`${s.service} · ${s.origin} · ${s.records} records`} />
          </Tooltip>
        ))}
        {sources.length === 0 && status.data && <Typography variant="caption">No logs ingested yet. Use Refresh now, or run <code>python -m app.engineering.cli ingest</code>.</Typography>}
      </Stack>
      {sources.length > 0 && (
        <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 0.75 }} data-testid="eng-freshness">
          {sources.map((s) => s.freshness && (
            <Chip key={`f${s.service}${s.origin}${s.source}`} size="small" color={COLOR[s.freshness]} label={`${s.service} ${s.origin}: ${s.freshness}${s.freshness === 'SYNTHETIC' ? '' : ` (${lag(s.lag_seconds)})`}`} />
          ))}
        </Stack>
      )}
      <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }} data-testid="eng-last-refresh">
        {lastRefresh ? `Last refreshed ${age}s ago${refreshResult && !refreshResult.throttled ? `, ${refreshResult.stored_new} new record(s) stored` : ''}.` : 'Not refreshed in this session. Records come from earlier ingestion.'}
        {' '}LIVE/DELAYED/STALE show the age of the newest stored record per source.
      </Typography>
      {stale && <Alert severity="warning" sx={{ mt: 1 }} data-testid="eng-stale">Live refresh is behind{refreshError ? `: ${refreshError}` : ` (last success ${age}s ago)`}. What you see may be out of date.</Alert>}
      {unavailable.length > 0 && (
        <Alert severity="info" sx={{ mt: 1 }} data-testid="eng-unavailable">
          <Box component="ul" sx={{ m: 0, pl: 2 }}>{unavailable.map((s) => <li key={s.source}>{s.service} ({s.source}) could not be read: {s.reason}</li>)}</Box>
        </Alert>
      )}
    </Paper>
  );
};

export default SourceHealth;
