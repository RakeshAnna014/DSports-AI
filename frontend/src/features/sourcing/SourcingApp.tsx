import { useEffect, useState } from 'react';
import { Alert, Box, Button, Checkbox, Container, Dialog, FormControlLabel, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { isBuyer, sourcingApi, sourcingError } from '@/api/sourcing';
import { useAuthStore } from '@/store/authStore';
import { PageHero } from '@/components/analytics';
import { colors } from '@/design/tokens';
import Dashboard from './Dashboard';
import OpportunityList from './OpportunityList';
import OpportunityDetail from './OpportunityDetail';
import DemandGaps from './DemandGaps';
import Suppliers from './Suppliers';
import AskPanel from './AskPanel';
import CalculatorTab from './CalculatorTab';
import { Proposals, Queue, Runs } from './QueueRuns';

const TABS = [['dashboard', 'Dashboard'], ['opportunities', 'Product discovery'], ['gaps', 'Customer demand'], ['suppliers', 'Suppliers'], ['calculator', 'Cost calculator'], ['queue', 'Shortlist & approvals'], ['runs', 'Research runs'], ['proposals', 'Onboarding proposals'], ['ask', 'Ask a question']] as const;

const AddProduct = ({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) => {
  const [f, setF] = useState({ name: '', brand: '', model: '', gtin: '', sport: '', category: '', description: '', relevance: '' });
  const [isTest, setIsTest] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((x) => ({ ...x, [k]: e.target.value }));
  const qc = useQueryClient();
  const save = useMutation({
    mutationFn: () => sourcingApi.createOpportunity({ ...Object.fromEntries(Object.entries(f).filter(([, v]) => v.trim() !== '')), ...(isTest ? { is_test: true } : {}) }),
    onSuccess: (o) => { void qc.invalidateQueries({ queryKey: ['src'] }); onCreated(o.id); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth data-testid="add-product-dialog">
      <DialogTitle>Add a product to investigate</DialogTitle>
      <DialogContent><Stack spacing={1.5} sx={{ pt: 1 }}>
        <TextField size="small" label="Product name" value={f.name} onChange={set('name')} inputProps={{ 'data-testid': 'p-name' }} />
        <Stack direction="row" spacing={1.5}><TextField size="small" label="Brand" value={f.brand} onChange={set('brand')} /><TextField size="small" label="Model number" value={f.model} onChange={set('model')} /><TextField size="small" label="GTIN / barcode" value={f.gtin} onChange={set('gtin')} /></Stack>
        <Stack direction="row" spacing={1.5}><TextField size="small" label="Sport" value={f.sport} onChange={set('sport')} /><TextField size="small" label="Category" value={f.category} onChange={set('category')} /></Stack>
        <TextField size="small" multiline minRows={2} label="Description" value={f.description} onChange={set('description')} />
        <TextField size="small" label="Why it may matter to DSports" value={f.relevance} onChange={set('relevance')} />
        <FormControlLabel control={<Checkbox size="small" checked={isTest} onChange={(e) => setIsTest(e.target.checked)} inputProps={{ 'data-testid': 'p-test' } as React.InputHTMLAttributes<HTMLInputElement>} />}
          label={<Typography variant="body2">This is test or practice data (kept out of live figures)</Typography>} />
        {save.isError && <Alert severity="warning" data-testid="p-error">{sourcingError(save.error)}</Alert>}
      </Stack></DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={f.name.trim().length < 2 || save.isPending} onClick={() => save.mutate()} data-testid="p-save">Add</Button></DialogActions>
    </Dialog>
  );
};

/** Internal. The page hides itself from non-buyers, but the real enforcement is the AI service: every route needs a buyer role, and approvals need an approver. */
const SourcingApp = () => {
  const { user, isAuthenticated, hydrate } = useAuthStore();
  const [ready, setReady] = useState(false);
  // A full page load starts with an empty auth store: restore the session before deciding that the visitor is not a buyer.
  useEffect(() => { hydrate(); setReady(true); }, [hydrate]);
  const allowed = isAuthenticated && isBuyer(user?.roles);
  const [params, setParams] = useSearchParams();
  const [adding, setAdding] = useState(false);
  const tab = params.get('tab') ?? 'dashboard';
  const opp = params.get('opp');
  const go = (t: string, o?: string | null) => setParams(o ? { tab: t, opp: o } : { tab: t });
  if (!ready) return <Box sx={{ minHeight: '100vh' }} data-testid="src-loading" />;
  if (!allowed) {
    return (
      <Container maxWidth="sm" sx={{ py: 6, textAlign: 'center' }} data-testid="src-restricted">
        <Typography variant="h5" fontWeight={900} gutterBottom>Product & Supplier Intelligence</Typography>
        <Alert severity="warning" sx={{ mb: 2 }}>This area is restricted to buyers, category managers and administrators.</Alert>
        <Button component={RouterLink} to={isAuthenticated ? '/' : '/login'}>{isAuthenticated ? 'Back to the shop' : 'Sign in'}</Button>
      </Container>
    );
  }
  return (
    <Box sx={{ bgcolor: colors.surface, minHeight: '100vh' }} data-testid="src-app">
      <PageHero overline="INTERNAL · BUYERS" title="Product & Supplier Intelligence" homeTestId="src-home" tabsTestId="src-tabs" tab={TABS.some(([k]) => k === tab) ? tab : 'dashboard'} onTab={(k) => go(k)}
        subtitle="Decide what to add to the catalogue and where to source it. Every figure shows where it came from: public listing, supplier quotation, buyer-verified, AI estimate, or unknown. Calculations are deterministic. Approval records a decision; it never buys, publishes or prices anything."
        actions={<Button size="small" variant="outlined" color="inherit" onClick={() => setAdding(true)} data-testid="hero-add-product">Add a product</Button>}
        tabs={TABS.map(([k, label]) => ({ key: k, label, testId: `src-tab-${k}` }))} />
      <Container maxWidth="xl" sx={{ py: 2.5 }}>
        {tab === 'dashboard' && <Dashboard onTab={(t) => go(t)} />}
        {tab === 'opportunities' && (opp ? <OpportunityDetail id={opp} onBack={() => go('opportunities')} /> : <OpportunityList onOpen={(id) => go('opportunities', id)} onCreate={() => setAdding(true)} />)}
        {tab === 'gaps' && (opp ? <OpportunityDetail id={opp} onBack={() => go('gaps')} /> : <DemandGaps onOpen={(id) => go('gaps', id)} />)}
        {tab === 'suppliers' && <Suppliers />}
        {tab === 'calculator' && <CalculatorTab />}
        {tab === 'queue' && <Queue onOpen={(id) => go('opportunities', id)} />}
        {tab === 'runs' && <Runs />}
        {tab === 'proposals' && <Proposals onOpen={(id) => go('opportunities', id)} />}
        {tab === 'ask' && <AskPanel onOpen={(id) => go('opportunities', id)} />}
        {adding && <AddProduct onClose={() => setAdding(false)} onCreated={(id) => go('opportunities', id)} />}
      </Container>
    </Box>
  );};

export default SourcingApp;
