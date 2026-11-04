import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import ScenarioRecommendations from './ScenarioRecommendations';
import PersonalizedSection from '@/features/home/PersonalizedSection';
import { aiApi } from '@/api/ai';
import { shopperProfilesApi } from '@/api/shopperProfiles';
import { useAuthStore } from '@/store/authStore';
import { useCartStore } from '@/store/cartStore';
import type { AiRecommendations } from '@/types/ai';

vi.mock('@/api/ai', () => ({ aiApi: { scenarioRecommendations: vi.fn(), recommendations: vi.fn() } }));
vi.mock('@/api/shopperProfiles', () => ({ shopperProfilesApi: { overview: vi.fn(), select: vi.fn() } }));

const item = (over: Record<string, unknown> = {}) => ({
  product_id: 'p1', name: 'Arm Guard', brand: 'SG', category: 'Accessories', sport: 'Cricket', price: 350, mrp: 500, original_price: 500, discount_pct: 30, availability: 'Only 3 left', in_stock: true,
  image_url: '/img/a.png', reasons: ['Goes with MRF Genius Bat: accessories', '30% off the list price (you save ₹150)'], budget_note: 'Up to 20% of the price of the product you picked (₹2,000)', ...over,
});
const reco = (over: Partial<AiRecommendations> = {}): AiRecommendations => ({
  title: 'Goes well with this', personalized: false, because: ['Up to 20% of the price of the product you picked (₹2,000)'], items: [item()], scenario: 'COMPLEMENTARY',
  budget: { explicit: null, complementary_ceiling_pct: 0.2, applied: { low: null, high: 2000, source: 'policy', pct: 0.2, label: 'Up to 20% of the price of the product you picked (₹2,000)' }, fallback_used: false, tried: [] },
  ...over,
} as AiRecommendations);

beforeEach(() => {
  useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u', email: 'a@b.c', roles: ['CUSTOMER'] } });
  vi.mocked(shopperProfilesApi.overview).mockResolvedValue({ activeProfileId: 'p-owner', profiles: [], maxFamilyProfiles: 6 });
  vi.mocked(aiApi.scenarioRecommendations).mockResolvedValue(reco());
});

describe('Scenario recommendations', () => {
  it('shows image, name, price, a validated original price and discount, availability and factual reasons', async () => {
    renderWithProviders(<ScenarioRecommendations productId="bat-1" productName="MRF Genius Bat" />);
    const card = await screen.findByTestId('recommendation-card');
    expect(within(card).getByText('Arm Guard')).toBeInTheDocument();
    expect(within(card).getByText('₹350.00')).toBeInTheDocument();
    expect(within(card).getByText('₹500.00')).toBeInTheDocument();                    // the real list price, struck through
    expect(within(card).getByText('30% OFF')).toBeInTheDocument();
    expect(within(card).getByText(/Goes with MRF Genius Bat/)).toBeInTheDocument();
    expect(aiApi.scenarioRecommendations).toHaveBeenCalledWith({ referenceProductId: 'bat-1', scenario: 'COMPLEMENTARY', budget: undefined }, 4);
  });

  it('does not show a discount when there is none', async () => {
    vi.mocked(aiApi.scenarioRecommendations).mockResolvedValue(reco({ items: [item({ mrp: 350, original_price: null, discount_pct: null })] as never }));
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    const card = await screen.findByTestId('recommendation-card');
    expect(within(card).queryByText(/% OFF/)).toBeNull();
    expect(within(card).queryByText(/Save/)).toBeNull();
  });

  it('applies an explicit budget, which is sent to the server, and can clear it', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    await screen.findByTestId('recommendation-card');
    await user.click(screen.getByTestId('reco-adjust-budget'));
    expect(screen.getByTestId('reco-budget-apply')).toBeDisabled();
    await user.type(screen.getByTestId('reco-budget-input'), '1500');
    await user.click(screen.getByTestId('reco-budget-apply'));
    await waitFor(() => expect(aiApi.scenarioRecommendations).toHaveBeenLastCalledWith({ referenceProductId: 'bat-1', scenario: 'COMPLEMENTARY', budget: 1500 }, 4));
    await user.click(await screen.findByTestId('reco-clear-budget'));
    await waitFor(() => expect(aiApi.scenarioRecommendations).toHaveBeenLastCalledWith({ referenceProductId: 'bat-1', scenario: 'COMPLEMENTARY', budget: undefined }, 4));
  });

  it('switches to premium alternatives only when the customer asks for them', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    await screen.findByTestId('recommendation-card');
    expect(vi.mocked(aiApi.scenarioRecommendations).mock.calls.every(([o]) => o.scenario !== 'UPGRADE')).toBe(true);
    await user.click(screen.getByTestId('reco-show-premium'));
    await waitFor(() => expect(aiApi.scenarioRecommendations).toHaveBeenLastCalledWith(expect.objectContaining({ scenario: 'UPGRADE' }), 4));
  });

  it('labels a widened budget band instead of hiding it', async () => {
    vi.mocked(aiApi.scenarioRecommendations).mockResolvedValue(reco({
      budget: { explicit: null, complementary_ceiling_pct: 0.2, applied: { low: null, high: 3000, source: 'fallback', pct: 0.3, label: 'Widened to up to 30% of the product\'s price (₹3,000) because too few items fit 20%' }, fallback_used: true, tried: [] },
    }));
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    expect(await screen.findByTestId('reco-fallback-note')).toHaveTextContent(/Widened to up to 30%/);
  });

  it('explains an empty result and offers the suggested alternative scenario', async () => {
    const user = userEvent.setup();
    vi.mocked(aiApi.scenarioRecommendations).mockImplementation(async (o) => (o.scenario === 'FREQUENTLY_BOUGHT_TOGETHER'
      ? reco({ items: [], message: 'There is not enough purchase history yet to show what is bought together with this product.', suggestedScenario: 'COMPLEMENTARY', scenario: 'FREQUENTLY_BOUGHT_TOGETHER' })
      : reco()));
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    await screen.findByTestId('recommendation-card');
    await user.click(screen.getByTestId('reco-frequently_bought_together'));
    expect(await screen.findByTestId('reco-empty')).toHaveTextContent(/not enough purchase history/i);
    await user.click(screen.getByRole('button', { name: /see what goes with it/i }));
    await waitFor(() => expect(aiApi.scenarioRecommendations).toHaveBeenLastCalledWith(expect.objectContaining({ scenario: 'COMPLEMENTARY' }), 4));
  });

  it('degrades to a calm message with a retry when the AI service is unavailable', async () => {
    const user = userEvent.setup();
    vi.mocked(aiApi.scenarioRecommendations).mockRejectedValueOnce(new Error('503'));
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    expect(await screen.findByTestId('reco-error')).toHaveTextContent(/rest of the shop works/i);
    await user.click(screen.getByRole('button', { name: /retry/i }));
    expect(await screen.findByTestId('recommendation-card')).toBeInTheDocument();
  });

  it('adds a recommended product to the cart through the existing cart store', async () => {
    const user = userEvent.setup();
    const addItem = vi.fn().mockResolvedValue(undefined);
    useCartStore.setState({ addItem });
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    await user.click(await screen.findByTestId('recommendation-add'));
    await waitFor(() => expect(addItem).toHaveBeenCalledWith({ productId: 'p1', name: 'Arm Guard', price: 350, imageUrl: '/img/a.png' }, 1));
    expect(await screen.findByText(/Arm Guard added to cart/)).toBeInTheDocument();
  });

  it('disables add to cart for a product that is not in stock', async () => {
    vi.mocked(aiApi.scenarioRecommendations).mockResolvedValue(reco({ items: [item({ in_stock: false })] as never }));
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    expect(await screen.findByTestId('recommendation-add')).toBeDisabled();
  });

  it('tells the customer when a family profile is used and that purchase history is not', async () => {
    vi.mocked(aiApi.scenarioRecommendations).mockResolvedValue(reco({ profile: { profile_id: 'f', display_name: 'Aarav', kind: 'FAMILY', personalization_applied: true, personalization_enabled: true } }));
    renderWithProviders(<ScenarioRecommendations productId="bat-1" />);
    expect(await screen.findByText(/Using Aarav's profile/)).toHaveTextContent(/purchase history is not used/i);
  });
});

describe('Home personalised section', () => {
  const emptyProfile = { id: 'p-owner', kind: 'OWNER' as const, displayName: 'Me', relationship: null, ageBand: null, personalizationEnabled: true, createdAt: '', updatedAt: 'u',
    preferences: { sports: [], favoriteBrands: [], favoriteCategories: [], favoriteTeams: [], usageLevel: null, priceMin: null, priceMax: null, sizes: {}, attributes: {}, empty: true } };

  it('shows nothing invented: an account holder with no preferences and no history is asked what they play', async () => {
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue({ activeProfileId: 'p-owner', profiles: [emptyProfile], maxFamilyProfiles: 6 });
    vi.mocked(aiApi.recommendations).mockResolvedValue({ title: 'Recommended for you', personalized: false, because: [], items: [], bundle: null });
    renderWithProviders(<PersonalizedSection />);
    expect(await screen.findByTestId('personalization-prompt')).toBeInTheDocument();
    expect(screen.queryByTestId('recommendation-card')).toBeNull();
    expect(aiApi.scenarioRecommendations).not.toHaveBeenCalled();
  });

  it('keeps the original order-history recommendations for an account holder with no saved preferences', async () => {
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue({ activeProfileId: 'p-owner', profiles: [emptyProfile], maxFamilyProfiles: 6 });
    vi.mocked(aiApi.recommendations).mockResolvedValue(reco({ title: 'Recommended for you', personalized: true }));
    renderWithProviders(<PersonalizedSection />);
    expect(await screen.findByText('Personalized for you')).toBeInTheDocument();
    expect(screen.getByTestId('recommendation-card')).toBeInTheDocument();
    expect(aiApi.recommendations).toHaveBeenCalledWith([], 4);
    expect(aiApi.scenarioRecommendations).not.toHaveBeenCalled();
  });

  it('never uses the account holder\'s history for a family profile, and can switch back', async () => {
    const child = { ...emptyProfile, id: 'p-child', kind: 'FAMILY' as const, displayName: 'Aarav', ageBand: 'CHILD_8_12' as const };
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue({ activeProfileId: 'p-child', profiles: [emptyProfile, child], maxFamilyProfiles: 6 });
    vi.mocked(aiApi.scenarioRecommendations).mockResolvedValue(reco({ personalized: false, items: [], message: 'Add what you play' }));
    renderWithProviders(<PersonalizedSection />);
    expect(await screen.findByTestId('personalization-prompt')).toBeInTheDocument();
    expect(screen.getByTestId('profile-switcher')).toBeInTheDocument();           // the way back to the owner profile is always visible
    expect(aiApi.recommendations).not.toHaveBeenCalled();
  });

  it('personalises nothing when personalisation is switched off for the profile', async () => {
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue({ activeProfileId: 'p-owner', profiles: [{ ...emptyProfile, personalizationEnabled: false }], maxFamilyProfiles: 6 });
    const { container } = renderWithProviders(<PersonalizedSection />);
    await waitFor(() => expect(shopperProfilesApi.overview).toHaveBeenCalled());
    await waitFor(() => expect(container.querySelector('[data-testid="home-personalized"], [data-testid="personalization-prompt"]')).toBeNull());
    expect(aiApi.recommendations).not.toHaveBeenCalled();
    expect(aiApi.scenarioRecommendations).not.toHaveBeenCalled();
  });

  it('shows personalised products with their reasons when they genuinely are', async () => {
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue({ activeProfileId: 'p-owner', profiles: [{ ...emptyProfile, preferences: { ...emptyProfile.preferences, sports: ['Cricket'], empty: false } }], maxFamilyProfiles: 6 });
    vi.mocked(aiApi.scenarioRecommendations).mockResolvedValue(reco({ personalized: true, scenario: 'PERSONALIZED', title: 'Picked for you' }));
    renderWithProviders(<PersonalizedSection />);
    expect(await screen.findByTestId('home-personalized')).toBeInTheDocument();
    expect(aiApi.scenarioRecommendations).toHaveBeenCalledWith({ scenario: 'PERSONALIZED' }, 4);
  });

  it('renders nothing when the AI service is down, so the home page is unaffected', async () => {
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue({ activeProfileId: 'p-owner', profiles: [emptyProfile], maxFamilyProfiles: 6 });
    vi.mocked(aiApi.recommendations).mockRejectedValue(new Error('down'));
    const { container } = renderWithProviders(<PersonalizedSection />);
    await waitFor(() => expect(aiApi.recommendations).toHaveBeenCalled());
    await waitFor(() => expect(container.querySelector('[data-testid="home-personalized"], [data-testid="personalization-prompt"]')).toBeNull());
  });
});
