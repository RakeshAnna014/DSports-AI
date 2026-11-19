import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import MatchSetupPage from './MatchSetupPage';
import { cricketApi } from '@/api/cricket';
import { apiError } from '@/test/venueFixtures';
import { MATCH } from './cricketFixtures';

vi.mock('@/api/cricket', () => ({ cricketApi: { matches: { get: vi.fn(), update: vi.fn(), toss: vi.fn(), setPlayingXi: vi.fn(), addScorer: vi.fn(), removeScorer: vi.fn() } } }));
const api = vi.mocked(cricketApi.matches);

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/matches/:id/setup" element={<MatchSetupPage />} />
    <Route path="/matches" element={<div>MATCHES LIST</div>} />
    <Route path="/matches/:id/score" element={<div>SCORER PAGE</div>} />
  </Routes>, '/matches/m-1/setup');

describe('MatchSetupPage', () => {
  beforeEach(() => {
    api.get.mockResolvedValue(MATCH);
  });

  it('shows the settings and that the match is still being set up', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Lions vs Tigers' })).toBeInTheDocument();
    expect(screen.getByText('20 overs')).toBeInTheDocument();
    expect(screen.getByText('Tennis ball')).toBeInTheDocument();
    expect(screen.getByText('3 a side')).toBeInTheDocument();
    expect(screen.getByTestId('match-state')).toHaveTextContent('Being set up');
  });

  it('records the toss and shows who bats first', async () => {
    const user = userEvent.setup();
    api.toss.mockResolvedValue({ ...MATCH, status: 'TOSS_DONE', toss: { winnerTeamId: 'tb', decision: 'BOWL' }, battingFirstTeamId: 'ta' });
    renderPage();
    await screen.findByTestId('record-toss');
    expect(screen.getByTestId('record-toss')).toBeDisabled();
    await user.click(screen.getByRole('combobox', { name: /won the toss/i }));
    await user.click(await screen.findByRole('option', { name: 'Tigers' }));
    await user.click(screen.getByRole('combobox', { name: /chose to/i }));
    await user.click(await screen.findByRole('option', { name: 'Bowl' }));
    api.get.mockResolvedValue({ ...MATCH, status: 'TOSS_DONE', toss: { winnerTeamId: 'tb', decision: 'BOWL' }, battingFirstTeamId: 'ta' });
    await user.click(screen.getByTestId('record-toss'));

    await waitFor(() => expect(api.toss).toHaveBeenCalledWith('m-1', 'tb', 'BOWL'));
    expect(await screen.findByTestId('toss-result')).toHaveTextContent('Tigers won the toss and chose to bowl. Lions bat first.');
  });

  it('picks a playing XI in batting order, never beyond the players per side, and saves it', async () => {
    const user = userEvent.setup();
    api.setPlayingXi.mockResolvedValue(MATCH);
    renderPage();
    const picker = (await screen.findAllByTestId('xi-picker'))[0];
    const players = within(picker).getAllByTestId('xi-player');
    await user.click(players[2]);
    await user.click(players[0]);
    await user.click(players[1]);
    await user.click(players[3]);

    expect(within(picker).getByTestId('xi-count')).toHaveTextContent('3 of 3');
    expect(players[2]).toHaveTextContent('1. A player 3');
    expect(players[0]).toHaveTextContent('2. A player 1');
    expect(players[3]).not.toHaveTextContent('.');
    await user.click(within(picker).getByTestId('save-xi'));

    await waitFor(() => expect(api.setPlayingXi).toHaveBeenCalledWith('m-1', 'ta', ['a-3', 'a-1', 'a-2']));
  });

  it('takes a player out of the XI by clicking again and needs at least two players to save', async () => {
    const user = userEvent.setup();
    renderPage();
    const picker = (await screen.findAllByTestId('xi-picker'))[1];
    const players = within(picker).getAllByTestId('xi-player');
    await user.click(players[0]);
    expect(within(picker).getByTestId('save-xi')).toBeDisabled();
    await user.click(players[1]);
    expect(within(picker).getByTestId('save-xi')).toBeEnabled();
    await user.click(players[0]);
    expect(within(picker).getByTestId('xi-count')).toHaveTextContent('1 of 3');
    expect(within(picker).getByTestId('save-xi')).toBeDisabled();
  });

  it('shows the server\'s refusal of an invalid XI', async () => {
    const user = userEvent.setup();
    api.setPlayingXi.mockRejectedValue(apiError(409, 'INVALID_PLAYING_XI', 'A player cannot play for both sides'));
    renderPage();
    const picker = (await screen.findAllByTestId('xi-picker'))[0];
    const players = within(picker).getAllByTestId('xi-player');
    await user.click(players[0]);
    await user.click(players[1]);
    await user.click(within(picker).getByTestId('save-xi'));

    expect(await screen.findByTestId('setup-message')).toHaveTextContent(/cannot play for both sides/i);
  });

  it('says the match is ready once the toss and both XIs are in', async () => {
    api.get.mockResolvedValue({ ...MATCH, status: 'TOSS_DONE', readyToStart: true });
    renderPage();

    expect(await screen.findByTestId('match-state')).toHaveTextContent('Ready to start');
  });

  it('adds a scorer by email, says to register first for an unknown one, and removes one', async () => {
    const user = userEvent.setup();
    api.addScorer.mockRejectedValueOnce(apiError(404, 'USER_NOT_REGISTERED', 'x')).mockResolvedValue(MATCH);
    api.get.mockResolvedValue({ ...MATCH, scorers: [{ userId: 'u-9', name: 'Sam Scorer', email: 'sam@example.com' }] });
    api.removeScorer.mockResolvedValue(MATCH);
    renderPage();
    await user.type(await screen.findByLabelText(/scorer's email/i), 'nobody@example.com');
    await user.click(screen.getByTestId('add-scorer'));
    expect(await screen.findByTestId('setup-message')).toHaveTextContent(/ask them to register first/i);

    await user.click(screen.getByTestId('add-scorer'));
    await waitFor(() => expect(api.addScorer).toHaveBeenCalledTimes(2));
    expect(api.addScorer).toHaveBeenLastCalledWith('m-1', 'nobody@example.com');
    await user.click(await screen.findByTestId('remove-scorer'));
    await waitFor(() => expect(api.removeScorer).toHaveBeenCalledWith('m-1', 'u-9'));
  });

  it('lets a scorer who is not the creator work on the toss and XIs but not manage scorers', async () => {
    api.get.mockResolvedValue({ ...MATCH, canManage: false });
    renderPage();

    await screen.findByTestId('record-toss');
    expect(screen.getAllByTestId('xi-picker')).toHaveLength(2);
    expect(screen.queryByTestId('add-scorer')).toBeNull();
  });

  it('is read-only for someone who only has a team in the match', async () => {
    api.get.mockResolvedValue({ ...MATCH, canManage: false, canScore: false, teamA: { ...MATCH.teamA, squad: [], playingXi: [{ playerId: 'a-1', name: 'Arjun', battingOrder: 1 }] } });
    renderPage();

    expect(await screen.findByText(/only its creator or scorer can change the setup/i)).toBeInTheDocument();
    expect(screen.queryByTestId('record-toss')).toBeNull();
    expect(screen.queryByTestId('xi-picker')).toBeNull();
    expect(screen.getAllByTestId('xi-view')[0]).toHaveTextContent('1. Arjun');
    expect(screen.getAllByTestId('xi-view')[1]).toHaveTextContent('Not chosen yet');
  });

  it('is read-only once the match has started and says so', async () => {
    api.get.mockResolvedValue({ ...MATCH, status: 'IN_PROGRESS' });
    renderPage();

    expect(await screen.findByText(/has started, so its setup can no longer change/i)).toBeInTheDocument();
    expect(screen.queryByTestId('record-toss')).toBeNull();
    expect(screen.queryByTestId('save-xi')).toBeNull();
    expect(screen.getByTestId('share-scorecard')).toBeInTheDocument();
  });

  it('offers the public scorecard link only once the match has started', async () => {
    api.get.mockResolvedValue({ ...MATCH, status: 'TOSS_DONE' });
    renderPage();
    await screen.findByTestId('match-state');

    expect(screen.queryByTestId('share-scorecard')).toBeNull();
  });

  it('edits the overs, ball and rules before the match starts and sends the date, city and umpires back unchanged', async () => {
    const user = userEvent.setup();
    const base = { ...MATCH, scheduledAt: '2026-11-20T10:00:00Z', umpires: 'R. Sharma' };
    api.get.mockResolvedValue(base);
    api.update.mockResolvedValue({ ...base, overs: 10 });
    renderPage();
    await user.click(await screen.findByTestId('edit-settings'));
    await user.clear(screen.getByLabelText('Overs per side'));
    await user.type(screen.getByLabelText('Overs per side'), '10');
    await user.click(screen.getByRole('combobox', { name: 'Ball' }));
    await user.click(await screen.findByRole('option', { name: 'Leather ball' }));
    await user.click(screen.getByLabelText('Last man stands'));
    await user.click(screen.getByTestId('save-settings'));

    await waitFor(() => expect(api.update).toHaveBeenCalled());
    const [id, settings, version] = api.update.mock.calls[0];
    expect([id, version]).toEqual(['m-1', 1]);
    expect(settings).toMatchObject({ overs: 10, ballType: 'LEATHER', scheduledAt: '2026-11-20T10:00:00Z', city: 'Chennai', umpires: 'R. Sharma', rules: { lastManStands: true, playersPerSide: 3 } });
    await waitFor(() => expect(screen.queryByTestId('edit-settings-form')).toBeNull());
  });

  it('asks to reload when someone else changed the match, and the form can be cancelled', async () => {
    const user = userEvent.setup();
    api.update.mockRejectedValue(apiError(409, 'OPTIMISTIC_LOCKING_CONFLICT', 'x'));
    renderPage();
    await user.click(await screen.findByTestId('edit-settings'));
    await user.click(screen.getByTestId('save-settings'));
    expect(await screen.findByTestId('settings-error')).toHaveTextContent(/open it again/i);

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByTestId('edit-settings-form')).toBeNull();
  });

  it('shows a refusal from the server of settings that do not fit the playing XI', async () => {
    const user = userEvent.setup();
    api.update.mockRejectedValue(apiError(400, 'VALIDATION_ERROR', 'A playing XI already has more players than the new players-per-side allows'));
    renderPage();
    await user.click(await screen.findByTestId('edit-settings'));
    await user.click(screen.getByTestId('save-settings'));
    expect(await screen.findByTestId('settings-error')).toHaveTextContent(/more players than the new players-per-side/i);
  });

  it('offers settings only to the creator and only before the match starts', async () => {
    api.get.mockResolvedValue({ ...MATCH, canManage: false });
    const first = renderPage();
    await screen.findByTestId('record-toss');
    expect(screen.queryByTestId('edit-settings')).toBeNull();
    first.unmount();

    api.get.mockResolvedValue({ ...MATCH, status: 'IN_PROGRESS' });
    renderPage();
    await screen.findByText(/has started/i);
    expect(screen.queryByTestId('edit-settings')).toBeNull();
  });

  it('offers to start scoring once the match is ready, and not before', async () => {
    renderPage();
    await screen.findByTestId('record-toss');
    expect(screen.queryByTestId('open-scorer')).toBeNull();
  });

  it('opens the scorer screen from a ready match, and from one that has started', async () => {
    const user = userEvent.setup();
    api.get.mockResolvedValue({ ...MATCH, status: 'TOSS_DONE', readyToStart: true });
    const first = renderPage();
    await user.click(await screen.findByTestId('open-scorer'));
    expect(await screen.findByText('SCORER PAGE')).toBeInTheDocument();
    first.unmount();

    api.get.mockResolvedValue({ ...MATCH, status: 'IN_PROGRESS' });
    renderPage();
    expect(await screen.findByTestId('open-scorer')).toHaveTextContent('Open the scorer screen');
  });

  it('does not offer scoring to someone who cannot score', async () => {
    api.get.mockResolvedValue({ ...MATCH, status: 'TOSS_DONE', readyToStart: true, canScore: false, canManage: false, teamA: { ...MATCH.teamA, squad: [] }, teamB: { ...MATCH.teamB, squad: [] } });
    renderPage();
    await screen.findByText(/only its creator or scorer can change the setup/i);
    expect(screen.queryByTestId('open-scorer')).toBeNull();
  });

  it('says so when the match cannot be found (a stranger gets 404)', async () => {
    api.get.mockRejectedValue(apiError(404, 'MATCH_NOT_FOUND', 'Match not found'));
    renderPage();

    expect(await screen.findByText(/couldn't find that match/i)).toBeInTheDocument();
  });
});
