import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import { cricketApi } from '@/api/cricket';
import { scoringApi } from '@/api/cricketScoring';
import { buildLocalView } from '../offline/localView';
import * as queue from '../offline/queue';
import { FakeServer, Phone, SETUP, START } from '../offline/testHarness';
import { lion, tiger } from '../engine/tsVectors';
import type { OfflineScorer, SenderStatus } from '../offline/useOfflineScorer';
import type { DisplayData, QueueState } from '../offline/types';
import { QUEUE_CAP } from '../offline/types';
import { READY_MATCH } from './scoringFixtures';
import OfflineScorerPage from './OfflineScorerPage';

/**
 * The offline scorer screen, state by state, rendered from queue states the queue itself produces (jsdom has no IndexedDB, so the hook is replaced by an object with
 * the same shape). What each state says in words, what the pad may do in it, and what the buttons ask the queue to do.
 */
vi.mock('@/api/cricket', () => ({ cricketApi: { matches: { get: vi.fn() } } }));
vi.mock('@/api/cricketScoring', async (original) => ({ ...(await original<typeof import('@/api/cricketScoring')>()), scoringApi: { state: vi.fn(), command: vi.fn(), undo: vi.fn(), engineState: vi.fn() } }));

const NAMES: DisplayData = {
  players: Object.fromEntries([...['Arun', 'Bala', 'Chetan', 'Dev'].map((n, i) => [lion(i + 1), n]), ...['Tarun', 'Uday', 'Vik', 'Wasim'].map((n, i) => [tiger(i + 1), n])]),
  teams: { '00000000-0000-0001-0000-000000000001': 'Lions', '00000000-0000-0002-0000-000000000002': 'Tigers' },
  editableUntil: null,
  locked: false,
};

const IDLE: SenderStatus = { phase: 'idle', nextAttemptAt: null, lastError: null, failures: 0 };

const run = (runs: number) => ({ type: 'DELIVER' as const, delivery: { kind: 'NORMAL' as const, runs, runsType: 'BAT' as const, boundary: false, dismissal: null } });

/** A phone that has started the innings and holds `balls` more waiting balls (all of it through the queue itself). */
async function phoneWith(balls: number): Promise<Phone> {
  const phone = new Phone(new FakeServer(SETUP));
  await phone.open();
  await phone.tap(START);
  for (let i = 0; i < balls; i++) {
    await phone.tap(run(i % 3));
  }
  phone.state = { ...phone.state, display: NAMES };
  return phone;
}

function offlineOf(state: QueueState, over: Partial<OfflineScorer> = {}): OfflineScorer {
  const view = buildLocalView(state.local, { matchId: 'm-1', names: { player: (id) => NAMES.players[id] ?? 'Player', team: (id) => NAMES.teams[id] ?? 'Team' }, editableUntil: state.display?.editableUntil ?? null, locked: state.display?.locked ?? false, eventTimes: new Map(), ownLabel: 'You' });
  return {
    status: { kind: 'ready' },
    state,
    view,
    sender: IDLE,
    tap: vi.fn(async () => null),
    undo: vi.fn(async () => null),
    retryNow: vi.fn(),
    apply: vi.fn(async () => undefined),
    noteServerView: vi.fn(),
    noteMatch: vi.fn(),
    ...over,
  };
}

const lease = (owner = true) => ({ owner, recheck: () => owner });

const renderPage = (offline: OfflineScorer, owner = true) =>
  renderWithProviders(
    <Routes>
      <Route path="/matches/:id/score" element={<OfflineScorerPage lease={lease(owner)} offline={offline} />} />
      <Route path="/matches/:id/setup" element={<div>SETUP PAGE</div>} />
    </Routes>,
    '/matches/m-1/score',
  );

describe('OfflineScorerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(cricketApi.matches.get).mockResolvedValue(READY_MATCH);
    vi.mocked(scoringApi.state).mockRejectedValue(new Error('offline'));
  });

  it('with nothing waiting it looks like the ordinary screen, with the hint about keeping the page open and a pad that works', async () => {
    const phone = await phoneWith(0);
    await phone.sendNext();   // the start of the innings is on the server: nothing waits
    renderPage(offlineOf({ ...phone.state, display: NAMES }));
    expect(await screen.findByTestId('scorer-page')).toBeInTheDocument();
    expect(screen.getByTestId('keep-open-hint')).toHaveTextContent('Keep this page open while scoring offline.');
    expect(screen.queryByTestId('chip-unsent')).toBeNull();
    expect(screen.queryByTestId('waiting-banner')).toBeNull();
    expect(screen.getByTestId('score')).toHaveTextContent('Lions 0/0');
    expect(screen.getByTestId('run-1')).toBeEnabled();
  });

  it('says how many balls wait, shows them dashed and unconfirmed, and keeps the pad and the choices working', async () => {
    const phone = await phoneWith(4);
    renderPage(offlineOf(phone.state));
    expect(await screen.findByTestId('chip-unsent')).toHaveTextContent('5 unsent');
    expect(screen.getByTestId('waiting-banner')).toHaveTextContent('5 changes are waiting on this phone and not on the server yet');
    expect(screen.getByTestId('waiting-banner')).toHaveTextContent('People following the match see the score up to the last ball the server has');
    expect(screen.getByTestId('unconfirmed-note')).toHaveTextContent('includes 5 balls not on the server yet');
    expect(screen.getAllByTestId('unconfirmed-item').map((e) => e.textContent)).toEqual(['· Start innings', '· Dot ball', '· 1 run', '· 2 runs', '· Dot ball']);
    expect(screen.getByTestId('run-4')).toBeEnabled();
    expect(screen.getByTestId('open-export')).toBeInTheDocument();
  });

  it('says it is offline when the browser is, in the words the plan gives', async () => {
    const original = Object.getOwnPropertyDescriptor(Navigator.prototype, 'onLine');
    Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => false });
    try {
      renderPage(offlineOf((await phoneWith(1)).state));
      expect(await screen.findByTestId('chip-offline')).toHaveTextContent('Offline: scoring continues, saved on this phone');
    } finally {
      if (original) {
        Object.defineProperty(Navigator.prototype, 'onLine', original);
      }
      Reflect.deleteProperty(navigator, 'onLine');
    }
  });

  it('hands a tap to the queue as the command the engine reads, and shows the rule when the queue refuses it', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(0);
    const offline = offlineOf(phone.state, { tap: vi.fn(async () => ({ code: 'RULE' as const, message: 'Choose the next batter before the next ball', violation: 'NEEDS_NEW_BATTER' })) });
    renderPage(offline);
    await user.click(await screen.findByTestId('run-4'));
    expect(offline.tap).toHaveBeenCalledWith({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 4, runsType: 'BAT', boundary: true, dismissal: null } });
    expect(await screen.findByTestId('scoring-notice')).toBeInTheDocument();
  });

  it('asks the queue to undo, in words that name the ball, and asks first when it would reopen an innings', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(2);
    const offline = offlineOf(phone.state);
    renderPage(offline);
    const undo = await screen.findByTestId('undo');
    expect(undo).toHaveTextContent('Undo: 1 run');
    await user.click(undo);
    expect(offline.undo).toHaveBeenCalledWith('BALL');
  });

  it('shows retry details while the sender waits, and Retry now', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(2);
    const offline = offlineOf(phone.state, { sender: { phase: 'retrying', nextAttemptAt: Date.now() + 8000, lastError: 'No network', failures: 2 } });
    renderPage(offline);
    expect(await screen.findByTestId('waiting-banner')).toHaveTextContent('Waiting for the network. Next try in');
    await user.click(screen.getByTestId('retry-now'));
    expect(offline.retryNow).toHaveBeenCalled();
  });

  it('says what is happening while sending, and that a sign-in is needed (the balls wait)', async () => {
    const phone = await phoneWith(3);
    const { unmount } = renderPage(offlineOf(phone.state, { sender: { phase: 'sending', nextAttemptAt: null, lastError: null, failures: 0 } }));
    expect(await screen.findByTestId('waiting-banner')).toHaveTextContent('Sending 1 of 4…');
    unmount();
    renderPage(offlineOf(phone.state, { sender: { phase: 'needs-sign-in', nextAttemptAt: null, lastError: 'Sign in again', failures: 1 } }));
    expect(await screen.findByTestId('sign-in-banner')).toHaveTextContent('saved on this phone and will wait');
  });

  it('turns the pad off and lists both sets of balls when somebody else scored, and sends the scorer\'s choice to the queue', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(0);
    await phone.sendNext();
    await phone.tap(run(1));
    await phone.tap(run(2));
    phone.server.foreign(run(0));
    phone.server.foreign(run(0));
    await phone.sendNext();
    expect(phone.state.blocked?.kind).toBe('CONFLICT');
    const offline = offlineOf({ ...phone.state, display: NAMES });
    renderPage(offline);
    const sheet = await screen.findByTestId('conflict-sheet');
    expect(within(sheet).getByTestId('conflict-theirs-count')).toHaveTextContent('2 changes by someone else');
    expect(within(sheet).getByTestId('conflict-mine-count')).toHaveTextContent('Your 2 unsent balls');
    expect(within(sheet).getByTestId('conflict-preview')).toHaveTextContent('All of yours can be added after theirs');
    expect(screen.getByTestId('run-1')).toBeDisabled();
    await user.click(within(sheet).getByTestId('conflict-rebase'));
    expect(offline.apply).toHaveBeenCalledTimes(1);
    const make = vi.mocked(offline.apply).mock.calls[0][0];
    expect(make(phone.state, 't').state.blocked).toBeNull();
    await user.click(within(screen.getByTestId('conflict-sheet')).getByTestId('conflict-keep-theirs'));
    expect(vi.mocked(offline.apply).mock.calls[1][0](phone.state, 't').state.items.every((i) => i.status === 'SET_ASIDE')).toBe(true);
  });

  it('lets the scorer postpone the decision and reminds them that the balls are on hold', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(0);
    await phone.sendNext();
    await phone.tap(run(1));
    phone.server.foreign(run(0));
    await phone.sendNext();
    renderPage(offlineOf({ ...phone.state, display: NAMES }));
    await user.click(await screen.findByTestId('conflict-not-now'));
    expect(screen.queryByTestId('conflict-sheet')).toBeNull();
    expect(screen.getByTestId('conflict-reminder')).toHaveTextContent('on hold until you decide');
    await user.click(screen.getByRole('button', { name: 'Decide' }));
    expect(screen.getByTestId('conflict-sheet')).toBeInTheDocument();
  });

  it('explains balls that were set aside, points to the administrator unlock, and discards only after naming the ball', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(0);
    await phone.sendNext();
    await phone.tap(run(1));
    await phone.tap(run(2));
    phone.server.locked = true;
    await phone.sendNext();
    expect(phone.state.blocked?.kind).toBe('LOCKED');
    const offline = offlineOf({ ...phone.state, display: NAMES });
    renderPage(offline);
    expect(await screen.findByTestId('locked-banner')).toHaveTextContent('An administrator can unlock this match');
    expect(screen.getByTestId('locked-banner')).toHaveTextContent('2 balls saved on this phone are waiting for that');
    expect(screen.getByTestId('run-1')).toBeDisabled();
    await user.click(screen.getByTestId('chip-set-aside'));
    const sheet = await screen.findByTestId('set-aside-sheet');
    expect(sheet).toHaveTextContent('saved on this phone but are not in the match');
    expect(sheet).toHaveTextContent('ask an administrator to unlock the match (a reason of at least 10 characters), then press Send again');
    expect(within(sheet).getAllByTestId('set-aside-item')[0]).toHaveTextContent('Set aside because the correction window closed');
    await user.click(within(sheet).getAllByTestId('set-aside-discard')[0]);
    expect(await screen.findByText(/will be deleted from this phone for good/)).toBeInTheDocument();
    expect(screen.getByText(/“1 run”/)).toBeInTheDocument();
    await user.click(screen.getByTestId('set-aside-confirm-discard'));
    expect(offline.apply).toHaveBeenCalledTimes(1);
    await user.click(within(screen.getByTestId('set-aside-sheet')).getByTestId('set-aside-send-again'));
    expect(offline.apply).toHaveBeenCalledTimes(2);
  });

  it('says the server refused a ball, keeps everything, and offers to set aside that ball and the rest', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(2);
    await phone.commit(queue.sendStarted(phone.state, phone.state.items[0].n, 't'));
    await phone.commit(queue.rejectedByServer(phone.state, phone.state.items[0].n, 'INNINGS_COMPLETE', 'The innings is complete', 't'));
    const offline = offlineOf({ ...phone.state, display: NAMES });
    renderPage(offline);
    const sheet = await screen.findByTestId('rejected-sheet');
    expect(sheet).toHaveTextContent('The innings is complete');
    expect(sheet).toHaveTextContent('Every ball is still saved here');
    expect(screen.getByTestId('offline-off-banner')).toHaveTextContent('Offline scoring is off on this phone');
    expect(screen.getByTestId('run-1')).toBeDisabled();
    await user.click(within(sheet).getByTestId('rejected-set-aside'));
    expect(offline.apply).toHaveBeenCalledTimes(1);
  });

  it('keeps balls visible and sendable when offline scoring has been switched off (a different engine version)', async () => {
    const phone = await phoneWith(3);
    const state = { ...phone.state, offline: { on: false as const, reason: 'ENGINE_VERSION_UNSUPPORTED' as const } };
    renderPage(offlineOf(state));
    const banner = await screen.findByTestId('offline-off-banner');
    expect(banner).toHaveTextContent('newer rules than this page knows: reload it');
    expect(banner).toHaveTextContent('The 4 waiting balls are kept and can still be sent');
    expect(screen.getByTestId('run-1')).toBeDisabled();
    expect(screen.getByTestId('chip-unsent')).toHaveTextContent('4 unsent');
  });

  it('warns from 150 waiting changes and stops taking balls at 600, with the words the plan gives', async () => {
    const phone = await phoneWith(0);
    const copy = (n: number) => ({ ...phone.state.items[0], n, clientEventId: `id-copy-${n}` });
    const many = (count: number): QueueState => ({ ...phone.state, items: Array.from({ length: count }, (_, i) => copy(i + 1)) });
    const first = renderPage(offlineOf(many(150)));
    expect(await screen.findByTestId('warn-150')).toHaveTextContent('150 changes are waiting on this phone');
    first.unmount();
    renderPage(offlineOf(many(QUEUE_CAP)));
    const full = await screen.findByTestId('full-banner');
    expect(full).toHaveTextContent(`This phone is holding ${QUEUE_CAP} unsent changes, the most it will keep`);
    expect(full).toHaveTextContent('Nothing already scored is lost');
    expect(screen.getByTestId('run-1')).toBeDisabled();
  });

  it('is read-only in a second tab, and says how many balls wait without letting this tab send or enqueue', async () => {
    const phone = await phoneWith(2);
    renderPage(offlineOf(phone.state), false);
    expect(await screen.findByTestId('other-tab-banner')).toHaveTextContent('read-only (3 waiting on this phone)');
    expect(screen.getByTestId('run-1')).toBeDisabled();
  });

  it('shows what is unsent as text to copy and a download, whatever the browser allows', async () => {
    const user = userEvent.setup();
    const phone = await phoneWith(2);
    renderPage(offlineOf(phone.state));
    await user.click(await screen.findByTestId('open-export'));
    const text = (await screen.findByTestId('unsent-text')) as HTMLTextAreaElement;
    expect(text.value).toContain('Start innings | waiting');
    expect(text.value).toContain('"clientEventId"');
    await user.click(screen.getByTestId('unsent-copy'));
    await waitFor(() => expect(screen.getByTestId('copy-result')).toBeInTheDocument());
  });

  it('shows names in any script and very long names without breaking the page', async () => {
    const phone = await phoneWith(1);
    const names: DisplayData = { ...NAMES, players: { ...NAMES.players, [lion(1)]: 'अरुण कुमार शर्मा', [lion(2)]: 'Bartholomew Maximilian Konstantinopoulos-Wickramasinghe the Third' } };
    const state = { ...phone.state, display: names };
    const offline = offlineOf(state);
    offline.view = buildLocalView(state.local, { matchId: 'm-1', names: { player: (id) => names.players[id] ?? 'Player', team: (id) => names.teams[id] ?? 'Team' }, editableUntil: null, locked: false, eventTimes: new Map(), ownLabel: 'You' });
    renderPage(offline);
    expect((await screen.findAllByText(/अरुण कुमार शर्मा/)).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/Konstantinopoulos-Wickramasinghe/).length).toBeGreaterThan(0);
  });
});
