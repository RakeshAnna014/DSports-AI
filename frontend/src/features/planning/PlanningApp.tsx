import { useEffect, useMemo, useState } from 'react';
import { Alert, Box, Button, Container, FormControl, MenuItem, Paper, Select, Stack, Typography } from '@mui/material';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { isApprover, isPlanner, planningApi, type Filters, type PlanStatus, type Source } from '@/api/planning';
import { useAuthStore } from '@/store/authStore';
import { PageHero, ProvenanceChip, WorkflowSteps } from '@/components/analytics';
import { colors } from '@/design/tokens';
import Actuals from './Actuals';
import Analysis from './Analysis';
import AskPanel from './AskPanel';
import Dashboard from './Dashboard';
import Evaluation from './Evaluation';
import Insights from './Insights';
import Inventory from './Inventory';
import PlanBar from './PlanBar';
import Performance from './Performance';
import Readiness from './Readiness';
import ReviewQueue from './ReviewQueue';
import Scenarios from './Scenarios';
import Workspace from './Workspace';
import { PlanningContext, SOURCE_LABEL } from './parts';

const TABS = [['dashboard', 'Dashboard'], ['insights', 'AI analysis'], ['workspace', 'Weekly planning'], ['performance', 'Plan vs actual'], ['actuals', 'Actual sales'], ['analysis', 'Regions & categories'],
  ['inventory', 'Inventory planning'], ['evaluation', 'Forecast accuracy'], ['scenarios', 'Scenarios & pilots'], ['review', 'Review queue'], ['readiness', 'Data & forecasts'], ['ask', 'Ask a question']] as const;

const STEPS = ['Generate forecast', 'Review recommendations', 'Adjust', 'Submit', 'Approve', 'Track actuals'];
/** Where a plan is in the working sequence: the plan's own status decides, the screen only mirrors it. */
const stepOf = (status: PlanStatus | undefined): number => {
  switch (status) {
    case undefined: return 0;
    case 'DRAFT': case 'RETURNED': return 2;
    case 'SUBMITTED': case 'UNDER_REVIEW': return 4;
    case 'REJECTED': return 3;
    default: return 5;
  }
};

/** Internal. The page hides itself from non-planners, but the real enforcement is the AI service: every route needs a planning role, edits and approvals are role-checked, and each user sees only their own stores. */
const PlanningApp = () => {
  const { user, isAuthenticated, hydrate } = useAuthStore();
  const [ready, setReady] = useState(false);
  useEffect(() => { hydrate(); setReady(true); }, [hydrate]);
  const [params, setParams] = useSearchParams();
  const [filters, setFilters] = useState<Filters>({});
  const roles = user?.roles ?? [];
  const allowed = isAuthenticated && isPlanner(roles);
  const tab = params.get('tab') ?? 'dashboard';
  const source = (params.get('source') as Source | null) ?? 'POC_SYNTHETIC';
  const planId = params.get('plan') ?? undefined;
  const taxonomy = useQuery({ queryKey: ['pln', 'taxonomy'], queryFn: planningApi.taxonomy, enabled: ready && allowed, retry: false });
  const plan = useQuery({ queryKey: ['pln', 'plan', planId], queryFn: () => planningApi.plan(planId as string), enabled: ready && allowed && !!planId, retry: false });
  const queue = useQuery({ queryKey: ['pln', 'review-count'], queryFn: () => planningApi.reviewQueue(), enabled: ready && allowed && isApprover(roles), retry: false });
  const update = (patch: Record<string, string | undefined>) => setParams((p) => { const next = new URLSearchParams(p); Object.entries(patch).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k))); return next; });
  const ctx = useMemo(() => ({
    source, setSource: (s: Source) => update({ source: s, plan: undefined }), planId, setPlanId: (id: string | undefined) => update({ plan: id }), filters, setFilters, taxonomy: taxonomy.data ?? {}, roles,
    goTab: (t: string, extra?: Record<string, string>) => update({ tab: t, ...extra }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [source, planId, filters, taxonomy.data, roles.join(',')]);
  if (!ready) return <Box sx={{ minHeight: '100vh' }} data-testid="pln-loading" />;
  if (!allowed) {
    return (
      <Container maxWidth="sm" sx={{ py: 6, textAlign: 'center' }} data-testid="pln-restricted">
        <Typography variant="h5" fontWeight={900} gutterBottom>Sales & Inventory Planning</Typography>
        <Alert severity="warning" sx={{ mb: 2 }}>This area is restricted to sales planners, store and regional managers, category managers and administrators.</Alert>
        <Button component={RouterLink} to={isAuthenticated ? '/' : '/login'}>{isAuthenticated ? 'Back to the shop' : 'Sign in'}</Button>
      </Container>
    );
  }
  const current = TABS.some(([k]) => k === tab) ? tab : 'dashboard';
  const awaiting = queue.data?.items.length ?? 0;
  return (
    <PlanningContext.Provider value={ctx}>
      <Box sx={{ minHeight: '100vh', bgcolor: colors.surface }} data-testid="pln-app">
        <PageHero overline="INTERNAL · PLANNING" title="Sales & Inventory Planning" homeTestId="pln-home" tab={current} onTab={(k) => update({ tab: k })} tabsTestId="pln-tabs"
          subtitle="Plan units per product, store and week. The model forecast, the manager's forecast, the target and the actual are always separate numbers. Approval records a decision; it never orders, moves stock, publishes or prices anything."
          badges={<ProvenanceChip dark kind={source === 'POC_SYNTHETIC' ? 'DEMO' : 'LIVE'} label={SOURCE_LABEL[source]} />}
          actions={(
            <FormControl size="small" sx={{ minWidth: 190 }}>
              <Select value={source} onChange={(e) => ctx.setSource(e.target.value as Source)} aria-label="Data scope" SelectDisplayProps={{ 'data-testid': 'pln-source' } as React.HTMLAttributes<HTMLDivElement>}
                sx={{ color: '#fff', '.MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255,255,255,0.4)' }, '.MuiSvgIcon-root': { color: '#fff' }, fontWeight: 700 }}>
                {(['POC_SYNTHETIC', 'DSPORTS_LIVE'] as Source[]).map((s) => <MenuItem key={s} value={s}>Data scope: {SOURCE_LABEL[s]}</MenuItem>)}
              </Select>
            </FormControl>
          )}
          tabs={TABS.map(([k, label]) => ({ key: k, label, testId: `pln-tab-${k}`, badge: k === 'review' ? awaiting : undefined }))} />
        <Container maxWidth="xl" sx={{ py: 2.5 }}>
          <Stack spacing={1.5}>
            <Paper elevation={0} sx={{ p: 1.5, borderRadius: 3, border: `1px solid ${colors.line}` }}>
              <Stack spacing={1}>
                <PlanBar />
                <WorkflowSteps steps={STEPS} current={stepOf(plan.data?.status)} />
              </Stack>
            </Paper>
            {current === 'dashboard' && <Dashboard />}
            {current === 'insights' && <Insights />}
            {current === 'workspace' && <Workspace />}
            {current === 'performance' && <Performance />}
            {current === 'actuals' && <Actuals />}
            {current === 'analysis' && <Analysis />}
            {current === 'inventory' && <Inventory />}
            {current === 'evaluation' && <Evaluation />}
            {current === 'scenarios' && <Scenarios />}
            {current === 'review' && <ReviewQueue />}
            {current === 'readiness' && <Readiness />}
            {current === 'ask' && <AskPanel />}
          </Stack>
        </Container>
      </Box>
    </PlanningContext.Provider>
  );
};

export default PlanningApp;
