import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import OwnerGroundsPage from './OwnerGroundsPage';
import { groundsApi } from '@/api/grounds';
import { useAuthStore } from '@/store/authStore';
import type { Booking, Ground } from '@/types/grounds';

vi.mock('@/api/grounds', async (orig) => {
  const real = await orig<typeof import('@/api/grounds')>();
  return { ...real, groundsApi: { owner: { grounds: vi.fn(), create: vi.fn(), update: vi.fn(), blackouts: vi.fn(), addBlackout: vi.fn(), bookings: vi.fn(), cancel: vi.fn(), noShow: vi.fn() } } };
});
const owner = vi.mocked(groundsApi.owner);

const GROUND: Ground = {
  id: 'g-1', name: 'Green Park Turf', address: '12 Park Road', city: 'Bengaluru', latitude: null, longitude: null, pitchType: 'TURF', facilities: [], photoUrls: [],
  opensAt: '06:00:00', closesAt: '22:00:00', timeZone: 'Asia/Kolkata', slotMinutes: 60, basePrice: 1000, weekendPrice: null, peakPrice: null, peakFrom: null, peakTo: null,
  currency: 'INR', active: true,
};
const BOOKING: Booking = {
  id: 'b-1', bookingNumber: 'GB-000001', groundId: 'g-1', groundName: 'Green Park Turf', groundCity: 'Bengaluru', startAt: '2099-11-21T11:30:00Z', endAt: '2099-11-21T12:30:00Z',
  price: 1000, currency: 'INR', status: 'CONFIRMED', heldUntil: '2099-11-16T05:00:00Z', paymentId: 'pay-1', cancelledAt: null, cancelledBy: null, createdAt: '2099-11-16T04:30:00Z',
};

describe('OwnerGroundsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'o-1', email: 'o@b.c', roles: ['CUSTOMER', 'GROUND_OWNER'] } });
    owner.grounds.mockResolvedValue([{ ground: GROUND, version: 3 }]);
    owner.bookings.mockResolvedValue([BOOKING]);
    owner.blackouts.mockResolvedValue([]);
  });

  it('is restricted to ground owners', async () => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    renderWithProviders(<OwnerGroundsPage />);

    expect(await screen.findByText(/ground owners only/i)).toBeInTheDocument();
    expect(owner.grounds).not.toHaveBeenCalled();
  });

  it('lists my ground and its bookings for the chosen day', async () => {
    renderWithProviders(<OwnerGroundsPage />);

    expect(await screen.findByTestId('owner-ground')).toHaveTextContent('Green Park Turf');
    const rows = await screen.findAllByTestId('owner-booking');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('GB-000001');
    expect(rows[0]).toHaveTextContent('confirmed');
    expect(owner.bookings).toHaveBeenCalledWith('g-1', expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/));
  });

  it('cancels a booking and reports the automatic full refund', async () => {
    const user = userEvent.setup();
    owner.cancel.mockResolvedValue({ booking: { ...BOOKING, status: 'CANCELLED' }, refundPercent: 100, refundAmount: 1000, refund: 'STARTED' });
    renderWithProviders(<OwnerGroundsPage />);
    await user.click(await within((await screen.findAllByTestId('owner-booking'))[0]).findByTestId('owner-cancel'));

    expect(await screen.findByTestId('owner-notice')).toHaveTextContent(/GB-000001 cancelled.*full refund.*started automatically/);
    expect(owner.cancel).toHaveBeenCalledWith('b-1');
  });

  it('offers the no-show action only for a confirmed booking that has ended', async () => {
    const user = userEvent.setup();
    owner.bookings.mockResolvedValue([{ ...BOOKING, startAt: '2020-01-01T10:00:00Z', endAt: '2020-01-01T11:00:00Z' }]);
    owner.noShow.mockResolvedValue({ ...BOOKING, status: 'NO_SHOW' });
    renderWithProviders(<OwnerGroundsPage />);
    await user.click(await screen.findByTestId('owner-noshow'));

    expect(await screen.findByTestId('owner-notice')).toHaveTextContent(/marked as a no-show/);
    expect(owner.noShow).toHaveBeenCalledWith('b-1');
  });

  it('does not offer no-show for a booking in the future', async () => {
    renderWithProviders(<OwnerGroundsPage />);
    await screen.findAllByTestId('owner-booking');

    expect(screen.queryByTestId('owner-noshow')).toBeNull();
  });

  it('adds a blackout and shows the server\'s refusal when a booking overlaps', async () => {
    const user = userEvent.setup();
    const { AxiosError } = await import('axios');
    owner.addBlackout.mockRejectedValue(new AxiosError('x', '409', undefined, undefined,
      { status: 409, data: { code: 'BLACKOUT_CONFLICT', message: '1 booking(s) overlap this period. Cancel them first, then add the blackout.', status: 409 } } as never));
    renderWithProviders(<OwnerGroundsPage />);
    await user.click(await screen.findByTestId('add-blackout'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/from/i), '2099-11-21T09:00');
    await user.type(within(dialog).getByLabelText(/until/i), '2099-11-21T11:00');
    await user.type(within(dialog).getByLabelText(/reason/i), 'Maintenance');
    await user.click(within(dialog).getByTestId('save-blackout'));

    expect(await screen.findByTestId('blackout-error')).toHaveTextContent(/cancel them first/i);
    expect(owner.addBlackout).toHaveBeenCalledWith('g-1', { startAt: expect.stringMatching(/Z$/), endAt: expect.stringMatching(/Z$/), reason: 'Maintenance' });
  });

  it('creates a ground', async () => {
    const user = userEvent.setup();
    owner.create.mockResolvedValue({ ground: GROUND, version: 1 });
    renderWithProviders(<OwnerGroundsPage />);
    await user.click(await screen.findByTestId('new-ground'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^name/i), 'New Turf');
    await user.type(within(dialog).getByLabelText(/^address/i), '1 Road');
    await user.type(within(dialog).getByLabelText(/^city/i), 'Pune');
    await user.click(within(dialog).getByTestId('save-ground'));

    await waitFor(() => expect(owner.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'New Turf', city: 'Pune', timeZone: 'Asia/Kolkata', slotMinutes: 60, basePrice: 1000 })));
  });

  it('sends the version I saw when editing, so a stale edit is refused by the server', async () => {
    const user = userEvent.setup();
    owner.update.mockResolvedValue({ ground: GROUND, version: 4 });
    renderWithProviders(<OwnerGroundsPage />);
    await user.click(await screen.findByTestId('edit-ground'));
    await user.click(within(await screen.findByRole('dialog')).getByTestId('save-ground'));

    await waitFor(() => expect(owner.update).toHaveBeenCalledWith('g-1', expect.objectContaining({ name: 'Green Park Turf' }), true, 3));
  });
});
