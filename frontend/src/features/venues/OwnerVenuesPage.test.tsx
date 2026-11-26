import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import OwnerVenuesPage from './OwnerVenuesPage';
import { venuesApi } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { apiError, BADMINTON, BOOKING, COURT_1, CRICKET, FOOTBALL, MANAGED, VENUE } from '@/test/venueFixtures';

vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return {
    ...real,
    venuesApi: {
      sports: vi.fn(),
      owner: { venues: vi.fn(), create: vi.fn(), update: vi.fn(), addCourt: vi.fn(), updateCourt: vi.fn(), blackouts: vi.fn(), addBlackout: vi.fn(), bookings: vi.fn(), cancel: vi.fn(), noShow: vi.fn() },
    },
  };
});
const owner = vi.mocked(venuesApi.owner);

describe('OwnerVenuesPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'o-1', email: 'o@b.c', roles: ['CUSTOMER', 'GROUND_OWNER'] } });
    owner.venues.mockResolvedValue([MANAGED]);
    owner.bookings.mockResolvedValue([BOOKING]);
    owner.blackouts.mockResolvedValue([]);
    vi.mocked(venuesApi.sports).mockResolvedValue([CRICKET, FOOTBALL, BADMINTON].map((s) => ({ ...s, description: null })));
  });

  it('is restricted to venue owners', async () => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    renderWithProviders(<OwnerVenuesPage />);

    expect(await screen.findByText(/venue owners only/i)).toBeInTheDocument();
    expect(owner.venues).not.toHaveBeenCalled();
  });

  it('lists my venue with its courts and sports, and its bookings for the chosen day', async () => {
    renderWithProviders(<OwnerVenuesPage />);

    expect(await screen.findByTestId('managed-venue')).toHaveTextContent('Green Park Sports Hub');
    const courts = await screen.findAllByTestId('managed-court');
    expect(courts).toHaveLength(2);
    expect(courts[0]).toHaveTextContent('Court 1');
    expect(courts[0]).toHaveTextContent('Cricket, Football');
    expect(courts[1]).toHaveTextContent('Badminton');
    const rows = await screen.findAllByTestId('owner-booking');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('GB-000001');
    expect(rows[0]).toHaveTextContent('Court 1 · Cricket');
    expect(rows[0]).toHaveTextContent('confirmed');
    expect(owner.bookings).toHaveBeenCalledWith('v-1', { date: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/) });
  });

  it('cancels a booking and reports the automatic full refund', async () => {
    const user = userEvent.setup();
    owner.cancel.mockResolvedValue({ booking: { ...BOOKING, status: 'CANCELLED' }, refundPercent: 100, refundAmount: 1000, refund: 'STARTED' });
    renderWithProviders(<OwnerVenuesPage />);
    await user.click(await within((await screen.findAllByTestId('owner-booking'))[0]).findByTestId('owner-cancel'));

    expect(await screen.findByTestId('owner-notice')).toHaveTextContent(/GB-000001 cancelled.*full refund.*started automatically/);
    expect(owner.cancel).toHaveBeenCalledWith('b-1');
  });

  it('offers the no-show action only for a confirmed booking that has ended', async () => {
    const user = userEvent.setup();
    owner.bookings.mockResolvedValue([{ ...BOOKING, startAt: '2020-01-01T10:00:00Z', endAt: '2020-01-01T11:00:00Z' }]);
    owner.noShow.mockResolvedValue({ ...BOOKING, status: 'NO_SHOW' });
    renderWithProviders(<OwnerVenuesPage />);
    await user.click(await screen.findByTestId('owner-noshow'));

    expect(await screen.findByTestId('owner-notice')).toHaveTextContent(/marked as a no-show/);
    expect(owner.noShow).toHaveBeenCalledWith('b-1');
  });

  it('does not offer no-show for a booking in the future', async () => {
    renderWithProviders(<OwnerVenuesPage />);
    await screen.findAllByTestId('owner-booking');

    expect(screen.queryByTestId('owner-noshow')).toBeNull();
  });

  it('adds a blackout for the whole venue and shows the server\'s refusal when a booking overlaps', async () => {
    const user = userEvent.setup();
    owner.addBlackout.mockRejectedValue(apiError(409, 'BLACKOUT_CONFLICT', '1 booking(s) overlap this period. Cancel them first, then add the blackout.'));
    renderWithProviders(<OwnerVenuesPage />);
    await user.click(await screen.findByTestId('add-blackout'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/from/i), '2099-11-21T09:00');
    await user.type(within(dialog).getByLabelText(/until/i), '2099-11-21T11:00');
    await user.type(within(dialog).getByLabelText(/reason/i), 'Maintenance');
    await user.click(within(dialog).getByTestId('save-blackout'));

    expect(await screen.findByTestId('blackout-error')).toHaveTextContent(/cancel them first/i);
    expect(owner.addBlackout).toHaveBeenCalledWith('v-1', { courtId: null, startAt: expect.stringMatching(/Z$/), endAt: expect.stringMatching(/Z$/), reason: 'Maintenance' });
  });

  it('can close just one court', async () => {
    const user = userEvent.setup();
    owner.addBlackout.mockResolvedValue({ id: 'bl-1', venueId: 'v-1', courtId: 'c-2', startAt: '', endAt: '', reason: 'Net repair', createdBy: 'o-1', createdAt: '' });
    renderWithProviders(<OwnerVenuesPage />);
    await user.click(await screen.findByTestId('add-blackout'));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: 'Hall' }));
    await user.type(within(dialog).getByLabelText(/from/i), '2099-11-21T09:00');
    await user.type(within(dialog).getByLabelText(/until/i), '2099-11-21T11:00');
    await user.type(within(dialog).getByLabelText(/reason/i), 'Net repair');
    await user.click(within(dialog).getByTestId('save-blackout'));

    await waitFor(() => expect(owner.addBlackout).toHaveBeenCalledWith('v-1', expect.objectContaining({ courtId: 'c-2', reason: 'Net repair' })));
  });

  it('creates a venue', async () => {
    const user = userEvent.setup();
    owner.create.mockResolvedValue(MANAGED);
    renderWithProviders(<OwnerVenuesPage />);
    await user.click(await screen.findByTestId('new-venue'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/^name/i), 'New Hub');
    await user.type(within(dialog).getByLabelText(/^address/i), '1 Road');
    await user.type(within(dialog).getByLabelText(/^city/i), 'Pune');
    await user.click(within(dialog).getByTestId('save-venue'));

    await waitFor(() => expect(owner.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'New Hub', city: 'Pune', timeZone: 'Asia/Kolkata' }), undefined));
  });

  it('sends the version I saw when editing a venue, so a stale edit is refused by the server', async () => {
    const user = userEvent.setup();
    owner.update.mockResolvedValue(MANAGED);
    renderWithProviders(<OwnerVenuesPage />);
    await user.click(await screen.findByTestId('edit-venue'));
    await user.click(within(await screen.findByRole('dialog')).getByTestId('save-venue'));

    await waitFor(() => expect(owner.update).toHaveBeenCalledWith('v-1', expect.objectContaining({ name: VENUE.name }), true, 3, undefined));
  });

  it('adds a court with the chosen bookable sports and will not save one without a sport', async () => {
    const user = userEvent.setup();
    owner.addCourt.mockResolvedValue(COURT_1);
    renderWithProviders(<OwnerVenuesPage />);
    await user.click(await screen.findByTestId('add-court'));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText(/court name/i), 'Court 3');
    expect(within(dialog).getByTestId('save-court')).toBeDisabled();
    await user.click(within(dialog).getByLabelText('Football'));
    await user.click(within(dialog).getByLabelText('Badminton'));
    await user.click(within(dialog).getByTestId('save-court'));

    await waitFor(() => expect(owner.addCourt).toHaveBeenCalledWith('v-1', expect.objectContaining({ name: 'Court 3', sportIds: ['s-football', 's-badminton'], slotMinutes: 60 })));
  });

  it('edits a court with the version I saw and shows the server\'s refusal', async () => {
    const user = userEvent.setup();
    owner.updateCourt.mockRejectedValue(apiError(409, 'OPTIMISTIC_LOCKING_CONFLICT', 'This court was changed since you opened it.'));
    renderWithProviders(<OwnerVenuesPage />);
    await user.click((await screen.findAllByTestId('edit-court'))[0]);
    await user.click(within(await screen.findByRole('dialog')).getByTestId('save-court'));

    expect(await screen.findByTestId('court-form-error')).toHaveTextContent(/changed since you opened it/i);
    expect(owner.updateCourt).toHaveBeenCalledWith('v-1', 'c-1', expect.objectContaining({ name: 'Court 1', sportIds: ['s-cricket', 's-football'] }), true, 2);
  });

  it('warns the owner when they no longer hold the role, and offers no reassignment (that is an administrator action)', async () => {
    owner.venues.mockResolvedValue([{ ...MANAGED, ownerEligible: false }]);
    renderWithProviders(<OwnerVenuesPage />);

    expect(await screen.findByTestId('owner-removed')).toHaveTextContent(/no longer hold the ground-owner role/i);
    expect(screen.queryByTestId('reassign-venue')).toBeNull();
  });
});
