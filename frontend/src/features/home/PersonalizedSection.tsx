import { useQuery } from '@tanstack/react-query';
import { Box, Button, Paper, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { TuneOutlined } from '@mui/icons-material';
import { aiApi } from '@/api/ai';
import ProfileSwitcher from '@/components/profile/ProfileSwitcher';
import { RecommendationBlock } from '@/components/products/RecommendationSection';
import { ProductRowSkeleton } from '@/components/common/States';
import { useShopperProfiles } from '@/features/profile/useShopperProfiles';
import { colors } from '@/design/tokens';

const Prompt = () => (
  <Paper variant="outlined" sx={{ p: 2, display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap', bgcolor: colors.greenSoft }} data-testid="personalization-prompt">
    <TuneOutlined color="primary" />
    <Typography variant="body2" sx={{ flexGrow: 1 }}>Tell us what you play, your budget and favourite brands, and we will pick products that fit. All optional.</Typography>
    <Button component={RouterLink} to="/profile" size="small" variant="contained">Set preferences</Button>
  </Paper>
);

/**
 * Home personalisation. Three cases, chosen by the ACTIVE profile:
 *  - the account holder with no saved preferences: the original order-history recommendations ("Personalized for you"), unchanged;
 *  - a profile with preferences, or a family profile: the profile-driven scenario ("Picked for you"), never using the account holder's purchase history for a family member;
 *  - personalisation switched off for the profile: nothing is personalised.
 * Nothing invented is ever shown: when there is no genuinely personalised result the section is a prompt or absent.
 */
const PersonalizedSection = () => {
  const profiles = useShopperProfiles();
  const active = profiles.data?.profiles.find((p) => p.id === profiles.data?.activeProfileId);
  const profileDriven = !!active && (active.kind === 'FAMILY' || !active.preferences.empty);
  const off = !!active && !active.personalizationEnabled;

  const scenario = useQuery({
    queryKey: ['ai-recommendations', 'home-personalized', profiles.data?.activeProfileId ?? null, active?.updatedAt ?? null],
    queryFn: () => aiApi.scenarioRecommendations({ scenario: 'PERSONALIZED' }, 4),
    retry: false, staleTime: 60_000, enabled: !profiles.isLoading && profileDriven && !off,
  });
  const history = useQuery({
    queryKey: ['ai-recommendations', 'home-history'],
    queryFn: () => aiApi.recommendations([], 4),
    retry: false, staleTime: 60_000, enabled: !profiles.isLoading && !profileDriven && !off,
  });

  if (profiles.isLoading || scenario.isLoading || history.isLoading) return <ProductRowSkeleton count={4} />;
  if (off) return null;

  const bar = (
    <Stack direction="row" alignItems="center" spacing={1.5} flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
      <ProfileSwitcher label="Picks for" />
      <Box sx={{ flexGrow: 1 }} />
      <Button component={RouterLink} to="/profile" size="small" data-testid="home-edit-preferences">Edit preferences</Button>
    </Stack>
  );
  if (!profileDriven) {                                          // the original behaviour for an account holder with no preferences
    if (!history.data) return null;                              // the AI service is down: the rest of the home page is unaffected
    return history.data.items.length > 0
      ? <Box data-testid="home-personalized">{bar}<RecommendationBlock data={history.data} title="Personalized for you" limit={4} /></Box>
      : <Box>{bar}<Prompt /></Box>;
  }
  const data = scenario.data;
  if (!data) return null;
  if (!data.personalized || data.items.length === 0) return active?.preferences.empty ? <Box>{bar}<Prompt /></Box> : null;
  return (
    <Box data-testid="home-personalized">
      {bar}
      <RecommendationBlock data={data} title={active && active.kind === 'FAMILY' ? `Picked for ${active.displayName}` : 'Picked for you'} limit={4} />
    </Box>
  );
};

export default PersonalizedSection;
