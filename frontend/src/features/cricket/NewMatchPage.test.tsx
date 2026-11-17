import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import NewMatchPage from './NewMatchPage';
import { cricketApi } from '@/api/cricket';
import { venuesApi } from '@/api/venues';
import { useAuthStore } from '@/store/authStore';
import { apiError, BOOKING } from '@/test/venueFixtures';
import { MATCH, TEAM } from './cricketFixtures';

vi.mock('@/api/cricket', () => ({ cricketApi: { teams: { mine: vi.fn() }, matches: { create: vi.fn() } } }));
vi.mock('@/api/venues', async (orig) => {
  const real = await orig<typeof import('@/api/venues')>();
  return { ...real, venuesApi: { myBookings: vi.fn() } };
});

const TEAMS = [{ ...TEAM, id: 'ta', name: 'Lions' }, { ...TEAM, id: 'tb', name: 'Tigers' }, { ...TEAM, id: 'tc', name: 'Old XI', status: 'ARCHIVED' as const }];
const inDays = (d: number) => new Date(Date.now() + d * 86_400_000).toISOString();

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/matches/new" element={<NewMatchPage />} />
    <Route path="/matches/:id/setup" element={<div>SETUP PAGE</div>} />
    <Route path="/teams" element={<div>TEAMS PAGE</div>} />
  </Routes>, '/matches/new');

describe('NewMatchPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    vi.mocked(cricketApi.teams.mine).mockResolvedValue(TEAMS);
    vi.mocked(venuesApi.myBookings).mockResolvedValue([
      { ...BOOKING, id: 'b-ok', startAt: inDays(2), endAt: inDays(2), status: 'CONFIRMED', sportName: 'Cricket' },
      { ...BOOKING, id: 'b-held', status: 'HELD', sportName: 'Cricket', endAt: inDays(3) },
      { ...BOOKING, id: 'b-foot', status: 'CONFIRMED', sportName: 'Football', endAt: inDays(3) },
      { ...BOOKING, id: 'b-past', status: 'CONFIRMED', sportName: 'Cricket', endAt: '2020-01-01T00:00:00Z' },
    ]);
  });

  async function pickTeams(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('combobox', { name: 'Team A' }));
    await user.click(await screen.findByRole('option', { name: 'Lions' }));
    await user.click(screen.getByRole('combobox', { name: 'Team B' }));
    await user.click(await screen.findByRole('option', { name: 'Tigers' }));
  }

  it('offers only active teams and does not allow the same team on both sides', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole('combobox', { name: 'Team A' }));
    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['Lions', 'Tigers']);
    await user.click(screen.getByRole('option', { name: 'Lions' }));
    await user.click(screen.getByRole('combobox', { name: 'Team B' }));

    expect(await screen.findByRole('option', { name: 'Lions' })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByTestId('create-match')).toBeDisabled();
  });

  it('creates a match with the standard rules and opens its setup', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.matches.create).mockResolvedValue(MATCH);
    renderPage();
    await pickTeams(user);
    await user.clear(screen.getByLabelText('Overs per side'));
    await user.type(screen.getByLabelText('Overs per side'), '12');
    await user.click(screen.getByTestId('create-match'));

    expect(await screen.findByText('SETUP PAGE')).toBeInTheDocument();
    expect(cricketApi.matches.create).toHaveBeenCalledWith(expect.objectContaining({
      teamAId: 'ta', teamBId: 'tb', overs: 12, ballType: 'TENNIS',
      rules: expect.objectContaining({ ballsPerOver: 6, playersPerSide: 11, widePenalty: 1, noBallPenalty: 1, freeHitEnabled: true, lastManStands: false }),
    }));
  });

  it('sends the rule changes the organiser made', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.matches.create).mockResolvedValue(MATCH);
    renderPage();
    await pickTeams(user);
    await user.clear(screen.getByLabelText('Players per side'));
    await user.type(screen.getByLabelText('Players per side'), '8');
    await user.clear(screen.getByLabelText(/no-ball penalty/i));
    await user.type(screen.getByLabelText(/no-ball penalty/i), '2');
    await user.type(screen.getByLabelText('Max overs per bowler'), '3');
    await user.click(screen.getByLabelText('Last man stands'));
    await user.click(screen.getByLabelText('Free hit after a no-ball'));
    await user.click(screen.getByTestId('create-match'));

    await waitFor(() => expect(cricketApi.matches.create).toHaveBeenCalled());
    expect(vi.mocked(cricketApi.matches.create).mock.calls[0][0].rules).toMatchObject({ playersPerSide: 8, noBallPenalty: 2, maxOversPerBowler: 3, lastManStands: true, freeHitEnabled: false });
  });

  it('lists only my paid, upcoming cricket bookings and sends the chosen one', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.matches.create).mockResolvedValue(MATCH);
    renderPage();
    await pickTeams(user);
    await user.click(screen.getByRole('combobox', { name: /venue booking/i }));
    const options = await screen.findAllByRole('option');
    expect(options).toHaveLength(2);
    expect(options[1]).toHaveTextContent('Green Park Sports Hub, Court 1');
    await user.click(options[1]);
    await user.click(screen.getByTestId('create-match'));

    await waitFor(() => expect(cricketApi.matches.create).toHaveBeenCalledWith(expect.objectContaining({ bookingId: 'b-ok' })));
  });

  it('explains a booking that already hosts another match', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.matches.create).mockRejectedValue(apiError(409, 'BOOKING_ALREADY_USED', 'x'));
    renderPage();
    await pickTeams(user);
    await user.click(screen.getByTestId('create-match'));

    expect(await screen.findByTestId('match-error')).toHaveTextContent(/already hosts another match/i);
  });

  it('tells someone with fewer than two teams to create teams first', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.teams.mine).mockResolvedValue([TEAMS[0]]);
    renderPage();

    expect(await screen.findByTestId('need-teams')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /create teams/i }));
    expect(await screen.findByText('TEAMS PAGE')).toBeInTheDocument();
  });
});
