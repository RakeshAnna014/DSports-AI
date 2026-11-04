import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { pick, renderWithProviders } from '@/test/render';
import ProfilesPanel from './ProfilesPanel';
import ProfileSwitcher from '@/components/profile/ProfileSwitcher';
import { shopperProfilesApi } from '@/api/shopperProfiles';
import { catalogApi } from '@/api/catalog';
import { useAuthStore } from '@/store/authStore';
import type { ProfilesOverview, ShopperProfile } from '@/types/shopperProfile';

vi.mock('@/api/shopperProfiles', () => ({
  shopperProfilesApi: { overview: vi.fn(), select: vi.fn(), savePreferences: vi.fn(), resetPreferences: vi.fn(), createFamily: vi.fn(), updateFamily: vi.fn(), deleteFamily: vi.fn() },
}));
vi.mock('@/api/catalog', () => ({ catalogApi: { getSports: vi.fn(), getBrands: vi.fn(), getCategories: vi.fn() } }));

const empty = { sports: [], favoriteBrands: [], favoriteCategories: [], favoriteTeams: [], usageLevel: null, priceMin: null, priceMax: null, sizes: {}, attributes: {}, empty: true };
const OWNER: ShopperProfile = { id: 'p-owner', kind: 'OWNER', displayName: 'Me', relationship: null, ageBand: null, personalizationEnabled: true, preferences: { ...empty, sports: ['Cricket'], empty: false }, createdAt: '', updatedAt: 'u1' };
const CHILD: ShopperProfile = { id: 'p-child', kind: 'FAMILY', displayName: 'Aarav', relationship: 'Son', ageBand: 'CHILD_8_12', personalizationEnabled: true, preferences: empty, createdAt: '', updatedAt: 'u2' };
const overview = (profiles: ShopperProfile[], active = 'p-owner'): ProfilesOverview => ({ activeProfileId: active, profiles, maxFamilyProfiles: 6 });

beforeEach(() => {
  useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u', email: 'a@b.c', roles: ['CUSTOMER'] } });
  vi.mocked(catalogApi.getSports).mockResolvedValue([{ id: 's1', name: 'Cricket' }, { id: 's2', name: 'Football' }] as never);
  vi.mocked(catalogApi.getBrands).mockResolvedValue([{ id: 'b1', name: 'MRF' }] as never);
  vi.mocked(catalogApi.getCategories).mockResolvedValue([{ id: 'c1', name: 'Bat' }] as never);
  vi.mocked(shopperProfilesApi.overview).mockResolvedValue(overview([OWNER, CHILD]));
});

describe('Profiles panel', () => {
  it('clearly distinguishes the account holder from a family member and marks the active profile', async () => {
    renderWithProviders(<ProfilesPanel />);
    expect(await screen.findByTestId('profile-chip-owner')).toHaveTextContent('Me (account holder) · active');
    expect(screen.getByTestId('profile-chip-family')).toHaveTextContent('Aarav · family member');
    expect(screen.getByText(/cannot sign in/i)).toBeInTheDocument();
    expect(screen.getByText(/age band/i)).toBeInTheDocument();
  });

  it('switches the active profile through the backend', async () => {
    const user = userEvent.setup();
    vi.mocked(shopperProfilesApi.select).mockResolvedValue(CHILD);
    renderWithProviders(<ProfilesPanel />);
    await user.click(await screen.findByTestId('profile-chip-family'));
    expect(screen.getByRole('heading', { name: /aarav's preferences/i }) ?? screen.getByText(/Aarav's preferences/)).toBeTruthy();
    await user.click(screen.getByTestId('profile-use'));
    await waitFor(() => expect(shopperProfilesApi.select).toHaveBeenCalledWith('p-child'));
  });

  it('adds a family member with an age band only (no date of birth) and needs both name and age band', async () => {
    const user = userEvent.setup();
    vi.mocked(shopperProfilesApi.createFamily).mockResolvedValue({ ...CHILD, id: 'p-new', displayName: 'Meera' });
    renderWithProviders(<ProfilesPanel />);
    await user.click(await screen.findByTestId('family-add'));
    const save = screen.getByTestId('family-save');
    expect(save).toBeDisabled();
    await user.type(screen.getByTestId('family-name'), 'Meera');
    expect(save).toBeDisabled();                                                       // still no age band
    await pick(user, /age band/i, '13 to 17');
    await user.click(save);
    await waitFor(() => expect(shopperProfilesApi.createFamily).toHaveBeenCalledWith({ name: 'Meera', relationship: undefined, ageBand: 'TEEN_13_17' }));
    expect(JSON.stringify(vi.mocked(shopperProfilesApi.createFamily).mock.calls[0][0])).not.toMatch(/birth|dob/i);
  });

  it('asks before deleting a family profile and deletes nothing if the customer keeps it', async () => {
    const user = userEvent.setup();
    vi.mocked(shopperProfilesApi.deleteFamily).mockResolvedValue(undefined);
    renderWithProviders(<ProfilesPanel />);
    await user.click(await screen.findByTestId('profile-chip-family'));
    await user.click(screen.getByTestId('family-delete'));
    expect(screen.getByText(/permanently deletes this family profile/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /keep it/i }));
    expect(shopperProfilesApi.deleteFamily).not.toHaveBeenCalled();
    await user.click(screen.getByTestId('family-delete'));
    await user.click(screen.getByTestId('family-delete-confirm'));
    await waitFor(() => expect(shopperProfilesApi.deleteFamily).toHaveBeenCalledWith('p-child'));
  });

  it('disables adding more family members at the limit', async () => {
    const six = Array.from({ length: 6 }, (_, i) => ({ ...CHILD, id: `f${i}`, displayName: `M${i}` }));
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue(overview([OWNER, ...six]));
    renderWithProviders(<ProfilesPanel />);
    expect(await screen.findByTestId('family-add')).toBeDisabled();
  });

  it('saves optional preferences with sports taken from the real catalogue, and blocks an inverted price range', async () => {
    const user = userEvent.setup();
    vi.mocked(shopperProfilesApi.savePreferences).mockResolvedValue(OWNER);
    renderWithProviders(<ProfilesPanel />);
    await screen.findByTestId('preferences-editor');
    await user.click(screen.getByRole('combobox', { name: /^sports$/i }));
    expect(within(screen.getByRole('listbox')).getAllByRole('option').map((o) => o.textContent)).toEqual(['Football']);   // Cricket is already chosen
    await user.click(within(screen.getByRole('listbox')).getByText('Football'));
    await user.type(screen.getByLabelText(/price from/i), '900');
    await user.type(screen.getByLabelText(/price up to/i), '100');
    expect(screen.getByTestId('preferences-save')).toBeDisabled();
    await user.clear(screen.getByLabelText(/price up to/i));
    await user.type(screen.getByLabelText(/price up to/i), '5000');
    await user.click(screen.getByTestId('preferences-save'));
    await waitFor(() => expect(shopperProfilesApi.savePreferences).toHaveBeenCalled());
    const [id, payload] = vi.mocked(shopperProfilesApi.savePreferences).mock.calls[0];
    expect(id).toBe('p-owner');
    expect(payload).toMatchObject({ sports: ['Cricket', 'Football'], priceMin: 900, priceMax: 5000, personalizationEnabled: true });
  });

  it('resets preferences only on request', async () => {
    const user = userEvent.setup();
    vi.mocked(shopperProfilesApi.resetPreferences).mockResolvedValue({ ...OWNER, preferences: empty });
    renderWithProviders(<ProfilesPanel />);
    await user.click(await screen.findByTestId('preferences-reset'));
    await waitFor(() => expect(shopperProfilesApi.resetPreferences).toHaveBeenCalledWith('p-owner'));
  });

  it('shows an error state instead of a broken form when profiles cannot be loaded', async () => {
    vi.mocked(shopperProfilesApi.overview).mockRejectedValue(new Error('down'));
    renderWithProviders(<ProfilesPanel />);
    expect(await screen.findByText(/could not be loaded/i)).toBeInTheDocument();
  });
});

describe('Profile switcher', () => {
  it('renders nothing with a single profile', async () => {
    vi.mocked(shopperProfilesApi.overview).mockResolvedValue(overview([OWNER]));
    const { container } = renderWithProviders(<ProfileSwitcher />);
    await waitFor(() => expect(shopperProfilesApi.overview).toHaveBeenCalled());
    expect(container.querySelector('[data-testid="profile-switcher"]')).toBeNull();
  });

  it('switches profile on click and does nothing when the active chip is clicked', async () => {
    const user = userEvent.setup();
    vi.mocked(shopperProfilesApi.select).mockResolvedValue(CHILD);
    renderWithProviders(<ProfileSwitcher />);
    const group = await screen.findByTestId('profile-switcher');
    await user.click(within(group).getByTestId('switch-owner'));
    expect(shopperProfilesApi.select).not.toHaveBeenCalled();
    await user.click(within(group).getByTestId('switch-family'));
    await waitFor(() => expect(shopperProfilesApi.select).toHaveBeenCalledWith('p-child'));
  });
});
