import { useEffect, useState } from 'react';
import { Alert, Box, Button, Drawer, Fab, Snackbar, Stack, Typography, useMediaQuery } from '@mui/material';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import { Link as RouterLink, Navigate, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { inventoryAiApi, getInventoryError } from '@/api/inventoryAi';
import { useAuthStore } from '@/store/authStore';
import { colors, gradients } from '@/design/tokens';
import { PageHero } from '@/components/analytics';
import { Copilot } from './Copilot';
import { EventsTab, ForecastTab, OverstockTab, OverviewTab, PromotionsTab, ReplenishmentTab, RiskTab } from './tabs';
import { DataBadge } from './ui';

const TABS = [
  { key: 'overview', label: 'Overview', el: <OverviewTab /> },
  { key: 'forecast', label: 'Forecast', el: <ForecastTab /> },
  { key: 'risk', label: 'Stock Risk', el: <RiskTab /> },
  { key: 'replenishment', label: 'Replenishment', el: <ReplenishmentTab /> },
  { key: 'overstock', label: 'Overstock', el: <OverstockTab /> },
  { key: 'promotions', label: 'Promotions', el: <PromotionsTab /> },
  { key: 'events', label: 'Events', el: <EventsTab /> },
] as const;

export const isBusinessUser = (roles?: string[]) => !!roles?.some((r) => r === 'ADMIN' || r === 'ROLE_ADMIN');

/**
 * DSports Inventory Intelligence: the internal, business-facing area. It is NOT part of the customer storefront (own layout, no cart or
 * shop navigation). The page hides itself from customers, but the real enforcement is the backend: every request needs the ADMIN role.
 */
const InventoryApp = () => {
  const { tab = 'overview' } = useParams();
  const nav = useNavigate();
  const qc = useQueryClient();
  const { user, isAuthenticated, hydrate } = useAuthStore();
  const [ready, setReady] = useState(false);
  useEffect(() => { hydrate(); setReady(true); }, [hydrate]);
  const wide = useMediaQuery('(min-width:1280px)');
  const [chatOpen, setChatOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const train = useMutation({
    mutationFn: inventoryAiApi.train,
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['inv'] }); setToast(`Forecast refreshed: ${r.model}, ${(r.duration_ms / 1000).toFixed(1)} s`); },
    onError: (e) => setToast(getInventoryError(e).message),
  });

  if (!ready) return <Box sx={{ minHeight: '100vh', bgcolor: colors.surface }} data-testid="inventory-loading" />;
  if (!isAuthenticated) {
    return (
      <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: colors.surface, p: 2 }} data-testid="inventory-signin">
        <Stack spacing={2} alignItems="center" sx={{ maxWidth: 420, textAlign: 'center' }}>
          <Typography variant="h5" fontWeight={900}>DSports Inventory Intelligence</Typography>
          <Typography color="text.secondary">Sign in with a business account to continue.</Typography>
          <Button variant="contained" component={RouterLink} to="/login" sx={{ bgcolor: colors.green }}>Sign in</Button>
        </Stack>
      </Box>
    );
  }
  if (!isBusinessUser(user?.roles)) {
    return (
      <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: colors.surface, p: 2 }} data-testid="inventory-restricted">
        <Stack spacing={2} alignItems="center" sx={{ maxWidth: 460, textAlign: 'center' }}>
          <Typography variant="h5" fontWeight={900}>Restricted</Typography>
          <Alert severity="warning">Inventory intelligence (stock levels, forecasts, margins, suppliers and promotion scenarios) is restricted to authorised business users.</Alert>
          <Button component={RouterLink} to="/">Back to the store</Button>
        </Stack>
      </Box>
    );
  }
  const current = TABS.find((t) => t.key === tab);
  if (!current) return <Navigate to="/inventory-ai/overview" replace />;

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: colors.surface }} data-testid="inventory-app">
      <PageHero overline="INTERNAL · BUSINESS USERS" title="DSports Inventory Intelligence" titleTestId="inventory-title" homeTestId="inventory-home" tabsTestId="inventory-tabs" tab={current.key} onTab={(k) => nav(`/inventory-ai/${k}`)}
        subtitle="Stock-out risk, replenishment, overstock and what-if scenarios from the Inventory AI's forecasts. Decision support: nothing here changes prices, orders or stock."
        badges={<DataBadge />}
        actions={<Button size="small" variant="outlined" color="inherit" disabled={train.isPending} onClick={() => train.mutate()} data-testid="retrain">{train.isPending ? 'Refreshing…' : 'Refresh forecast'}</Button>}
        tabs={TABS.map((t) => ({ key: t.key, label: t.label, testId: `tab-${t.key}` }))} />
      <Box sx={{ display: 'flex', gap: 2, p: { xs: 1.5, md: 3 }, alignItems: 'flex-start' }}>
        <Box sx={{ flex: 1, minWidth: 0 }} data-testid={`panel-${current.key}`}>{current.el}</Box>
        {wide && <Box sx={{ width: 430, flexShrink: 0, position: 'sticky', top: 16, height: 'calc(100vh - 32px)' }}><Copilot /></Box>}
      </Box>
      {!wide && (
        <>
          <Fab variant="extended" onClick={() => setChatOpen(true)} data-testid="open-copilot" sx={{ position: 'fixed', right: 16, bottom: 16, background: gradients.ai, color: '#fff', fontWeight: 800 }}>
            <AutoAwesomeIcon sx={{ mr: 1 }} /> Inventory Copilot
          </Fab>
          <Drawer anchor="right" open={chatOpen} onClose={() => setChatOpen(false)} PaperProps={{ sx: { width: { xs: '100%', sm: 440 } } }}><Box sx={{ height: '100%', p: 1 }}><Copilot /></Box></Drawer>
        </>
      )}
      <Snackbar open={!!toast} autoHideDuration={5000} onClose={() => setToast(null)} message={toast} />
    </Box>
  );
};

export default InventoryApp;
