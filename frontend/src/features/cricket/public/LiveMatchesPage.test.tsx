import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import { cricketPublicApi } from '@/api/cricketPublic';
import { useAuthStore } from '@/store/authStore';
import type { PublicMatchSummary } from '@/types/cricketPublic';
import LiveMatchesPage from './LiveMatchesPage';
import { SUMMARY } from './publicFixtures';

vi.mock('@/api/cricketPublic', async (original) => ({ ...(await original<typeof import('@/api/cricketPublic')>()), cricketPublicApi: { scorecard: vi.fn(), live: vi.fn() } }));
const live = vi.mocked(cricketPublicApi.live);

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/live" element={<LiveMatchesPage />} />
    <Route path="/m/:id" element={<div>SCORECARD</div>} />
  </Routes>, '/live');

const page = (items: PublicMatchSummary[], pageNumber = 0, hasNext = false) => ({ items, page: pageNumber, size: 20, hasNext });

beforeEach(() => {
  live.mockReset();
  useAuthStore.setState({ isAuthenticated: false, user: null });
});

describe('the live matches page (no sign-in)', () => {
  it('lists the matches being played with each innings score, and opens the scorecard', async () => {
    const user = userEvent.setup();
    live.mockResolvedValue(page([SUMMARY, { ...SUMMARY, matchId: 'm-2', teamA: 'Kings', teamB: 'Royals', city: null, venue: null, status: 'INNINGS_BREAK', scores: [{ team: 'Kings', runs: 90, wickets: 4, overs: '10.0', current: true }] }]));
    renderPage();

    const cards = await screen.findAllByTestId('live-match');
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('Lions vs Tigers');
    expect(cards[0]).toHaveTextContent('Live');
    expect(cards[0]).toHaveTextContent('Lions 22/2 (2.0 ov)');
    expect(cards[0]).toHaveTextContent('Tigers 10/0 (0.2 ov)');
    expect(cards[0]).toHaveTextContent('2 overs · Green Park · Chennai');
    expect(cards[1]).toHaveTextContent('Innings break');
    expect(live).toHaveBeenCalledWith({ city: '', page: 0, size: 20 });

    await user.click(cards[0].querySelector('a')!);
    expect(await screen.findByText('SCORECARD')).toBeInTheDocument();
  });

  it('filters by city once the typing pauses', async () => {
    const user = userEvent.setup();
    live.mockResolvedValue(page([SUMMARY]));
    renderPage();
    await screen.findByTestId('live-match');

    live.mockResolvedValue(page([]));
    await user.type(screen.getByLabelText('City'), ' Pune ');

    expect(await screen.findByText('No live matches in Pune')).toBeInTheDocument();
    await waitFor(() => expect(live).toHaveBeenLastCalledWith({ city: 'Pune', page: 0, size: 20 }));
    expect(live).not.toHaveBeenCalledWith(expect.objectContaining({ city: 'P' }));
  });

  it('says so when nothing is being played', async () => {
    live.mockResolvedValue(page([]));
    renderPage();

    expect(await screen.findByText('No live matches right now')).toBeInTheDocument();
  });

  it('loads the next page on request and stops offering more at the end', async () => {
    const user = userEvent.setup();
    live.mockImplementation(async ({ page: n }) => (n === 0 ? page([SUMMARY], 0, true) : page([{ ...SUMMARY, matchId: 'm-9', teamA: 'Kings', teamB: 'Royals' }], 1, false)));
    renderPage();
    await screen.findByTestId('live-match');

    await user.click(screen.getByTestId('load-more'));
    expect(await screen.findByText('Kings vs Royals')).toBeInTheDocument();
    expect(screen.getAllByTestId('live-match')).toHaveLength(2);
    expect(screen.queryByTestId('load-more')).toBeNull();
    expect(live).toHaveBeenCalledWith({ city: '', page: 1, size: 20 });
  });

  it('shows an error with a retry when the list cannot be loaded', async () => {
    const user = userEvent.setup();
    live.mockRejectedValueOnce(new Error('down')).mockResolvedValue(page([SUMMARY]));
    renderPage();

    expect(await screen.findByText("We couldn't load the live matches.")).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /try again|retry/i }));
    expect(await screen.findByTestId('live-match')).toBeInTheDocument();
  });
});
