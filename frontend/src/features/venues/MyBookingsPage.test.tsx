import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { renderWithProviders } from '@/test/render';
import MyBookingsPage from './MyBookingsPage';
import { venuesApi } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import type { Booking } from '@/types/venues';

vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return { ...real, venuesApi: { myBookings: vi.fn(), cancel: vi.fn() } };
});

const booking = (over: Partial<Booking>): Booking => ({
  id: 'b-1', bookingNumber: 'GB-000001', venueId: 'v-1', venueName: 'Green Park Sports Hub', venueCity: 'Bengaluru', courtId: 'c-1', courtName: 'Court 1', sportId: 's-cricket', sportName: 'Cricket', startAt: '2026-11-21T11:30:00Z', endAt: '2026-11-21T12:30:00Z',
  price: 3000, currency: 'INR', status: 'CONFIRMED', heldUntil: '2026-11-16T05:00:00Z', paymentId: 'pay-1', cancelledAt: null, cancelledBy: null, createdAt: '2026-11-16T04:30:00Z', ...over,
});

describe('MyBookingsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    vi.mocked(venuesApi.myBookings).mockResolvedValue([
      booking({ id: 'b-1', bookingNumber: 'GB-000001', status: 'CONFIRMED' }),
      booking({ id: 'b-2', bookingNumber: 'GB-000002', status: 'HELD', paymentId: null }),
      booking({ id: 'b-3', bookingNumber: 'GB-000003', status: 'CANCELLED', cancelledBy: 'OWNER' }),
    ]);
  });

  it('lists the bookings with their status, a pay link only for a held one and a cancel button only for active ones', async () => {
    renderWithProviders(<MyBookingsPage />);
    const cards = await screen.findAllByTestId('booking-card');
    expect(cards).toHaveLength(3);
    expect(within(cards[0]).getByText('Confirmed')).toBeInTheDocument();
    expect(within(cards[0]).queryByRole('link', { name: /pay now/i })).toBeNull();
    expect(within(cards[1]).getByRole('link', { name: /pay now/i })).toHaveAttribute('href', '/bookings/b-2/pay');
    expect(within(cards[1]).getByText('Awaiting payment')).toBeInTheDocument();
    expect(within(cards[2]).queryByTestId('cancel-booking')).toBeNull();
  });

  it('cancels after a confirmation and tells the customer what the server decided about the refund', async () => {
    const user = userEvent.setup();
    vi.mocked(venuesApi.cancel).mockResolvedValue({ booking: booking({ status: 'CANCELLED' }), refundPercent: 50, refundAmount: 1500, refund: 'REQUESTED' });
    renderWithProviders(<MyBookingsPage />);
    const cards = await screen.findAllByTestId('booking-card');
    await user.click(within(cards[0]).getByTestId('cancel-booking'));
    expect(await screen.findByText(/cancel GB-000001\?/i)).toBeInTheDocument();
    expect(venuesApi.cancel).not.toHaveBeenCalled();
    await user.click(screen.getByTestId('confirm-cancel'));

    expect(await screen.findByTestId('cancel-outcome')).toHaveTextContent(/GB-000001 cancelled.*50% refund of .*1,500.*awaiting approval/);
    expect(venuesApi.cancel).toHaveBeenCalledWith('b-1');
  });

  it('can keep a booking instead of cancelling it', async () => {
    const user = userEvent.setup();
    renderWithProviders(<MyBookingsPage />);
    await user.click(within((await screen.findAllByTestId('booking-card'))[0]).getByTestId('cancel-booking'));
    await user.click(await screen.findByRole('button', { name: /keep booking/i }));

    expect(venuesApi.cancel).not.toHaveBeenCalled();
  });

  it('shows the server\'s reason when a cancellation is refused', async () => {
    const user = userEvent.setup();
    vi.mocked(venuesApi.cancel).mockRejectedValue(new AxiosError('x', '409', undefined, undefined,
      { status: 409, data: { code: 'INVALID_STATUS_TRANSITION', message: 'A booking that has already started cannot be cancelled', status: 409 } } as never));
    renderWithProviders(<MyBookingsPage />);
    await user.click(within((await screen.findAllByTestId('booking-card'))[0]).getByTestId('cancel-booking'));
    await user.click(await screen.findByTestId('confirm-cancel'));

    expect(await screen.findByText(/already started cannot be cancelled/i)).toBeInTheDocument();
  });

  it('confirms a payment that has just been made, and asks a signed-out visitor to sign in', async () => {
    renderWithProviders(<MyBookingsPage />, '/bookings?paid=b-1');
    expect(await screen.findByTestId('paid-banner')).toBeInTheDocument();

    useAuthStore.setState({ isAuthenticated: false, user: null });
    renderWithProviders(<MyBookingsPage />);
    expect((await screen.findAllByText(/sign in to see your bookings/i)).length).toBeGreaterThan(0);
  });
});
