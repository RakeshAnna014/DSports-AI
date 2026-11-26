import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import VenueDetailPage from './VenueDetailPage';
import { venuesApi } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { apiError, COURT_1, COURT_2, CRICKET, FOOTBALL, SLOTS, VENUE } from '@/test/venueFixtures';

vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return { ...real, venuesApi: { get: vi.fn(), slots: vi.fn(), hold: vi.fn() } };
});

const renderPage = (url = '/venues/v-1?sport=cricket&date=2026-11-21') => renderWithProviders(
  <Routes>
    <Route path="/venues/:id" element={<VenueDetailPage />} />
    <Route path="/bookings/:id/pay" element={<div>PAY PAGE</div>} />
    <Route path="/login" element={<div>LOGIN PAGE</div>} />
  </Routes>, url);

describe('VenueDetailPage', () => {
  beforeEach(() => {
    vi.mocked(venuesApi.get).mockResolvedValue({ venue: VENUE, courts: [COURT_1, COURT_2], acceptingBookings: true });
    vi.mocked(venuesApi.slots).mockResolvedValue(SLOTS);
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
  });

  it('opens on the first court that offers the chosen sport and shows its slots in the venue\'s own time zone, the booked one disabled with its reason', async () => {
    renderPage('/venues/v-1?sport=badminton&date=2026-11-21');

    const grid = await screen.findByTestId('slot-grid');
    const slots = within(grid).getAllByTestId('slot');
    expect(slots).toHaveLength(5);
    expect(slots[0]).toHaveTextContent('06:00–07:00');
    expect(slots[2]).toBeDisabled();
    expect(slots[2]).toHaveTextContent('Booked');
    expect(venuesApi.slots).toHaveBeenCalledWith('v-1', 'c-2', '2026-11-21');
    expect(screen.getAllByTestId('court-chip')[1]).toHaveAttribute('aria-pressed', 'true');
  });

  it('lets the customer switch court, which loads that court\'s slots and offers its own sports', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId('slot-grid');
    expect(screen.getAllByTestId('court-sport').map((b) => b.textContent)).toEqual(['Cricket', 'Football']);

    await user.click(screen.getAllByTestId('court-chip')[1]);

    await waitFor(() => expect(venuesApi.slots).toHaveBeenLastCalledWith('v-1', 'c-2', '2026-11-21'));
    expect(screen.getAllByTestId('court-sport').map((b) => b.textContent)).toEqual(['Badminton']);
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

  it('holds the chosen run for the chosen court and sport with an Idempotency-Key and goes to the payment page', async () => {
    const user = userEvent.setup();
    vi.mocked(venuesApi.hold).mockResolvedValue({ id: 'b-1' } as never);
    renderPage();
    const slots = within(await screen.findByTestId('slot-grid')).getAllByTestId('slot');
    await user.click(slots[0]);
    await user.click(slots[1]);
    await user.click(screen.getByTestId('hold-and-pay'));

    await screen.findByText('PAY PAGE');
    expect(venuesApi.hold).toHaveBeenCalledWith({ courtId: 'c-1', sportId: CRICKET.id, startAt: SLOTS[0].start, endAt: SLOTS[1].end }, expect.stringMatching(/^[0-9a-f-]{36}$/));
  });

  it('books the sport picked on the court, not just the one in the address', async () => {
    const user = userEvent.setup();
    vi.mocked(venuesApi.hold).mockResolvedValue({ id: 'b-1' } as never);
    renderPage();
    await screen.findByTestId('slot-grid');
    await user.click(screen.getAllByTestId('court-sport')[1]);
    await user.click(within(screen.getByTestId('slot-grid')).getAllByTestId('slot')[0]);
    await user.click(screen.getByTestId('hold-and-pay'));

    await screen.findByText('PAY PAGE');
    expect(venuesApi.hold).toHaveBeenCalledWith(expect.objectContaining({ courtId: 'c-1', sportId: FOOTBALL.id }), expect.any(String));
  });

  it('tells the customer when someone else took the slot and refreshes the list', async () => {
    const user = userEvent.setup();
    vi.mocked(venuesApi.hold).mockRejectedValue(apiError(409, 'SLOT_UNAVAILABLE', 'That time is no longer available.'));
    renderPage();
    const slots = within(await screen.findByTestId('slot-grid')).getAllByTestId('slot');
    await user.click(slots[0]);
    await user.click(screen.getByTestId('hold-and-pay'));

    expect(await screen.findByTestId('hold-message')).toHaveTextContent(/just took that time/i);
    await waitFor(() => expect(vi.mocked(venuesApi.slots).mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByText('PAY PAGE')).toBeNull();
  });

  it('explains the hold limit and a venue that stopped taking bookings', async () => {
    const user = userEvent.setup();
    vi.mocked(venuesApi.hold).mockRejectedValueOnce(apiError(409, 'HOLD_LIMIT_REACHED', 'You already have 2 unpaid slot(s) on hold.'));
    renderPage();
    await user.click((await screen.findAllByTestId('slot'))[0]);
    await user.click(screen.getByTestId('hold-and-pay'));
    expect(await screen.findByTestId('hold-message')).toHaveTextContent(/unpaid slots on hold/i);

    vi.mocked(venuesApi.hold).mockRejectedValueOnce(apiError(409, 'VENUE_NOT_ACCEPTING_BOOKINGS', 'not taking bookings'));
    await user.click(screen.getByTestId('hold-and-pay'));
    await waitFor(() => expect(screen.getByTestId('hold-message')).toHaveTextContent(/not taking new bookings/i));
  });

  it('shows a venue that is not taking bookings with every slot disabled', async () => {
    vi.mocked(venuesApi.get).mockResolvedValue({ venue: VENUE, courts: [COURT_1], acceptingBookings: false });
    renderPage();

    expect(await screen.findByTestId('not-accepting')).toHaveTextContent(/not taking new bookings/i);
    const slots = within(await screen.findByTestId('slot-grid')).getAllByTestId('slot');
    expect(slots.every((s) => (s as HTMLButtonElement).disabled)).toBe(true);
  });

  it('asks a signed-out visitor to sign in instead of holding anything', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ isAuthenticated: false, user: null });
    renderPage();
    await user.click((await screen.findAllByTestId('slot'))[0]);
    await user.click(screen.getByRole('button', { name: /sign in to book/i }));

    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
    expect(venuesApi.hold).not.toHaveBeenCalled();
  });

  it('says so when the venue has no courts', async () => {
    vi.mocked(venuesApi.get).mockResolvedValue({ venue: VENUE, courts: [], acceptingBookings: true });
    renderPage();

    expect(await screen.findByText(/no courts open for booking yet/i)).toBeInTheDocument();
  });
});
