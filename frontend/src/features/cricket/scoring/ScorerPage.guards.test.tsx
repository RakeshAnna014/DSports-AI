import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import ScorerPage from './ScorerPage';
import { cricketApi } from '@/api/cricket';
import { scoringApi } from '@/api/cricketScoring';
import { READY_MATCH, VIEW, view, withInnings } from './scoringFixtures';
import type { ScoringOutcome, ScoringView } from '@/types/cricketScoring';

vi.mock('@/api/cricket', () => ({ cricketApi: { matches: { get: vi.fn() } } }));
vi.mock('@/api/cricketScoring', async (original) => ({ ...(await original<typeof import('@/api/cricketScoring')>()), scoringApi: { state: vi.fn(), command: vi.fn(), undo: vi.fn() } }));

const api = vi.mocked(scoringApi);
const OUTBOX_KEY = 'dsports.scoring.outbox.m-1';
const LEASE_KEY = 'dsports.scoring.tab.m-1';
const outcome = (v: ScoringView): ScoringOutcome => ({ view: v, replayed: false, eventSeq: v.seq });

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/matches/:id/score" element={<ScorerPage />} />
  </Routes>, '/matches/m-1/score');

/** Only the clock and the interval are faked, so the page's own loading (which uses ordinary timers) still settles. */
const fakeClock = (at: string) => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.setSystemTime(new Date(at));
};
const NOW = '2026-11-20T12:00:00Z';
const inMinutes = (minutes: number) => new Date(new Date(NOW).getTime() + minutes * 60000).toISOString();
const tick = (ms: number) => act(() => { vi.advanceTimersByTime(ms); });

const FINISHED = { matchStatus: 'COMPLETED', result: { type: 'WON_BY_RUNS', winner: null, margin: 5, ballsRemaining: 0, text: 'Lions won by 5 runs' } } as const;

beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  vi.mocked(cricketApi.matches.get).mockResolvedValue(READY_MATCH);
  api.state.mockResolvedValue(VIEW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the correction window: warning, countdown and the locked message', () => {
  it('shows no warning while more than an hour is left, only the plain line', async () => {
    fakeClock(NOW);
    api.state.mockResolvedValue(view({ ...FINISHED, editableUntil: inMinutes(61) }));
    renderPage();

    expect(await screen.findByTestId('time-left')).toHaveTextContent('You can correct this match for 1 h 1 min.');
    expect(screen.queryByTestId('lock-warning')).toBeNull();
  });

  it('warns in the last hour, with the minutes left and what to do', async () => {
    fakeClock(NOW);
    api.state.mockResolvedValue(view({ ...FINISHED, editableUntil: inMinutes(38) }));
    renderPage();

    expect(await screen.findByTestId('lock-warning')).toHaveTextContent('This match locks in 38 min. Finish your correction or ask an administrator to unlock it.');
    expect(screen.getByTestId('time-left')).toHaveTextContent('You can correct this match for 38 min.');
  });

  it('warns at exactly one hour left and not before', async () => {
    fakeClock(NOW);
    api.state.mockResolvedValue(view({ ...FINISHED, editableUntil: inMinutes(60) }));
    renderPage();

    expect(await screen.findByTestId('lock-warning')).toHaveTextContent('This match locks in 1 h 0 min.');
  });

  it('counts down on its own, and the warning appears when the last hour begins, with no reload', async () => {
    fakeClock(NOW);
    api.state.mockResolvedValue(view({ ...FINISHED, editableUntil: inMinutes(63) }));
    renderPage();
    expect(await screen.findByTestId('time-left')).toHaveTextContent('1 h 3 min');
    expect(screen.queryByTestId('lock-warning')).toBeNull();

    tick(60_000);
    expect(screen.getByTestId('time-left')).toHaveTextContent('1 h 2 min');
    tick(2 * 60_000);
    expect(screen.getByTestId('time-left')).toHaveTextContent('You can correct this match for 1 h 0 min.');
    expect(screen.getByTestId('lock-warning')).toHaveTextContent('This match locks in 1 h 0 min.');
    tick(22 * 60_000);
    expect(screen.getByTestId('lock-warning')).toHaveTextContent('This match locks in 38 min.');
    expect(api.state).toHaveBeenCalledTimes(1);
  });

  it('shows the warning and the countdown during a correction in progress too, not only on a finished match', async () => {
    fakeClock(NOW);
    api.state.mockResolvedValue(view({ matchStatus: 'IN_PROGRESS', editableUntil: inMinutes(20) }));
    renderPage();

    expect(await screen.findByTestId('lock-warning')).toHaveTextContent('This match locks in 20 min.');
    expect(screen.getByTestId('time-left')).toHaveTextContent('20 min');
    expect(screen.getByTestId('run-1')).toBeEnabled();
  });

  it('asks the server again when the time is up, and shows the lock it answers with', async () => {
    fakeClock(NOW);
    api.state.mockResolvedValueOnce(view({ ...FINISHED, editableUntil: inMinutes(2) }))
      .mockResolvedValue(view({ ...FINISHED, editableUntil: inMinutes(2), locked: true, canUndo: { allowed: false, describe: null, crossesInnings: false, blockedReason: 'LOCKED' } }));
    renderPage();
    await screen.findByTestId('lock-warning');
    expect(api.state).toHaveBeenCalledTimes(1);

    tick(3 * 60_000);

    await waitFor(() => expect(api.state).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId('locked-banner')).toBeInTheDocument();
    expect(screen.queryByTestId('lock-warning')).toBeNull();
    expect(screen.queryByTestId('time-left')).toBeNull();
  });

  it('says "finished more than 24 hours ago" when the match had finished', async () => {
    api.state.mockResolvedValue(view({ ...FINISHED, locked: true, editableUntil: inMinutes(-5), canUndo: { allowed: false, describe: null, crossesInnings: false, blockedReason: 'LOCKED' } }));
    renderPage();

    expect(await screen.findByTestId('locked-banner')).toHaveTextContent('This match was finished more than 24 hours ago and is locked. An administrator can unlock it.');
  });

  it('says the window closed during a correction when the match was left in progress', async () => {
    api.state.mockResolvedValue(withInnings({}, { matchStatus: 'IN_PROGRESS', locked: true, editableUntil: inMinutes(-5), canUndo: { allowed: false, describe: null, crossesInnings: false, blockedReason: 'LOCKED' } }));
    renderPage();

    const banner = await screen.findByTestId('locked-banner');
    expect(banner).toHaveTextContent('The correction window closed while the match was in progress. An administrator can unlock it.');
    expect(banner).not.toHaveTextContent('finished more than 24 hours ago');
    expect(screen.getByTestId('run-1')).toBeDisabled();
  });
});

describe('a second tab of the same match', () => {
  const otherTab = (ageMs = 0) => localStorage.setItem(LEASE_KEY, JSON.stringify({ tab: 'another-tab', at: Date.now() - ageMs }));

  it('is the scorer when no other tab holds the match, and the lease is released when the page closes', async () => {
    const { unmount } = renderPage();
    await screen.findByTestId('scorer-page');

    expect(screen.queryByTestId('other-tab-banner')).toBeNull();
    expect(screen.getByTestId('run-1')).toBeEnabled();
    expect(JSON.parse(localStorage.getItem(LEASE_KEY)!)).toMatchObject({ at: expect.any(Number) });
    unmount();
    expect(localStorage.getItem(LEASE_KEY)).toBeNull();
  });

  it('is read-only when another tab holds the match: says so, offers nothing to change, and does not touch the saved ball', async () => {
    otherTab();
    const saved = JSON.stringify({ kind: 'command', clientEventId: 'saved-id-1', expectedSeq: 12, command: { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 1, runsType: 'BAT', boundary: false } }, label: '1 run', createdAt: '2026-11-20T10:00:00Z' });
    localStorage.setItem(OUTBOX_KEY, saved);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPage();

    expect(await screen.findByTestId('other-tab-banner')).toHaveTextContent('read-only');
    for (const id of ['run-1', 'run-4', 'btn-wicket', 'undo', 'more']) {
      expect(screen.getByTestId(id)).toBeDisabled();
      await user.click(screen.getByTestId(id));
    }
    expect(screen.queryByTestId('outbox-banner')).toBeNull();
    expect(api.command).not.toHaveBeenCalled();
    expect(api.undo).not.toHaveBeenCalled();
    expect(localStorage.getItem(OUTBOX_KEY)).toBe(saved);
    expect(JSON.parse(localStorage.getItem(LEASE_KEY)!).tab).toBe('another-tab');
  });

  it('keeps the score fresh while read-only', async () => {
    fakeClock(NOW);
    otherTab();
    renderPage();
    await screen.findByTestId('other-tab-banner');
    expect(api.state).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(5000); });

    expect(api.state.mock.calls.length).toBeGreaterThan(1);
  });

  it('does not show the blocking "who bats next" sheet over the explanation', async () => {
    otherTab();
    api.state.mockResolvedValue(view({ needs: 'BATTER', eligibleBatters: [{ player: { id: 'a-3', name: 'Chetan' }, note: null, returning: false }] }));
    renderPage();

    expect(await screen.findByTestId('other-tab-banner')).toBeInTheDocument();
    expect(screen.queryByText('Who bats next?')).toBeNull();
  });

  it('takes over when the other tab goes quiet, and then sends the ball it left saved, once', async () => {
    fakeClock(NOW);
    otherTab();
    localStorage.setItem(OUTBOX_KEY, JSON.stringify({ kind: 'command', clientEventId: 'saved-id-1', expectedSeq: 12, command: { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 1, runsType: 'BAT', boundary: false } }, label: '1 run', createdAt: '2026-11-20T10:00:00Z' }));
    api.command.mockResolvedValue(outcome(withInnings({ runs: 15 }, { seq: 13 })));
    renderPage();
    await screen.findByTestId('other-tab-banner');
    expect(api.command).not.toHaveBeenCalled();

    tick(5000);
    expect(screen.getByTestId('other-tab-banner')).toBeInTheDocument();
    tick(7000);

    await waitFor(() => expect(screen.queryByTestId('other-tab-banner')).toBeNull());
    await waitFor(() => expect(api.command).toHaveBeenCalledTimes(1));
    expect(api.command.mock.calls[0][1].clientEventId).toBe('saved-id-1');
    expect(JSON.parse(localStorage.getItem(LEASE_KEY)!).tab).not.toBe('another-tab');
    await waitFor(() => expect(localStorage.getItem(OUTBOX_KEY)).toBeNull());
  });

  it('steps aside at once when another tab claims the match (storage event)', async () => {
    renderPage();
    await screen.findByTestId('scorer-page');
    expect(screen.getByTestId('run-1')).toBeEnabled();

    act(() => {
      otherTab();
      window.dispatchEvent(new StorageEvent('storage', { key: LEASE_KEY }));
    });

    expect(await screen.findByTestId('other-tab-banner')).toBeInTheDocument();
    expect(screen.getByTestId('run-1')).toBeDisabled();
  });

  describe('a tab that was frozen in the background', () => {
    const takenOverSilently = () => localStorage.setItem(LEASE_KEY, JSON.stringify({ tab: 'another-tab', at: Date.now() }));
    const comeBack = () => {
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      act(() => { document.dispatchEvent(new Event('visibilitychange')); });
    };

    it('looks at the lease as soon as it is visible again and becomes read-only if another tab took over (no storage event reached it)', async () => {
      renderPage();
      await screen.findByTestId('scorer-page');
      expect(screen.getByTestId('run-1')).toBeEnabled();

      takenOverSilently();
      comeBack();

      expect(await screen.findByTestId('other-tab-banner')).toBeInTheDocument();
      expect(screen.getByTestId('run-1')).toBeDisabled();
    });

    it('does the same when the window gets focus again', async () => {
      renderPage();
      await screen.findByTestId('scorer-page');

      takenOverSilently();
      act(() => { window.dispatchEvent(new Event('focus')); });

      expect(await screen.findByTestId('other-tab-banner')).toBeInTheDocument();
    });

    it('does not send a tapped ball if another tab took over while it was frozen, and does not save it either', async () => {
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      renderPage();
      await user.click(await screen.findByTestId('run-1').then((b) => { takenOverSilently(); return b; }));

      expect(await screen.findByTestId('other-tab-banner')).toBeInTheDocument();
      expect(api.command).not.toHaveBeenCalled();
      expect(localStorage.getItem(OUTBOX_KEY)).toBeNull();
    });

    it('does not retry a waiting ball when the network returns after another tab took over, and leaves it saved for that tab', async () => {
      const user = userEvent.setup();
      api.command.mockRejectedValue(new (await import('axios')).AxiosError('Network Error', 'ERR_NETWORK'));
      renderPage();
      await user.click(await screen.findByTestId('run-1'));
      await screen.findByTestId('outbox-banner');
      const saved = localStorage.getItem(OUTBOX_KEY);
      expect(api.command).toHaveBeenCalledTimes(1);

      takenOverSilently();
      act(() => { window.dispatchEvent(new Event('online')); });

      expect(await screen.findByTestId('other-tab-banner')).toBeInTheDocument();
      expect(api.command).toHaveBeenCalledTimes(1);
      expect(localStorage.getItem(OUTBOX_KEY)).toBe(saved);
      expect(screen.queryByTestId('outbox-banner')).toBeNull();
    });
  });

  it('still lets a lease that is long out of date go: an old record from a closed tab does not lock the match', async () => {
    otherTab(60_000);
    renderPage();
    await screen.findByTestId('scorer-page');

    expect(screen.queryByTestId('other-tab-banner')).toBeNull();
    expect(screen.getByTestId('run-1')).toBeEnabled();
  });

  it('works when browser storage is blocked: the page just scores', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('blocked'); });
    renderPage();
    await screen.findByTestId('scorer-page');

    expect(screen.queryByTestId('other-tab-banner')).toBeNull();
    expect(screen.getByTestId('run-1')).toBeEnabled();
    vi.restoreAllMocks();
  });
});
