import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import MatchesPage from './MatchesPage';
import { cricketApi } from '@/api/cricket';
import { useAuthStore } from '@/store/authStore';
import { MATCH } from './cricketFixtures';

vi.mock('@/api/cricket', () => ({ cricketApi: { matches: { mine: vi.fn() } } }));

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/matches" element={<MatchesPage />} />
    <Route path="/matches/new" element={<div>NEW MATCH</div>} />
    <Route path="/matches/:id/setup" element={<div>SETUP PAGE</div>} />
    <Route path="/login" element={<div>LOGIN PAGE</div>} />
  </Routes>, '/matches');

describe('MatchesPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u-1', email: 'a@b.c', roles: ['CUSTOMER'] } });
    vi.mocked(cricketApi.matches.mine).mockResolvedValue([
      { ...MATCH, venue: { bookingId: 'b-1', name: 'Green Park Hub', city: 'Pune' }, scheduledAt: '2026-11-20T10:00:00Z' },
      { ...MATCH, id: 'm-2', status: 'TOSS_DONE', canScore: false, ballType: 'LEATHER', venue: null, city: null },
    ]);
  });

  it('lists my matches with their status, venue and whether I score them', async () => {
    renderPage();

    const cards = await screen.findAllByTestId('match-card');
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('Lions vs Tigers');
    expect(cards[0]).toHaveTextContent('20 overs · Tennis ball');
    expect(cards[0]).toHaveTextContent('Green Park Hub');
    expect(cards[0]).toHaveTextContent('Set up');
    expect(cards[0]).toHaveTextContent('Scorer');
    expect(cards[1]).toHaveTextContent('Leather ball');
    expect(cards[1]).toHaveTextContent('Toss done');
    expect(cards[1]).not.toHaveTextContent('Scorer');
  });

  it('opens a match and starts a new one', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click((await screen.findAllByTestId('match-card'))[0].querySelector('button')!);
    expect(await screen.findByText('SETUP PAGE')).toBeInTheDocument();
  });

  it('goes to the new match screen', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId('new-match'));

    expect(await screen.findByText('NEW MATCH')).toBeInTheDocument();
  });

  it('asks a signed-out visitor to sign in', async () => {
    const user = userEvent.setup();
    useAuthStore.setState({ isAuthenticated: false, user: null });
    renderPage();
    await user.click(await screen.findByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('LOGIN PAGE')).toBeInTheDocument();
    expect(cricketApi.matches.mine).not.toHaveBeenCalled();
  });
});
