import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import AdminVenuesPage from './AdminVenuesPage';
import { adminApi, venuesApi } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { BADMINTON, CRICKET, FOOTBALL, MANAGED } from '@/test/venueFixtures';

vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return {
    ...real,
    adminApi: { owners: vi.fn() },
    venuesApi: { sports: vi.fn(), admin: { venues: vi.fn(), create: vi.fn(), update: vi.fn(), addCourt: vi.fn(), updateCourt: vi.fn(), blackouts: vi.fn(), addBlackout: vi.fn() } },
  };
});
const admin = vi.mocked(venuesApi.admin);

const OWNERS = [
  { id: 'o-1', email: 'asha@example.com', firstName: 'Asha', lastName: 'Rao', grantedAt: null },
  { id: 'o-2', email: 'ben@example.com', firstName: 'Ben', lastName: 'Roy', grantedAt: null },
];

describe('AdminVenuesPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'admin-1', email: 'admin@b.c', roles: ['ADMIN'] } });
    admin.venues.mockResolvedValue([MANAGED]);
    admin.blackouts.mockResolvedValue([]);
    vi.mocked(adminApi.owners).mockResolvedValue(OWNERS);
    vi.mocked(venuesApi.sports).mockResolvedValue([CRICKET, FOOTBALL, BADMINTON].map((s) => ({ ...s, description: null })));
  });

  it('is for administrators only and loads nothing for anyone else', async () => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'o-1', email: 'o@b.c', roles: ['GROUND_OWNER'] } });
    renderWithProviders(<AdminVenuesPage />);

    expect(await screen.findByText(/administrators only/i)).toBeInTheDocument();
    expect(admin.venues).not.toHaveBeenCalled();
  });

  it('shows every venue with its owner', async () => {
    renderWithProviders(<AdminVenuesPage />);

    expect(await screen.findByTestId('managed-venue')).toHaveTextContent('Green Park Sports Hub');
    await waitFor(() => expect(screen.getByTestId('venue-owner-line')).toHaveTextContent('Owner: asha@example.com'));
    expect(screen.queryByTestId('owner-removed')).toBeNull();
  });

  it('creates a venue on an owner\'s behalf and will not save without choosing the owner', async () => {
    const user = userEvent.setup();
    admin.create.mockResolvedValue(MANAGED);
    renderWithProviders(<AdminVenuesPage />);
    await user.click(await screen.findByTestId('new-venue'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^name/i), 'Built by admin');
    await user.type(within(dialog).getByLabelText(/^address/i), '1 Road');
    await user.type(within(dialog).getByLabelText(/^city/i), 'Delhi');
    expect(within(dialog).getByTestId('save-venue')).toBeDisabled();
    await user.click(within(dialog).getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: 'ben@example.com' }));
    await user.click(within(dialog).getByTestId('save-venue'));

    await waitFor(() => expect(admin.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'Built by admin', city: 'Delhi' }), 'o-2'));
  });

  it('flags a venue whose owner lost the role and reassigns it to another owner, which sends the new owner with the version seen', async () => {
    const user = userEvent.setup();
    admin.venues.mockResolvedValue([{ ...MANAGED, ownerEligible: false }]);
    admin.update.mockResolvedValue({ ...MANAGED, ownerId: 'o-2' });
    renderWithProviders(<AdminVenuesPage />);

    expect(await screen.findByTestId('managed-venue')).toHaveTextContent('owner removed');
    expect(await screen.findByTestId('owner-removed')).toHaveTextContent(/hidden from search until you reassign/i);
    await user.click(screen.getByTestId('reassign-venue'));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent(/no longer holds the ground-owner role/i);
    await user.click(within(dialog).getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: 'ben@example.com' }));
    await user.click(within(dialog).getByTestId('save-venue'));

    await waitFor(() => expect(admin.update).toHaveBeenCalledWith('v-1', expect.objectContaining({ name: 'Green Park Sports Hub' }), true, 3, 'o-2'));
  });

  it('does not send an owner when only the details change', async () => {
    const user = userEvent.setup();
    admin.update.mockResolvedValue(MANAGED);
    renderWithProviders(<AdminVenuesPage />);
    await user.click(await screen.findByTestId('edit-venue'));
    await user.click(within(await screen.findByRole('dialog')).getByTestId('save-venue'));

    await waitFor(() => expect(admin.update).toHaveBeenCalledWith('v-1', expect.any(Object), true, 3, undefined));
  });

  it('adds a court to an owner\'s venue through the admin routes', async () => {
    const user = userEvent.setup();
    admin.addCourt.mockResolvedValue(MANAGED.courts[0]);
    renderWithProviders(<AdminVenuesPage />);
    await user.click(await screen.findByTestId('add-court'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/court name/i), 'Court 9');
    await user.click(within(dialog).getByLabelText('Cricket'));
    await user.click(within(dialog).getByTestId('save-court'));

    await waitFor(() => expect(admin.addCourt).toHaveBeenCalledWith('v-1', expect.objectContaining({ name: 'Court 9', sportIds: ['s-cricket'] })));
  });
});
