import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import TeamDetailPage from './TeamDetailPage';
import { cricketApi } from '@/api/cricket';
import { apiError } from '@/test/venueFixtures';
import { PLAYER, PLAYER_2, TEAM } from './cricketFixtures';

vi.mock('@/api/cricket', () => ({
  cricketApi: {
    teams: { get: vi.fn(), addMember: vi.fn(), removeMember: vi.fn(), setCaptain: vi.fn(), update: vi.fn(), archive: vi.fn() },
    players: { mine: vi.fn(), createGuest: vi.fn() },
  },
}));

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/teams/:id" element={<TeamDetailPage />} />
    <Route path="/teams" element={<div>TEAMS LIST</div>} />
  </Routes>, '/teams/t-1');

describe('TeamDetailPage', () => {
  beforeEach(() => {
    vi.mocked(cricketApi.teams.get).mockResolvedValue(TEAM);
    vi.mocked(cricketApi.players.mine).mockResolvedValue([PLAYER, PLAYER_2]);
  });

  it('shows the members with the captain first and the details the server allows', async () => {
    renderPage();

    const rows = await screen.findAllByTestId('team-member');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Arjun Kumar');
    expect(within(rows[0]).getByTestId('captain-chip')).toBeInTheDocument();
    expect(rows[0]).toHaveTextContent('+919876543210');
    expect(rows[1]).toHaveTextContent('Chetan');
    expect(rows[1]).toHaveTextContent('Has an account');
    expect(rows[1]).not.toHaveTextContent('+91');
  });

  it('offers only players who are not in the team yet and adds the one picked', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.teams.addMember).mockResolvedValue(TEAM);
    renderPage();
    await screen.findAllByTestId('team-member');
    await user.click(screen.getByRole('combobox', { name: /from my players/i }));
    const options = await screen.findAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual(['Bala Raj']);
    await user.click(options[0]);
    await user.click(screen.getByTestId('add-existing'));

    await waitFor(() => expect(cricketApi.teams.addMember).toHaveBeenCalledWith('t-1', 'p-2'));
  });

  it('creates a guest and puts them in the team in one go', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.players.createGuest).mockResolvedValue({ ...PLAYER, id: 'p-new', name: 'Dev' });
    vi.mocked(cricketApi.teams.addMember).mockResolvedValue(TEAM);
    renderPage();
    await screen.findAllByTestId('team-member');
    await user.type(screen.getByLabelText('Name'), 'Dev');
    await user.type(screen.getByLabelText(/phone/i), '98765 43210');
    await user.click(screen.getByTestId('add-guest'));

    await waitFor(() => expect(cricketApi.players.createGuest).toHaveBeenCalledWith({ name: 'Dev', phone: '98765 43210', role: undefined }));
    await waitFor(() => expect(cricketApi.teams.addMember).toHaveBeenCalledWith('t-1', 'p-new'));
  });

  it('makes a captain, removes a player, and does not offer to remove the captain', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.teams.setCaptain).mockResolvedValue(TEAM);
    vi.mocked(cricketApi.teams.removeMember).mockResolvedValue(TEAM);
    renderPage();
    const rows = await screen.findAllByTestId('team-member');
    expect(within(rows[0]).queryByTestId('remove-member')).toBeNull();
    await user.click(within(rows[1]).getByTestId('make-captain'));
    await waitFor(() => expect(cricketApi.teams.setCaptain).toHaveBeenCalledWith('t-1', 'p-3'));
    await user.click(within(rows[1]).getByTestId('remove-member'));
    await waitFor(() => expect(cricketApi.teams.removeMember).toHaveBeenCalledWith('t-1', 'p-3'));
  });

  it('renames with the version it saw and shows the server\'s refusal', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.teams.update).mockRejectedValue(apiError(409, 'OPTIMISTIC_LOCKING_CONFLICT', 'This team was changed since you opened it.'));
    renderPage();
    await user.click(await screen.findByTestId('rename-team'));
    const dialog = await screen.findByRole('dialog');
    await user.clear(within(dialog).getByLabelText('Team name'));
    await user.type(within(dialog).getByLabelText('Team name'), 'Lions XI');
    await user.click(within(dialog).getByTestId('save-team'));

    await waitFor(() => expect(cricketApi.teams.update).toHaveBeenCalledWith('t-1', 'Lions XI', 'Chennai', 3));
    expect(await screen.findByTestId('team-message')).toHaveTextContent(/changed since you opened it/i);
  });

  it('archives only after a confirmation', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.teams.archive).mockResolvedValue({ ...TEAM, status: 'ARCHIVED' });
    renderPage();
    await user.click(await screen.findByTestId('archive-team'));
    expect(cricketApi.teams.archive).not.toHaveBeenCalled();
    await user.click(await screen.findByTestId('confirm-archive'));

    await waitFor(() => expect(cricketApi.teams.archive).toHaveBeenCalledWith('t-1'));
  });

  it('is read-only for a member without rights and for an archived team', async () => {
    vi.mocked(cricketApi.teams.get).mockResolvedValue({ ...TEAM, canEdit: false });
    const first = renderPage();
    expect(await screen.findByText(/only its creator or captain can change it/i)).toBeInTheDocument();
    expect(screen.queryByTestId('rename-team')).toBeNull();
    expect(screen.queryByTestId('add-guest')).toBeNull();
    expect(screen.queryByTestId('make-captain')).toBeNull();
    expect(cricketApi.players.mine).not.toHaveBeenCalled();
    first.unmount();

    vi.mocked(cricketApi.teams.get).mockResolvedValue({ ...TEAM, status: 'ARCHIVED' });
    renderPage();
    await screen.findAllByTestId('team-member');
    expect(screen.queryByTestId('archive-team')).toBeNull();
    expect(screen.queryByTestId('add-guest')).toBeNull();
  });

  it('says so when the team cannot be found (a stranger gets 404)', async () => {
    vi.mocked(cricketApi.teams.get).mockRejectedValue(apiError(404, 'TEAM_NOT_FOUND', 'Team not found'));
    renderPage();

    expect(await screen.findByText(/couldn't find that team/i)).toBeInTheDocument();
  });
});
