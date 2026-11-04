import { useCallback, useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Container, MenuItem, Stack, Tab, Tabs, TextField, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { HomeOutlined } from '@mui/icons-material';
import { engineeringApi, type EngRefreshResult } from '@/api/engineeringAi';
import { useAuthStore } from '@/store/authStore';
import TimeRangePicker, { defaultRange, isHistorical, type RangeState } from './TimeRange';
import SourceHealth from './SourceHealth';
import IncidentsPanel from './IncidentsPanel';
import LogsPanel from './LogsPanel';
import AskPanel, { errorText } from './AskPanel';

const LIVE_INTERVAL_S = 15;
const isEngineer = (roles?: string[]) => !!roles?.some((r) => r === 'ADMIN' || r === 'ROLE_ADMIN');

/** Internal. The page hides itself from non-engineers, but the real enforcement is the AI service: every route needs the ADMIN role. */
const EngineeringApp = () => {
  const { user, isAuthenticated, hydrate } = useAuthStore();
  const [ready, setReady] = useState(false);
  // This page is outside the main layout, so a full page load starts with an empty auth store: restore the session before deciding the visitor is not an engineer.
  useEffect(() => { hydrate(); setReady(true); }, [hydrate]);
  const allowed = isAuthenticated && isEngineer(user?.roles);
  const qc = useQueryClient();
  const [tab, setTab] = useState<'incidents' | 'logs' | 'ask'>('incidents');
  const [range, setRange] = useState<RangeState>(defaultRange());
  const [origin, setOrigin] = useState<'' | 'real' | 'synthetic'>('');
  const [live, setLive] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null);
  const [refreshResult, setRefreshResult] = useState<EngRefreshResult | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const status = useQuery({ queryKey: ['eng', 'sources'], queryFn: engineeringApi.sourceStatus, enabled: allowed, retry: false });

  // Near-live: re-read the real sources on the service, then reload what is on screen. A failed refresh is shown, never hidden.
  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      const result = await engineeringApi.refresh();
      setRefreshResult(result);
      setRefreshError(null);
      setLastRefresh(new Date());
      setNow(Date.now());
      setRefreshToken((n) => n + 1);
      await qc.invalidateQueries({ queryKey: ['eng', 'sources'] });
    } catch (err) {
      setRefreshError(errorText(err));
    } finally {
      setRefreshing(false);
    }
  }, [qc]);

  useEffect(() => {
    if (!allowed || !live) return undefined;
    const tick = window.setInterval(() => { void refresh(); }, LIVE_INTERVAL_S * 1000);
    const clock = window.setInterval(() => setNow(Date.now()), 5000);
    return () => { window.clearInterval(tick); window.clearInterval(clock); };
  }, [allowed, live, refresh]);

  if (!ready) return <Box sx={{ minHeight: '100vh' }} data-testid="eng-loading" />;
  if (!allowed) {
    return (
      <Container maxWidth="sm" sx={{ py: 6, textAlign: 'center' }} data-testid="eng-restricted">
        <Typography variant="h5" fontWeight={900} gutterBottom>Engineering AI</Typography>
        <Alert severity="warning" sx={{ mb: 2 }}>Log investigation is restricted to authorised engineering users.</Alert>
        <Button component={RouterLink} to={isAuthenticated ? '/' : '/login'}>{isAuthenticated ? 'Back to the shop' : 'Sign in'}</Button>
      </Container>
    );
  }
  return (
    <Container maxWidth="lg" sx={{ py: 3 }} data-testid="eng-app">
      <Stack direction="row" alignItems="flex-start" justifyContent="space-between" spacing={1} sx={{ mb: 0.5 }}>
        <Box>
          <Typography variant="overline" color="text.secondary" fontWeight={800}>INTERNAL · ENGINEERING</Typography>
          <Typography variant="h5" fontWeight={900}>Log monitoring and incident intelligence</Typography>
        </Box>
        <Button component={RouterLink} to="/" variant="outlined" size="small" startIcon={<HomeOutlined />} aria-label="Home" data-testid="eng-home" sx={{ flexShrink: 0 }}>Home</Button>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Logs are redacted before storage and treated as untrusted data. Facts come from log records, git and source files; explanations are hypotheses unless you confirm them.
        Records marked synthetic are generated test data and are never shown as live production data.
      </Typography>
      <SourceHealth status={status} live={live} onLive={setLive} refreshing={refreshing} onRefresh={() => void refresh()} lastRefresh={lastRefresh}
        refreshResult={refreshResult} refreshError={refreshError} intervalSeconds={LIVE_INTERVAL_S} now={now} />
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'flex-start' }} sx={{ mb: 1.5 }}>
        <TimeRangePicker value={range} onChange={setRange} />
        <TextField select size="small" label="Data" value={origin} onChange={(e) => setOrigin(e.target.value as '' | 'real' | 'synthetic')} sx={{ minWidth: 150 }} data-testid="eng-origin">
          <MenuItem value="">Real + synthetic</MenuItem><MenuItem value="real">Real only</MenuItem><MenuItem value="synthetic">Synthetic only</MenuItem>
        </TextField>
      </Stack>
      {isHistorical(range) && tab === 'incidents' && <Alert severity="info" sx={{ mb: 1.5 }}>This window is in the past: these are historical incidents, not what is happening now.</Alert>}
      <Tabs value={tab} onChange={(_e, v) => setTab(v)} sx={{ mb: 1.5 }} variant="scrollable" scrollButtons="auto">
        <Tab value="incidents" label="Incidents" data-testid="eng-tab-incidents" />
        <Tab value="logs" label="Logs" data-testid="eng-tab-logs" />
        <Tab value="ask" label="Ask a question" data-testid="eng-tab-ask" />
      </Tabs>
      {tab === 'incidents' && <IncidentsPanel range={range} origin={origin} refreshToken={refreshToken} />}
      {tab === 'logs' && <LogsPanel range={range} refreshToken={refreshToken} />}
      {tab === 'ask' && <AskPanel origin={origin} />}
    </Container>
  );
};

export default EngineeringApp;
