import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, type AxiosResponse } from 'axios';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import { cricketPublicApi } from '@/api/cricketPublic';
import { useAuthStore } from '@/store/authStore';
import PublicScorecardPage from './PublicScorecardPage';
import { CHASE, FINISHED_CARD, FIRST_INNINGS, LIVE_CARD } from './publicFixtures';

vi.mock('@/api/cricketPublic', async (original) => ({ ...(await original<typeof import('@/api/cricketPublic')>()), cricketPublicApi: { scorecard: vi.fn(), live: vi.fn(), analytics: vi.fn() } }));
const scorecard = vi.mocked(cricketPublicApi.scorecard);

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/m/:id" element={<PublicScorecardPage />} />
    <Route path="/live" element={<div>LIVE LIST</div>} />
  </Routes>, '/m/m-1');

const answer = (data = LIVE_CARD) => ({ kind: 'changed' as const, data, etag: `"${data.seq}"` });

beforeEach(() => {
  scorecard.mockReset();
  vi.mocked(cricketPublicApi.analytics).mockReset();
  vi.mocked(cricketPublicApi.analytics).mockResolvedValue({ seq: 0, innings: [], commentary: [], shots: [] });
  useAuthStore.setState({ isAuthenticated: false, user: null });
  localStorage.clear();
});

describe('the public scorecard page (no sign-in)', () => {
  it('shows a live chase: the score, overs, run rate, target and the rate required', async () => {
    scorecard.mockResolvedValue(answer());
    renderPage();

    expect(await screen.findByTestId('public-score')).toHaveTextContent('Tigers 10/0');
    expect(screen.getByTestId('public-overs')).toHaveTextContent('0.2 / 2 ov');
    expect(screen.getByTestId('public-run-rate')).toHaveTextContent('Run rate 30.00');
    expect(screen.getByTestId('public-chase')).toHaveTextContent('Target 23 · need 13 off 10 balls · required rate 7.80');
    expect(screen.getByTestId('public-status')).toHaveTextContent('Live');
    expect(screen.getByRole('heading', { name: 'Lions vs Tigers' })).toBeInTheDocument();
    expect(screen.getByText(/Green Park, Chennai/)).toBeInTheDocument();
    expect(screen.getByTestId('public-earlier')).toHaveTextContent('Lions 22/2 (2.0 ov)');
    expect(scorecard).toHaveBeenCalledWith('m-1', null);
  });

  it('shows the batters with how they were out, the striker marked, the bowlers, extras, fall of wickets and waivers', async () => {
    scorecard.mockResolvedValue(answer());
    renderPage();
    await screen.findByTestId('public-score');

    const [first, chase] = screen.getAllByTestId('public-innings');
    const firstBatters = within(first).getAllByTestId('public-batter');
    expect(firstBatters[0]).toHaveTextContent('Ravi');
    expect(firstBatters[0]).toHaveTextContent('c Kiran b Tarun');
    expect(firstBatters[1]).toHaveTextContent('b Tarun');
    expect(firstBatters[2]).toHaveTextContent('not out');
    expect(within(first).getByTestId('public-extras')).toHaveTextContent('Extras 3 (b 1, lb 0, wd 1, nb 1)');
    expect(within(first).getByTestId('public-yet-to-bat')).toHaveTextContent('Yet to bat: Anil, Bala');
    expect(within(first).getByTestId('public-bowler')).toHaveTextContent('Tarun');
    expect(within(first).getByTestId('public-bowler')).toHaveTextContent('12.00');
    expect(within(first).getByTestId('public-fall')).toHaveTextContent('Fall of wickets: 1-12 (Ravi, 0.5 ov), 2-15 (Sam, 1.2 ov)');
    expect(within(first).getByTestId('public-waivers')).toHaveTextContent('Kiran bowled two overs in a row');

    const crease = within(chase).getAllByTestId('public-batter');
    expect(crease[1]).toHaveTextContent('Tarun *');
    expect(crease[0]).not.toHaveTextContent('*');
    expect(FIRST_INNINGS.batters[0].howOut).toBe('c Kiran b Tarun');
    expect(CHASE.batters).toHaveLength(2);
  });

  it('shows the result of a finished match and says the scorecard is final', async () => {
    scorecard.mockResolvedValue(answer(FINISHED_CARD));
    renderPage();

    expect(await screen.findByTestId('public-result')).toHaveTextContent('Tigers won by 10 wickets (0 balls left)');
    expect(screen.getByTestId('public-status')).toHaveTextContent('Result');
    expect(screen.queryByTestId('public-chase')).toHaveTextContent('Target was 23.');
    expect(screen.getByTestId('public-updated')).toHaveTextContent('Final scorecard.');
  });

  it('says the innings break', async () => {
    scorecard.mockResolvedValue(answer({ ...LIVE_CARD, status: 'INNINGS_BREAK', innings: [FIRST_INNINGS] }));
    renderPage();

    expect(await screen.findByTestId('public-break')).toBeInTheDocument();
    expect(screen.getByTestId('public-score')).toHaveTextContent('Lions 22/2');
    expect(screen.queryByTestId('public-chase')).toBeNull();
  });

  it('works for a signed-out visitor and offers no sign-in', async () => {
    scorecard.mockResolvedValue(answer());
    renderPage();
    await screen.findByTestId('public-score');

    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(screen.queryByText(/sign in/i)).toBeNull();
  });

  it('says a match that has not started has not started, offers a check, and never shows an error', async () => {
    const user = userEvent.setup();
    scorecard.mockRejectedValueOnce(new AxiosError('nf', 'ERR', undefined, undefined, { status: 404, data: {} } as AxiosResponse)).mockResolvedValue(answer());
    renderPage();

    expect(await screen.findByTestId('public-not-found')).toHaveTextContent('has not started yet');
    await user.click(screen.getByRole('button', { name: 'Check again' }));
    expect(await screen.findByTestId('public-score')).toBeInTheDocument();
    expect(screen.queryByTestId('public-not-found')).toBeNull();
  });

  it('keeps the last score on screen when a refresh fails, says so, and updates by itself once the server answers again', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
    try {
      scorecard.mockResolvedValueOnce(answer()).mockRejectedValueOnce(new AxiosError('offline')).mockResolvedValue(answer({ ...LIVE_CARD, seq: 26, innings: [FIRST_INNINGS, { ...CHASE, runs: 14 }] }));
      renderPage();
      await act(async () => { await vi.advanceTimersByTimeAsync(0); });
      expect(screen.getByTestId('public-score')).toHaveTextContent('Tigers 10/0');
      expect(screen.queryByTestId('public-stale')).toBeNull();

      await act(async () => { await vi.advanceTimersByTimeAsync(4000); });
      expect(screen.getByTestId('public-stale')).toHaveTextContent("Can't refresh right now");
      expect(screen.getByTestId('public-score')).toHaveTextContent('Tigers 10/0');

      await act(async () => { await vi.advanceTimersByTimeAsync(8000); });
      expect(screen.queryByTestId('public-stale')).toBeNull();
      expect(screen.getByTestId('public-score')).toHaveTextContent('Tigers 14/0');
    } finally {
      vi.useRealTimers();
    }
  });

  it('has the share buttons and a way back to the live list', async () => {
    const user = userEvent.setup();
    scorecard.mockResolvedValue(answer());
    renderPage();
    await screen.findByTestId('public-score');

    expect(screen.getByTestId('copy-link')).toBeInTheDocument();
    expect(screen.getByTestId('whatsapp-share')).toHaveAttribute('href', expect.stringContaining('https://wa.me/?text='));
    await user.click(screen.getByRole('link', { name: 'Live matches' }));
    expect(await screen.findByText('LIVE LIST')).toBeInTheDocument();
  });
});
