import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Collapse, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { aiApi } from '@/api/ai';
import { useAuthStore } from '@/store/authStore';
import { formatPrice } from '@/lib/productUtils';
import ProfileSwitcher from '@/components/profile/ProfileSwitcher';
import { useShopperProfiles } from '@/features/profile/useShopperProfiles';
import { ProductRowSkeleton } from '@/components/common/States';
import { RecommendationBlock } from './RecommendationSection';
import type { RecoScenario } from '@/types/ai';

const SCENARIOS: { value: RecoScenario; label: string }[] = [
  { value: 'COMPLEMENTARY', label: 'Goes with this' },
  { value: 'FREQUENTLY_BOUGHT_TOGETHER', label: 'Bought together' },
  { value: 'SIMILAR', label: 'Similar' },
  { value: 'UPGRADE', label: 'Premium alternatives' },
];

/**
 * Budget- and context-aware recommendations for one product. Prices, stock, discounts and the budget band are computed by the AI service
 * from DSports data (no model decides them); this component only presents them and lets the customer move the budget.
 */
const ScenarioRecommendations = ({ productId, productName }: { productId: string; productName?: string }) => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const profiles = useShopperProfiles();
  const [scenario, setScenario] = useState<RecoScenario>('COMPLEMENTARY');
  const [budget, setBudget] = useState<number | undefined>(undefined);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['reco', productId, scenario, budget ?? null, isAuthenticated, profiles.data?.activeProfileId ?? null],
    queryFn: () => aiApi.scenarioRecommendations({ referenceProductId: productId, scenario, budget }, 4),
    retry: false,
    staleTime: 60_000,
    enabled: !profiles.isLoading,        // wait for the active profile so the request is made once, for the right profile
  });

  const applyBudget = () => {
    const v = Number(draft);
    if (Number.isFinite(v) && v > 0) { setBudget(v); setEditing(false); }
  };
  const premium = () => { setScenario('UPGRADE'); setBudget(undefined); };

  return (
    <Box component="section" aria-label="Recommended with this product" data-testid="scenario-recommendations" sx={{ mt: 4 }}>
      <Stack direction="row" alignItems="center" spacing={1.5} flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
        <Typography variant="h5" fontWeight={800} sx={{ flexGrow: 1 }}>Recommended with {productName ?? 'this product'}</Typography>
        {isAuthenticated && <ProfileSwitcher />}
      </Stack>
      <ToggleButtonGroup exclusive size="small" value={scenario} onChange={(_, v: RecoScenario | null) => { if (v) { setScenario(v); setBudget(undefined); } }} aria-label="Recommendation type"
        sx={{ flexWrap: 'wrap', mb: 1.5 }}>
        {SCENARIOS.map((s) => <ToggleButton key={s.value} value={s.value} data-testid={`reco-${s.value.toLowerCase()}`}>{s.label}</ToggleButton>)}
      </ToggleButtonGroup>

      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <Button size="small" variant="outlined" onClick={() => { setDraft(budget?.toString() ?? ''); setEditing((e) => !e); }} data-testid="reco-adjust-budget">Adjust budget</Button>
        {scenario !== 'UPGRADE' && <Button size="small" variant="outlined" onClick={premium} data-testid="reco-show-premium">Show premium alternatives</Button>}
        {budget !== undefined && <Button size="small" onClick={() => setBudget(undefined)} data-testid="reco-clear-budget">Clear budget ({formatPrice(budget)})</Button>}
      </Stack>
      <Collapse in={editing} unmountOnExit>
        <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
          <TextField size="small" type="number" label="Maximum price (₹)" value={draft} onChange={(e) => setDraft(e.target.value)} inputProps={{ min: 1, 'data-testid': 'reco-budget-input' }}
            onKeyDown={(e) => { if (e.key === 'Enter') applyBudget(); }} />
          <Button variant="contained" size="small" disabled={!(Number(draft) > 0)} onClick={applyBudget} data-testid="reco-budget-apply">Apply</Button>
        </Stack>
      </Collapse>

      {(isLoading || profiles.isLoading) && <ProductRowSkeleton count={4} />}
      {isError && (
        <Alert severity="info" action={<Button color="inherit" size="small" onClick={() => void refetch()}>Retry</Button>} data-testid="reco-error">
          Recommendations are unavailable right now. The rest of the shop works as usual.
        </Alert>
      )}
      {data && (
        <>
          {data.budget?.fallback_used && (
            <Alert severity="info" sx={{ mb: 1.5 }} data-testid="reco-fallback-note">{data.budget.applied.label}. Items shown are still in stock and compatible.</Alert>
          )}
          {data.items.length > 0 ? (
            <RecommendationBlock data={data} title={data.title} limit={4} />
          ) : (
            <Alert severity="info" data-testid="reco-empty"
              action={data.suggestedScenario ? <Button color="inherit" size="small" onClick={() => setScenario(data.suggestedScenario!)}>See what goes with it</Button> : undefined}>
              {data.message ?? 'Nothing matches right now.'}
            </Alert>
          )}
          {data.profile && data.profile.kind === 'FAMILY' && (
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>Using {data.profile.display_name}&apos;s profile. Your purchase history is not used for it.</Typography>
          )}
        </>
      )}
    </Box>
  );
};

export default ScenarioRecommendations;
