import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, type AxiosResponse } from 'axios';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import { cricketPublicApi } from '@/api/cricketPublic';
import type { PublicCareerLine, PublicPlayerStats } from '@/types/cricketPublic';
import PlayerStatsPage from './PlayerStatsPage';
import PublicScorecardPage from './PublicScorecardPage';
import { FINISHED_CARD } from './publicFixtures';

vi.mock('@/api/cricketPublic', async (original) => ({ ...(await original<typeof import('@/api/cricketPublic')>()), cricketPublicApi: { scorecard: vi.fn(), live: vi.fn(), analytics: vi.fn(), playerStats: vi.fn() } }));
const playerStats = vi.mocked(cricketPublicApi.playerStats);

const LEATHER: PublicCareerLine = {
  ballType: 'LEATHER', matches: 3,
  batting: { innings: 3, runs: 150, balls: 120, notOuts: 1, highest: 87, highestNotOut: true, fours: 14, sixes: 5, fifties: 1, hundreds: 0, ducks: 1, average: 75, strikeRate: 125 },
  bowling: { innings: 2, overs: '5.3', runs: 41, wickets: 4, maidens: 0, best: '3/12', fiveWickets: 0, average: 10.25, economy: 7.45, strikeRate: 8.25 },
  fielding: { catches: 2, stumpings: 0, runOuts: 1 },
};
const TENNIS: PublicCareerLine = { ...LEATHER, ballType: 'TENNIS', matches: 1, batting: { ...LEATHER.batting, runs: 9, innings: 1, highest: 9, highestNotOut: false } };
const STATS: PublicPlayerStats = { name: 'Arun Kumar', lines: [LEATHER, TENNIS] };

const renderPage = (id = 'p-1') => renderWithProviders(
  <Routes><Route path="/p/:id" element={<PlayerStatsPage />} /><Route path="/m/:id" element={<PublicScorecardPage />} /><Route path="/live" element={<div>LIVE</div>} /></Routes>, `/p/${id}`);

const failure = (status: number) => new AxiosError('x', undefined, undefined, undefined, { status } as AxiosResponse);

beforeEach(() => {
  playerStats.mockReset();
});

describe('the player page', () => {
  it('shows each figure the server gave, with the highest score starred when it was not out', async () => {
    playerStats.mockResolvedValue({ name: 'Arun Kumar', lines: [LEATHER] });
    renderPage();
    expect(await screen.findByTestId('player-name')).toHaveTextContent('Arun Kumar');
    expect(playerStats).toHaveBeenCalledWith('p-1');
    expect(screen.getByTestId('bat-runs')).toHaveTextContent('150');
    expect(screen.getByTestId('bat-highest')).toHaveTextContent('87*');
    expect(screen.getByTestId('bat-average')).toHaveTextContent('75.00');
    expect(screen.getByTestId('bat-sr')).toHaveTextContent('125.00');
    expect(screen.getByTestId('bowl-overs')).toHaveTextContent('5.3');
    expect(screen.getByTestId('bowl-best')).toHaveTextContent('3/12');
    expect(screen.getByTestId('bowl-economy')).toHaveTextContent('7.45');
    expect(screen.getByTestId('field-catches')).toHaveTextContent('2');
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    expect(screen.getByText('Leather ball')).toBeInTheDocument();
  });

  it('keeps the two ball types on their own tabs and never adds them', async () => {
    const user = userEvent.setup();
    playerStats.mockResolvedValue(STATS);
    renderPage();
    await screen.findByTestId('player-name');
    expect(within(screen.getByTestId('career-leather')).getByTestId('bat-runs')).toHaveTextContent('150');
    await user.click(screen.getByTestId('tab-tennis'));
    expect(within(screen.getByTestId('career-tennis')).getByTestId('bat-runs')).toHaveTextContent('9');
    expect(screen.queryByTestId('career-leather')).not.toBeInTheDocument();
  });

  it('shows a dash, never a zero, for a figure the server could not work out, and for a player who never batted', async () => {
    playerStats.mockResolvedValue({
      name: 'Bala',
      lines: [{ ...LEATHER, batting: { ...LEATHER.batting, innings: 0, runs: 0, balls: 0, notOuts: 0, highest: 0, highestNotOut: false, average: null, strikeRate: null }, bowling: { ...LEATHER.bowling, best: null, average: null, economy: null, strikeRate: null } }],
    });
    renderPage();
    await screen.findByTestId('player-name');
    for (const id of ['bat-average', 'bat-sr', 'bat-highest', 'bowl-best', 'bowl-average', 'bowl-economy', 'bowl-sr']) {
      expect(screen.getByTestId(id)).toHaveTextContent('–');
    }
  });

  it('wraps a very long non-Latin name instead of breaking the layout', async () => {
    const name = 'அருண்குமார்'.repeat(12);
    playerStats.mockResolvedValue({ name, lines: [LEATHER] });
    renderPage();
    expect(await screen.findByTestId('player-name')).toHaveTextContent(name);
    expect(screen.getByTestId('player-name')).toHaveStyle({ overflowWrap: 'anywhere' });
  });

  it('says there is nothing yet for a 404, and offers a retry when it cannot load', async () => {
    const user = userEvent.setup();
    playerStats.mockRejectedValueOnce(failure(404));
    const first = renderPage();
    expect(await screen.findByTestId('player-none')).toHaveTextContent('no finished matches');
    first.unmount();
    playerStats.mockRejectedValueOnce(failure(500)).mockResolvedValueOnce(STATS);
    renderPage();
    expect(await screen.findByTestId('player-failed')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('player-name')).toBeInTheDocument();
  });

  it('shows a guest or a hidden player as a name with no link', async () => {
    const card = { ...FINISHED_CARD, innings: FINISHED_CARD.innings.map((i) => ({ ...i, batters: i.batters.map((b) => ({ ...b, playerId: null })), bowlers: i.bowlers.map((w) => ({ ...w, playerId: null })) })) };
    vi.mocked(cricketPublicApi.scorecard).mockResolvedValue({ kind: 'changed', data: card, etag: '"1"' });
    vi.mocked(cricketPublicApi.analytics).mockResolvedValue({ seq: 1, innings: [], commentary: [], shots: [] });
    renderWithProviders(<Routes><Route path="/m/:id" element={<PublicScorecardPage />} /></Routes>, '/m/m-1');
    expect((await screen.findAllByTestId('player-name-plain')).length).toBeGreaterThan(0);
    expect(screen.queryByTestId('player-link')).toBeNull();
  });

  it('is reached from a name on the public scorecard', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketPublicApi.scorecard).mockResolvedValue({ kind: 'changed', data: FINISHED_CARD, etag: '"1"' });
    vi.mocked(cricketPublicApi.analytics).mockResolvedValue({ seq: 1, innings: [], commentary: [], shots: [] });
    playerStats.mockResolvedValue(STATS);
    renderWithProviders(<Routes><Route path="/m/:id" element={<PublicScorecardPage />} /><Route path="/p/:id" element={<PlayerStatsPage />} /></Routes>, '/m/m-1');
    const link = (await screen.findAllByTestId('player-link'))[0];
    expect(link.getAttribute('href')).toMatch(/^\/p\/p-/);
    await user.click(link);
    expect(await screen.findByTestId('player-name')).toHaveTextContent('Arun Kumar');
  });
});
