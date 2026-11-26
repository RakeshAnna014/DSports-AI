import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import TeamsPage from './TeamsPage';
import { cricketApi } from '@/api/cricket';
import { useAuthStore } from '@/store/authStore';
import { apiError } from '@/test/venueFixtures';
import { TEAM } from './cricketFixtures';

vi.mock('@/api/cricket', () => ({ cricketApi: { teams: { mine: vi.fn(), create: vi.fn() } } }));

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/teams" element={<TeamsPage />} />
    <Route path="/teams/:id" element={<div>TEAM PAGE</div>} />
    <Route path="/login" element={<div>LOGIN PAGE</div>} />
  </Routes>, '/teams');

describe('TeamsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    vi.mocked(cricketApi.teams.mine).mockResolvedValue([TEAM, { ...TEAM, id: 't-2', name: 'Sunday XI', city: null, canEdit: false, members: [] }]);
  });

  it('lists my teams with their size and marks the ones I am only a member of', async () => {
    renderPage();

    const cards = await screen.findAllByTestId('team-card');
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('Green Lions');
    expect(cards[0]).toHaveTextContent('Chennai · 2 players');
    expect(cards[1]).toHaveTextContent('Sunday XI');
    expect(cards[1]).toHaveTextContent('No city · 0 players');
    expect(cards[1]).toHaveTextContent('Member');
  });

  it('creates a team and opens it', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.teams.create).mockResolvedValue({ ...TEAM, id: 't-9' });
    renderPage();
    await screen.findAllByTestId('team-card');
    expect(screen.getByTestId('create-team')).toBeDisabled();
    await user.type(screen.getByLabelText('Team name'), '  New XI ');
    await user.type(screen.getByLabelText(/city/i), 'Pune');
    await user.click(screen.getByTestId('create-team'));

    expect(await screen.findByText('TEAM PAGE')).toBeInTheDocument();
    expect(cricketApi.teams.create).toHaveBeenCalledWith('New XI', 'Pune');
  });

  it('shows the server\'s refusal for a duplicate name', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketApi.teams.create).mockRejectedValue(apiError(409, 'DUPLICATE_TEAM_NAME', 'You already have a team with that name'));
    renderPage();
    await user.type(await screen.findByLabelText('Team name'), 'Green Lions');
    await user.click(screen.getByTestId('create-team'));

    expect(await screen.findByTestId('team-error')).toHaveTextContent(/already have a team with that name/i);
  });

  it('asks a signed-out visitor to sign in and loads nothing', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ isAuthenticated: false, user: null });
    renderPage();
    await user.click(await screen.findByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
    await waitFor(() => expect(cricketApi.teams.mine).not.toHaveBeenCalled());
  });
});
