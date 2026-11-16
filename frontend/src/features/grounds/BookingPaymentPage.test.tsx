import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import BookingPaymentPage from './BookingPaymentPage';
import { groundsApi } from '@/api/grounds';
import { paymentsApi } from '@/api/payments';
import type { Booking } from '@/types/grounds';

vi.mock('@/api/grounds', async (orig) => {
  const real = await orig<typeof import('@/api/grounds')>();
  return { ...real, groundsApi: { getBooking: vi.fn() } };
});
vi.mock('@/api/payments', () => ({ paymentsApi: { createPayment: vi.fn(), capturePayment: vi.fn() } }));

const inMinutes = (m: number) => new Date(Date.now() + m * 60_000).toISOString();

const booking = (over: Partial<Booking> = {}): Booking => ({
  id: 'b-1', bookingNumber: 'GB-000001', groundId: 'g-1', groundName: 'Green Park Turf', groundCity: 'Bengaluru', startAt: inMinutes(60 * 48), endAt: inMinutes(60 * 49),
  price: 3000, currency: 'INR', status: 'HELD', heldUntil: inMinutes(9), paymentId: null, cancelledAt: null, cancelledBy: null, createdAt: new Date().toISOString(), ...over,
});

const apiError = (status: number, code: string, message: string) =>
  new AxiosError('failed', String(status), undefined, undefined, { status, data: { code, message, status } } as never);

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/bookings/:id/pay" element={<BookingPaymentPage />} />
    <Route path="/bookings" element={<div>MY BOOKINGS</div>} />
  </Routes>, '/bookings/b-1/pay');

describe('BookingPaymentPage', () => {
  beforeEach(() => {
    vi.mocked(groundsApi.getBooking).mockResolvedValue(booking());
    vi.mocked(paymentsApi.createPayment).mockResolvedValue({ id: 'pay-1', status: 'AUTHORIZED' } as never);
    vi.mocked(paymentsApi.capturePayment).mockResolvedValue({ id: 'pay-1', status: 'SUCCESS' } as never);
  });

  it('shows the amount and a countdown while the slot is held', async () => {
    renderPage();
    expect(await screen.findByTestId('booking-amount')).toHaveTextContent('3,000');
    expect(screen.getByTestId('hold-countdown')).toHaveTextContent(/held for \d+:\d\d/);
    expect(screen.getByText(/no real money moves/i)).toBeInTheDocument();
  });

  it('pays through the payments API with the booking id as the order id and the booking\'s own price, then goes to my bookings', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId('pay-booking'));

    await screen.findByText('MY BOOKINGS');
    expect(paymentsApi.createPayment).toHaveBeenCalledWith({ orderId: 'b-1', amount: 3000, currency: 'INR', paymentMethod: 'UPI', paymentProvider: 'MOCK' });
    expect(paymentsApi.capturePayment).toHaveBeenCalledWith('pay-1');
  });

  it('says clearly when the slot was lost while paying and a refund has been started', async () => {
    const user = userEvent.setup();
    vi.mocked(paymentsApi.capturePayment).mockRejectedValue(apiError(409, 'PAID_ITEM_NO_LONGER_AVAILABLE', 'Your payment was received, but what you paid for is no longer available. A full refund has been started automatically.'));
    renderPage();
    await user.click(await screen.findByTestId('pay-booking'));

    expect(await screen.findByTestId('pay-error')).toHaveTextContent(/full refund has been started/i);
    expect(screen.queryByText('MY BOOKINGS')).toBeNull();
  });

  it('offers a safe retry of the same payment when the gateway times out', async () => {
    const user = userEvent.setup();
    vi.mocked(paymentsApi.capturePayment).mockRejectedValueOnce(apiError(504, 'GATEWAY_TIMEOUT', 'The payment gateway did not respond in time.')).mockResolvedValue({ id: 'pay-1', status: 'SUCCESS' } as never);
    renderPage();
    await user.click(await screen.findByTestId('pay-booking'));
    expect(await screen.findByTestId('pay-error')).toHaveTextContent(/retrying is safe/i);

    await user.click(screen.getByRole('button', { name: /retry payment/i }));
    await screen.findByText('MY BOOKINGS');
    expect(paymentsApi.createPayment).toHaveBeenCalledTimes(1);
    expect(paymentsApi.capturePayment).toHaveBeenCalledTimes(2);
  });

  it('shows why a booking can no longer be paid and offers no pay button', async () => {
    vi.mocked(groundsApi.getBooking).mockResolvedValue(booking({ heldUntil: inMinutes(-1) }));
    renderPage();
    expect(await screen.findByTestId('hold-lapsed')).toBeInTheDocument();
    expect(screen.getByTestId('pay-booking')).toBeDisabled();
  });

  it('does not offer payment for a booking that is already confirmed or expired', async () => {
    vi.mocked(groundsApi.getBooking).mockResolvedValue(booking({ status: 'CONFIRMED' }));
    renderPage();
    expect(await screen.findByText(/paid and confirmed/i)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('pay-booking')).toBeDisabled());
  });
});
