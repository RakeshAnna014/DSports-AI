import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import GroundDetailPage from './GroundDetailPage';
import { groundsApi } from '@/api/grounds';
import { useAuthStore } from '@/store/authStore';
import type { Ground, Slot } from '@/types/grounds';

vi.mock('@/api/grounds', async (orig) => {
  const real = await orig<typeof import('@/api/grounds')>();
  return { ...real, groundsApi: { get: vi.fn(), slots: vi.fn(), hold: vi.fn() } };
});

const GROUND: Ground = {
  id: 'g-1', name: 'Green Park Turf', address: '12 Park Road', city: 'Bengaluru', latitude: null, longitude: null, pitchType: 'TURF', facilities: ['Floodlights'], photoUrls: [],
  opensAt: '06:00:00', closesAt: '22:00:00', timeZone: 'Asia/Kolkata', slotMinutes: 60, basePrice: 1000, weekendPrice: 1500, peakPrice: 2000, peakFrom: '18:00:00', peakTo: '21:00:00',
  currency: 'INR', active: true,
};

/** 06:00 IST onwards, hourly, 1000 each; the slot at index 2 (08:00) is booked. */
const SLOTS: Slot[] = Array.from({ length: 5 }, (_, i) => {
  const start = new Date(Date.UTC(2026, 10, 21, 0, 30) + i * 3600_000);
  return { start: start.toISOString(), end: new Date(start.getTime() + 3600_000).toISOString(), price: 1000, available: i !== 2, unavailableReason: i === 2 ? 'BOOKED' : null };
});

const apiError = (status: number, code: string, message: string) =>
  new AxiosError('failed', String(status), undefined, undefined, { status, data: { code, message, status } } as never);

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/grounds/:id" element={<GroundDetailPage />} />
    <Route path="/bookings/:id/pay" element={<div>PAY PAGE</div>} />
    <Route path="/login" element={<div>LOGIN PAGE</div>} />
  </Routes>, '/grounds/g-1?date=2026-11-21');

describe('GroundDetailPage', () => {
  beforeEach(() => {
    vi.mocked(groundsApi.get).mockResolvedValue(GROUND);
    vi.mocked(groundsApi.slots).mockResolvedValue(SLOTS);
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
  });

  it('shows the slots in the ground\'s own time zone, with the booked one disabled and its reason', async () => {
    renderPage();
    const grid = await screen.findByTestId('slot-grid');
    const slots = within(grid).getAllByTestId('slot');
    expect(slots).toHaveLength(5);
    expect(slots[0]).toHaveTextContent('06:00–07:00');
    expect(slots[2]).toBeDisabled();
    expect(slots[2]).toHaveTextContent('Booked');
    expect(groundsApi.slots).toHaveBeenCalledWith('g-1', '2026-11-21');
  });

  it('builds a run of consecutive slots, shows the total, and a non-adjacent click starts a new run', async () => {
    const user = userEvent.setup();
    renderPage();
    const slots = within(await screen.findByTestId('slot-grid')).getAllByTestId('slot');
    await user.click(slots[0]);
    await user.click(slots[1]);
    expect(screen.getByTestId('selection-summary')).toHaveTextContent('06:00');
    expect(screen.getByTestId('selection-summary')).toHaveTextContent(/08:00.*2,000/);
    await user.click(slots[4]);
    expect(screen.getByTestId('selection-summary')).toHaveTextContent(/10:00.*1,000/);
  });

  it('holds the chosen run with an Idempotency-Key and goes to the payment page', async () => {
    const user = userEvent.setup();
    vi.mocked(groundsApi.hold).mockResolvedValue({ id: 'b-1' } as never);
    renderPage();
    const slots = within(await screen.findByTestId('slot-grid')).getAllByTestId('slot');
    await user.click(slots[0]);
    await user.click(slots[1]);
    await user.click(screen.getByTestId('hold-and-pay'));

    await screen.findByText('PAY PAGE');
    expect(groundsApi.hold).toHaveBeenCalledWith({ groundId: 'g-1', startAt: SLOTS[0].start, endAt: SLOTS[1].end }, expect.stringMatching(/^[0-9a-f-]{36}$/));
  });

  it('tells the customer when someone else took the slot and refreshes the list', async () => {
    const user = userEvent.setup();
    vi.mocked(groundsApi.hold).mockRejectedValue(apiError(409, 'SLOT_UNAVAILABLE', 'That time is no longer available.'));
    renderPage();
    const slots = within(await screen.findByTestId('slot-grid')).getAllByTestId('slot');
    await user.click(slots[0]);
    await user.click(screen.getByTestId('hold-and-pay'));

    expect(await screen.findByTestId('hold-message')).toHaveTextContent(/just took that time/i);
    await waitFor(() => expect(vi.mocked(groundsApi.slots).mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByText('PAY PAGE')).toBeNull();
  });

  it('explains the hold limit', async () => {
    const user = userEvent.setup();
    vi.mocked(groundsApi.hold).mockRejectedValue(apiError(409, 'HOLD_LIMIT_REACHED', 'You already have 2 unpaid slot(s) on hold.'));
    renderPage();
    await user.click((await screen.findAllByTestId('slot'))[0]);
    await user.click(screen.getByTestId('hold-and-pay'));

    expect(await screen.findByTestId('hold-message')).toHaveTextContent(/unpaid slots on hold/i);
  });

  it('asks a signed-out visitor to sign in instead of holding anything', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ isAuthenticated: false, user: null });
    renderPage();
    await user.click((await screen.findAllByTestId('slot'))[0]);
    await user.click(screen.getByRole('button', { name: /sign in to book/i }));

    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
    expect(groundsApi.hold).not.toHaveBeenCalled();
  });
});
