import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import AdminSportsPage from './AdminSportsPage';
import { adminApi } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import type { Sport } from '@/types/venues';

vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return { ...real, adminApi: { sports: vi.fn(), addSport: vi.fn(), setSportBookable: vi.fn() } };
});

const SPORTS: Sport[] = [
  { id: 's-1', name: 'Cricket', slug: 'cricket', description: null, status: 'ACTIVE', bookable: true },
  { id: 's-2', name: 'Running', slug: 'running', description: null, status: 'ACTIVE', bookable: false },
];

describe('AdminSportsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'admin-1', email: 'admin@b.c', roles: ['ADMIN'] } });
    vi.mocked(adminApi.sports).mockResolvedValue(SPORTS);
  });

  it('is for administrators only', async () => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    renderWithProviders(<AdminSportsPage />);

    expect(await screen.findByText(/administrators only/i)).toBeInTheDocument();
    expect(adminApi.sports).not.toHaveBeenCalled();
  });

  it('lists the catalogue sports with their bookable switch', async () => {
    renderWithProviders(<AdminSportsPage />);

    const rows = await screen.findAllByTestId('sport-row');
    expect(rows).toHaveLength(2);
    expect(screen.getByLabelText('Bookable: Cricket')).toBeChecked();
    expect(screen.getByLabelText('Bookable: Running')).not.toBeChecked();
  });

  it('adds a sport with a slug made from its name and the bookable flag', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.addSport).mockResolvedValue({ id: 's-3', name: 'Table Squash', slug: 'table-squash', bookable: true });
    renderWithProviders(<AdminSportsPage />);
    await screen.findAllByTestId('sport-row');
    await user.type(screen.getByLabelText(/sport name/i), 'Table Squash');
    await user.click(screen.getByTestId('add-sport'));

    await waitFor(() => expect(adminApi.addSport).toHaveBeenCalledWith({ name: 'Table Squash', slug: 'table-squash', description: undefined, bookable: true }));
    expect(await screen.findByTestId('sports-message')).toHaveTextContent(/Table Squash was added and can now be booked/);
  });

  it('can add a shop-only sport by turning the switch off', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.addSport).mockResolvedValue({ id: 's-4', name: 'Archery', slug: 'archery', bookable: false });
    renderWithProviders(<AdminSportsPage />);
    await screen.findAllByTestId('sport-row');
    await user.type(screen.getByLabelText(/sport name/i), 'Archery');
    await user.click(screen.getAllByRole('checkbox').find((c) => c.closest('form'))!);
    await user.click(screen.getByTestId('add-sport'));

    await waitFor(() => expect(adminApi.addSport).toHaveBeenCalledWith(expect.objectContaining({ slug: 'archery', bookable: false })));
  });

  it('turns an existing sport bookable', async () => {
    const user = userEvent.setup();
    vi.mocked(adminApi.setSportBookable).mockResolvedValue({ ...SPORTS[1], bookable: true });
    renderWithProviders(<AdminSportsPage />);
    await user.click(await screen.findByLabelText('Bookable: Running'));

    await waitFor(() => expect(adminApi.setSportBookable).toHaveBeenCalledWith(SPORTS[1], true));
    expect(await screen.findByTestId('sports-message')).toHaveTextContent(/Running can now be booked/);
  });
});
