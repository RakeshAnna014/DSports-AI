import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { renderWithProviders } from '@/test/render';
import ScorerPage from './ScorerPage';
import { cricketApi } from '@/api/cricket';
import { scoringApi } from '@/api/cricketScoring';
import { ARUN, BALA, CHETAN, FREE_HIT_ALLOWED, READY_MATCH, TARUN, UDAY, VIEW, VIK, conflictOf, networkError, serverError, view, withInnings } from './scoringFixtures';
import type { ScoringOutcome, ScoringView } from '@/types/cricketScoring';

vi.mock('@/api/cricket', () => ({ cricketApi: { matches: { get: vi.fn() } } }));
vi.mock('@/api/cricketScoring', async (original) => ({ ...(await original<typeof import('@/api/cricketScoring')>()), scoringApi: { state: vi.fn(), command: vi.fn(), undo: vi.fn() } }));

const api = vi.mocked(scoringApi);
const OUTBOX_KEY = 'dsports.scoring.outbox.m-1';

const outcome = (v: ScoringView, replayed = false): ScoringOutcome => ({ view: v, replayed, eventSeq: v.seq });

const renderPage = () => renderWithProviders(
  <Routes>
    <Route path="/matches/:id/score" element={<ScorerPage />} />
    <Route path="/matches/:id/setup" element={<div>SETUP PAGE</div>} />
  </Routes>, '/matches/m-1/score');

describe('ScorerPage', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    vi.mocked(cricketApi.matches.get).mockResolvedValue(READY_MATCH);
    api.state.mockResolvedValue(VIEW);
  });

  const lastCommand = () => api.command.mock.calls[api.command.mock.calls.length - 1][1];

  // ---- sharing -------------------------------------------------------------------------------------------------------

  it('offers the public scorecard link once an innings has started, and not before', async () => {
    api.state.mockResolvedValue(view({ matchStatus: 'TOSS_DONE', seq: 0, innings: [], canUndo: { allowed: false, describe: null, crossesInnings: false, blockedReason: 'NOTHING_TO_UNDO' } }));
    const first = renderPage();
    await screen.findByText('Lions bat, Tigers bowl.');
    expect(screen.queryByTestId('share-scorecard')).toBeNull();
    first.unmount();

    api.state.mockResolvedValue(VIEW);
    renderPage();
    const share = await screen.findByTestId('share-scorecard');
    expect(within(share).getByTestId('whatsapp-share').getAttribute('href')).toContain(encodeURIComponent(`${window.location.origin}/m/m-1`));
  });

  // ---- starting an innings -----------------------------------------------------------------------------------------

  it('starts the first innings for the side that bats first, with the openers and bowler chosen', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(view({ matchStatus: 'TOSS_DONE', seq: 0, innings: [], canUndo: { allowed: false, describe: null, crossesInnings: false, blockedReason: 'NOTHING_TO_UNDO' } }));
    api.command.mockResolvedValue(outcome(VIEW));
    renderPage();

    expect(await screen.findByText('Lions bat, Tigers bowl.')).toBeInTheDocument();
    await user.click(screen.getByTestId('start-innings-button'));

    await waitFor(() => expect(api.command).toHaveBeenCalled());
    expect(lastCommand()).toMatchObject({ expectedSeq: 0, command: { type: 'START_INNINGS', striker: 'a-1', nonStriker: 'a-2', bowler: 'b-1' } });
    expect(lastCommand().clientEventId).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByTestId('score')).toHaveTextContent('Lions 14/1');
  });

  it('will not open an innings with the same batter twice', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(view({ matchStatus: 'TOSS_DONE', seq: 0, innings: [] }));
    renderPage();
    await screen.findByTestId('start-innings');
    await user.click(screen.getByRole('combobox', { name: 'Opening non-striker' }));
    expect(await screen.findByRole('option', { name: 'Arun' })).toHaveAttribute('aria-disabled', 'true');
  });

  it('says to finish the setup when the toss has not been recorded', async () => {
    api.state.mockResolvedValue(view({ matchStatus: 'TOSS_DONE', seq: 0, innings: [] }));
    vi.mocked(cricketApi.matches.get).mockResolvedValue({ ...READY_MATCH, battingFirstTeamId: null });
    renderPage();
    expect(await screen.findByText(/record the toss and both playing xis/i)).toBeInTheDocument();
  });

  // ---- the ball screen --------------------------------------------------------------------------------------------

  it('shows the score, the batters with the striker marked, the bowler, the balls of the over and the chase', async () => {
    api.state.mockResolvedValue(withInnings({ target: 30, runsNeeded: 16, ballsRemaining: 20 }));
    renderPage();

    expect(await screen.findByTestId('score')).toHaveTextContent('Lions 14/1');
    expect(screen.getByTestId('overs')).toHaveTextContent('1.4 ov');
    expect(screen.getByTestId('chase')).toHaveTextContent('Target 30. Need 16 off 20 balls.');
    const batters = screen.getAllByTestId('at-crease');
    expect(batters[0]).toHaveTextContent('Arun *');
    expect(batters[0]).toHaveTextContent('9 (6)');
    expect(batters[1]).toHaveTextContent('Bala');
    expect(screen.getByTestId('bowler')).toHaveTextContent('Tarun 0.4-0-10-0');
    expect(within(screen.getByTestId('this-over')).getAllByText(/./).map((c) => c.textContent)).toEqual(['1', '•', 'Wd', '4']);
  });

  it('sends a single run from the seq it last saw, with a client event id, and shows the answer', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(withInnings({ runs: 15 }, { seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('run-1'));

    await waitFor(() => expect(api.command).toHaveBeenCalledTimes(1));
    expect(lastCommand()).toMatchObject({ expectedSeq: 12, command: { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 1, runsType: 'BAT', boundary: false } } });
    expect(await screen.findByText('Lions 15/1')).toBeInTheDocument();
    expect(localStorage.getItem(OUTBOX_KEY)).toBeNull();
    expect(screen.getByTestId('run-1')).toBeEnabled();
  });

  it('counts four and six as boundaries', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(withInnings({ runs: 18 }, { seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('run-4'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { runs: 4, boundary: true } }));
    await screen.findByText('Lions 18/1');
    api.command.mockResolvedValue(outcome(withInnings({ runs: 24 }, { seq: 14 })));
    await user.click(screen.getByTestId('run-6'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { runs: 6, boundary: true } }));
  });

  it('sends wides, no-balls, byes and leg byes with their runs', async () => {
    const user = userEvent.setup();
    let seq = 12;
    api.command.mockImplementation(async () => outcome(view({ seq: ++seq })));
    renderPage();

    await user.click(await screen.findByTestId('btn-wide'));
    await user.click(screen.getByTestId('wide-2'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { kind: 'WIDE', runs: 2 } }));

    await user.click(await screen.findByTestId('btn-noball'));
    await user.click(screen.getByTestId('nb-bat-4'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { kind: 'NO_BALL', runs: 4, runsType: 'BAT', boundary: true } }));

    await user.click(await screen.findByTestId('btn-noball'));
    await user.click(screen.getByTestId('nb-bye-2'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { kind: 'NO_BALL', runs: 2, runsType: 'BYE' } }));

    await user.click(await screen.findByTestId('btn-bye'));
    await user.click(screen.getByTestId('bye-1'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { kind: 'NORMAL', runs: 1, runsType: 'BYE' } }));

    await user.click(await screen.findByTestId('btn-legbye'));
    await user.click(screen.getByTestId('legbye-3'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { kind: 'NORMAL', runs: 3, runsType: 'LEG_BYE' } }));
    expect(api.command).toHaveBeenCalledTimes(5);
  });

  it('flags a free hit', async () => {
    api.state.mockResolvedValue(withInnings({}, { freeHit: true, allowedDismissals: FREE_HIT_ALLOWED }));
    renderPage();
    expect(await screen.findByTestId('free-hit')).toBeInTheDocument();
  });

  // ---- a wicket ----------------------------------------------------------------------------------------------------

  it('offers all eight ways on an ordinary ball, only the allowed ones on a free hit, and sends nothing until confirmed', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('btn-wicket'));
    expect(screen.getAllByTestId(/^how-/)).toHaveLength(8);
    await user.click(screen.getByTestId('how-BOWLED'));
    expect(api.command).not.toHaveBeenCalled();
    await user.click(screen.getByTestId('confirm-wicket'));

    await waitFor(() => expect(lastCommand().command).toMatchObject({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 0, dismissal: { type: 'BOWLED' } } }));
  });

  it('on a free hit only run out, obstructing and a double hit are offered', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(withInnings({}, { freeHit: true, allowedDismissals: FREE_HIT_ALLOWED }));
    renderPage();
    await user.click(await screen.findByTestId('btn-wicket'));

    expect(screen.getAllByTestId(/^how-/).map((b) => b.getAttribute('data-testid'))).toEqual(['how-RUN_OUT', 'how-OBSTRUCTING_FIELD', 'how-HIT_BALL_TWICE']);
  });

  it('records the catcher when one is chosen and leaves him out when not', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('btn-wicket'));
    await user.click(screen.getByTestId('how-CAUGHT'));
    await user.click(screen.getByRole('combobox', { name: /fielder/i }));
    await user.click(await screen.findByRole('option', { name: 'Vik' }));
    await user.click(screen.getByTestId('confirm-wicket'));

    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { dismissal: { type: 'CAUGHT', fielderId: 'b-3' } } }));
  });

  it('a wicket on a wide offers only what a wide allows', async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByTestId('btn-wide'));
    await user.click(screen.getByTestId('wide-wicket'));

    expect(screen.getAllByTestId(/^how-/).map((b) => b.getAttribute('data-testid'))).toEqual(['how-STUMPED', 'how-HIT_WICKET', 'how-RUN_OUT', 'how-OBSTRUCTING_FIELD']);
  });

  // ---- the run out ------------------------------------------------------------------------------------------------

  it('defaults the end of a run out from the runs completed and lets the scorer change it', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('btn-wicket'));
    await user.click(screen.getByTestId('how-RUN_OUT'));

    expect(screen.getByTestId('end-bowler')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('run-out-preview')).toHaveTextContent('Arun is out. Bala will face.');
    await user.click(screen.getByRole('button', { name: 'More runs' }));
    expect(screen.getByTestId('runs-completed')).toHaveTextContent('1');
    expect(screen.getByTestId('end-striker')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('run-out-preview')).toHaveTextContent('The new batter will face.');
    await user.click(screen.getByRole('button', { name: 'More runs' }));
    expect(screen.getByTestId('end-bowler')).toHaveAttribute('aria-pressed', 'true');

    await user.click(screen.getByTestId('who-nonstriker'));
    expect(screen.getByTestId('end-striker')).toHaveAttribute('aria-pressed', 'true');
    await user.click(screen.getByTestId('end-bowler'));
    expect(screen.getByTestId('end-hint')).toHaveTextContent('You changed the end');
    await user.click(screen.getByTestId('confirm-wicket'));

    await waitFor(() => expect(lastCommand().command).toMatchObject({
      delivery: { kind: 'NORMAL', runs: 2, dismissal: { type: 'RUN_OUT', batterOut: 'a-2', end: 'BOWLERS_END' } },
    }));
  });

  it('sends the odd-run default of the hand-scored innings: the striker out at the striker end after one run', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('btn-wicket'));
    await user.click(screen.getByTestId('how-RUN_OUT'));
    await user.click(screen.getByRole('button', { name: 'More runs' }));
    await user.click(screen.getByTestId('confirm-wicket'));

    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { runs: 1, dismissal: { type: 'RUN_OUT', batterOut: 'a-1', end: 'BATTING_END' } } }));
  });

  it('on the last ball of an over the preview says the partner faces after a run out at the striker end', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(withInnings({ overs: '1.5' }));
    renderPage();
    await user.click(await screen.findByTestId('btn-wicket'));
    await user.click(screen.getByTestId('how-RUN_OUT'));
    await user.click(screen.getByTestId('end-striker'));

    expect(screen.getByTestId('run-out-preview')).toHaveTextContent('Bala will face.');
  });

  // ---- what the server says is needed -----------------------------------------------------------------------------

  it('after a wicket the next batter must be chosen before anything else, and returning batters say so', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(withInnings({ striker: null }, { needs: 'BATTER', eligibleBatters: [{ player: CHETAN, returning: false, note: null }, { player: ARUN, returning: true, note: 'retired hurt, 2 off 2' }] }));
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();

    expect(await screen.findByText('Who bats next?')).toBeInTheDocument();
    expect(screen.getByTestId('run-1')).toBeDisabled();
    expect(screen.getByText('retired hurt, 2 off 2')).toBeInTheDocument();
    await user.click(screen.getByText('Chetan'));

    await waitFor(() => expect(lastCommand().command).toEqual({ type: 'SELECT_BATTER', batterId: 'a-3' }));
  });

  it('at the end of an over the next bowler is chosen; the one who bowled the last over and anyone at the limit are greyed with the reason', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(withInnings({}, {
      needs: 'BOWLER',
      bowlers: [
        { player: TARUN, overs: '1.0', creditedOvers: 1, limit: 1, canBowlNext: false, canReplaceMidOver: false, why: 'BOWLED_LAST_OVER' },
        { player: UDAY, overs: '1.0', creditedOvers: 1, limit: 1, canBowlNext: false, canReplaceMidOver: false, why: 'AT_LIMIT' },
        { player: VIK, overs: '0.0', creditedOvers: 0, limit: 1, canBowlNext: true, canReplaceMidOver: false, why: null },
      ],
    }));
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();

    expect(await screen.findByText('Who bowls the next over?')).toBeInTheDocument();
    const choices = screen.getAllByTestId('choice');
    expect(choices[0]).toHaveAttribute('aria-disabled', 'true');
    expect(choices[0]).toHaveTextContent('bowled the last over');
    expect(choices[1]).toHaveAttribute('aria-disabled', 'true');
    expect(choices[1]).toHaveTextContent('at the limit');
    expect(screen.queryByTestId('chooser-notice')).toBeNull();
    await user.click(choices[2]);

    await waitFor(() => expect(lastCommand().command).toEqual({ type: 'SELECT_BOWLER', bowlerId: 'b-3' }));
  });

  it('when everyone is at the limit the screen says so before the scorer allows someone', async () => {
    api.state.mockResolvedValue(withInnings({}, {
      needs: 'BOWLER', waiverRequired: true,
      bowlers: [{ player: UDAY, overs: '1.0', creditedOvers: 1, limit: 1, canBowlNext: true, canReplaceMidOver: false, why: null }],
    }));
    renderPage();
    expect(await screen.findByTestId('chooser-notice')).toHaveTextContent('Everyone left is at the over limit');
  });

  // ---- the outbox: offline, reload, retry -------------------------------------------------------------------------------

  it('saves a ball on the phone when the network is down, blocks the next ball, and sends it again with the same id when asked', async () => {
    const user = userEvent.setup();
    api.command.mockRejectedValueOnce(networkError()).mockResolvedValue(outcome(withInnings({ runs: 15 }, { seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('run-1'));

    const banner = await screen.findByTestId('outbox-banner');
    expect(banner).toHaveTextContent('Saved on this phone: 1 run');
    expect(banner).toHaveTextContent('Waiting for the network');
    const saved = JSON.parse(localStorage.getItem(OUTBOX_KEY)!);
    expect(saved).toMatchObject({ kind: 'command', expectedSeq: 12, label: '1 run' });
    expect(screen.getByTestId('run-2')).toBeDisabled();
    expect(screen.getByTestId('undo')).toBeDisabled();

    await user.click(screen.getByTestId('retry-now'));
    await waitFor(() => expect(screen.queryByTestId('outbox-banner')).toBeNull());
    expect(api.command).toHaveBeenCalledTimes(2);
    expect(api.command.mock.calls[1][1].clientEventId).toBe(api.command.mock.calls[0][1].clientEventId);
    expect(api.command.mock.calls[1][1].clientEventId).toBe(saved.clientEventId);
    expect(localStorage.getItem(OUTBOX_KEY)).toBeNull();
    expect(screen.getByTestId('run-2')).toBeEnabled();
  });

  it('does not accept another ball, a wicket, an undo or More while one ball is waiting, says why, and keeps the waiting ball untouched', async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    api.command.mockRejectedValue(networkError());
    renderPage();
    await user.click(await screen.findByTestId('run-1'));
    const banner = await screen.findByTestId('outbox-banner');
    const saved = localStorage.getItem(OUTBOX_KEY);
    expect(banner).toHaveTextContent('Scoring is paused until it is sent');

    for (const id of ['run-4', 'run-2', 'btn-wicket', 'undo', 'more']) {
      expect(screen.getByTestId(id)).toBeDisabled();
      await user.click(screen.getByTestId(id));
    }

    expect(api.undo).not.toHaveBeenCalled();
    expect(api.command).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(OUTBOX_KEY)).toBe(saved);
    expect(screen.getByTestId('outbox-banner')).toHaveTextContent('Saved on this phone: 1 run');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('a ball sent after the lock fell is dropped with the server\'s reason (not retried for ever) and the screen shows the lock', async () => {
    const user = userEvent.setup();
    api.command.mockRejectedValue(serverError(409, 'MATCH_LOCKED', 'This match was finished more than 24 hours ago and is locked. An administrator can unlock it.'));
    api.state.mockResolvedValueOnce(VIEW).mockResolvedValue(view({ matchStatus: 'IN_PROGRESS', locked: true, canUndo: { allowed: false, describe: null, crossesInnings: false, blockedReason: 'LOCKED' } }));
    renderPage();
    await user.click(await screen.findByTestId('run-1'));

    expect(await screen.findByTestId('scoring-notice')).toHaveTextContent('is locked');
    expect(await screen.findByTestId('locked-banner')).toBeInTheDocument();
    expect(localStorage.getItem(OUTBOX_KEY)).toBeNull();
    expect(screen.getByTestId('run-1')).toBeDisabled();
    expect(api.command).toHaveBeenCalledTimes(1);
  });

  it('sends the saved ball when the phone comes back online', async () => {
    const user = userEvent.setup();
    api.command.mockRejectedValueOnce(networkError()).mockResolvedValue(outcome(withInnings({ runs: 15 }, { seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('run-1'));
    await screen.findByTestId('outbox-banner');

    act(() => { window.dispatchEvent(new Event('online')); });

    await waitFor(() => expect(screen.queryByTestId('outbox-banner')).toBeNull());
    expect(api.command).toHaveBeenCalledTimes(2);
  });

  it('treats a server error like a dropped connection and keeps the ball', async () => {
    const user = userEvent.setup();
    api.command.mockRejectedValueOnce(serverError(503, 'INTERNAL_ERROR', 'down')).mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('run-1'));

    expect(await screen.findByTestId('outbox-banner')).toHaveTextContent('Waiting for the network');
    expect(localStorage.getItem(OUTBOX_KEY)).not.toBeNull();
  });

  it('resumes a ball saved before a reload, with its own id, and an answer that says it was already counted clears it', async () => {
    localStorage.setItem(OUTBOX_KEY, JSON.stringify({ kind: 'command', clientEventId: 'saved-id-1', expectedSeq: 11, command: { type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 4, runsType: 'BAT', boundary: true } }, label: 'FOUR', createdAt: '2026-11-20T10:00:00Z' }));
    api.command.mockResolvedValue(outcome(withInnings({ runs: 18 }, { seq: 12 }), true));
    renderPage();

    await waitFor(() => expect(api.command).toHaveBeenCalledTimes(1));
    expect(lastCommand()).toMatchObject({ clientEventId: 'saved-id-1', expectedSeq: 11 });
    await waitFor(() => expect(localStorage.getItem(OUTBOX_KEY)).toBeNull());
    expect(await screen.findByText('Lions 18/1')).toBeInTheDocument();
    expect(screen.queryByTestId('outbox-banner')).toBeNull();
  });

  it('lets the scorer discard a ball that never got through and then shows the real score', async () => {
    const user = userEvent.setup();
    api.command.mockRejectedValue(networkError());
    renderPage();
    await user.click(await screen.findByTestId('run-1'));
    await screen.findByTestId('outbox-banner');
    api.state.mockResolvedValue(withInnings({ runs: 20 }, { seq: 13 }));
    await user.click(screen.getByTestId('discard-outbox'));

    await waitFor(() => expect(localStorage.getItem(OUTBOX_KEY)).toBeNull());
    expect(await screen.findByText('Lions 20/1')).toBeInTheDocument();
  });

  // ---- another scorer ----------------------------------------------------------------------------------------------------

  it('shows a conflict instead of merging silently, and applies the ball on top with the same id when asked', async () => {
    const user = userEvent.setup();
    const conflict = conflictOf(14, { innings: [{ ...VIEW.innings[0], runs: 19 }] });
    api.command.mockRejectedValueOnce(serverError(409, 'SCORE_CONFLICT', 'stale', conflict)).mockResolvedValue(outcome(withInnings({ runs: 20 }, { seq: 15 })));
    renderPage();
    await user.click(await screen.findByTestId('run-1'));

    const sheet = await screen.findByTestId('conflict-sheet');
    expect(within(sheet).getByTestId('conflict-now')).toHaveTextContent('Lions 19/1 (1.4)');
    expect(within(sheet).getByTestId('conflict-event')).toHaveTextContent('2 runs (Sam Scorer)');
    expect(within(sheet).getByTestId('conflict-yours')).toHaveTextContent('1 run');
    expect(screen.queryByTestId('outbox-banner')).toBeNull();
    await user.click(within(sheet).getByTestId('conflict-apply'));

    await waitFor(() => expect(api.command).toHaveBeenCalledTimes(2));
    expect(api.command.mock.calls[1][1]).toMatchObject({ expectedSeq: 14, clientEventId: api.command.mock.calls[0][1].clientEventId });
    expect(await screen.findByText('Lions 20/1')).toBeInTheDocument();
    expect(localStorage.getItem(OUTBOX_KEY)).toBeNull();
  });

  it('lets the scorer discard their ball after a conflict and shows what the other scorer did', async () => {
    const user = userEvent.setup();
    api.command.mockRejectedValue(serverError(409, 'SCORE_CONFLICT', 'stale', conflictOf(14, { innings: [{ ...VIEW.innings[0], runs: 19 }] })));
    renderPage();
    await user.click(await screen.findByTestId('run-1'));
    await user.click(await screen.findByTestId('conflict-discard'));

    expect(await screen.findByText('Lions 19/1')).toBeInTheDocument();
    expect(screen.queryByTestId('conflict-sheet')).toBeNull();
    expect(localStorage.getItem(OUTBOX_KEY)).toBeNull();
    expect(api.command).toHaveBeenCalledTimes(1);
  });

  // ---- a rule, a lock ---------------------------------------------------------------------------------------------------

  it('shows the rule in plain words when the server refuses a ball and lets the scorer carry on', async () => {
    const user = userEvent.setup();
    api.command.mockRejectedValue(serverError(422, 'SCORING_REJECTED', 'x', { violation: 'DISMISSAL_NOT_ALLOWED_ON_FREE_HIT' }));
    renderPage();
    await user.click(await screen.findByTestId('btn-wicket'));
    await user.click(screen.getByTestId('how-BOWLED'));
    await user.click(screen.getByTestId('confirm-wicket'));

    expect(await screen.findByTestId('scoring-notice')).toHaveTextContent('That kind of dismissal cannot happen on a free hit.');
    expect(localStorage.getItem(OUTBOX_KEY)).toBeNull();
    expect(screen.getByTestId('run-1')).toBeEnabled();
  });

  it('says the match is locked and offers nothing to change', async () => {
    api.state.mockResolvedValue(view({
      matchStatus: 'COMPLETED', locked: true, result: { type: 'WON_BY_RUNS', winner: null, margin: 5, ballsRemaining: 0, text: 'Lions won by 5 runs' },
      canUndo: { allowed: false, describe: null, crossesInnings: false, blockedReason: 'LOCKED' },
    }));
    renderPage();

    expect(await screen.findByTestId('locked-banner')).toBeInTheDocument();
    expect(screen.getByTestId('result-text')).toHaveTextContent('Lions won by 5 runs');
    expect(screen.getByTestId('undo')).toBeDisabled();
    expect(screen.queryByTestId('time-left')).toBeNull();
  });

  // ---- undo -------------------------------------------------------------------------------------------------------------

  it('says what the undo will take off and sends it from the seq it last saw', async () => {
    const user = userEvent.setup();
    api.undo.mockResolvedValue(outcome(view({ seq: 13, canUndo: { allowed: true, describe: 'Undo: no run', crossesInnings: false, blockedReason: null } })));
    renderPage();
    const button = await screen.findByTestId('undo');
    expect(button).toHaveTextContent('Undo: 4 runs');
    await user.click(button);

    await waitFor(() => expect(api.undo).toHaveBeenCalledTimes(1));
    expect(api.undo.mock.calls[0][1]).toMatchObject({ expectedSeq: 12, mode: 'BALL' });
    expect(await screen.findByTestId('undo')).toHaveTextContent('Undo: no run');
  });

  it('asks first when the undo would reopen an innings or a finished match, and does nothing if the scorer keeps it', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(view({ matchStatus: 'COMPLETED', result: { type: 'WON_BY_WICKETS', winner: null, margin: 10, ballsRemaining: 0, text: 'Tigers won' }, canUndo: { allowed: true, describe: 'Undo: 3 runs', crossesInnings: true, blockedReason: null }, editableUntil: new Date(Date.now() + 23 * 3600_000 + 600_000).toISOString() }));
    api.undo.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    expect(await screen.findByTestId('time-left')).toHaveTextContent(/You can correct this match for 23 h/);
    await user.click(screen.getByTestId('undo'));

    expect(await screen.findByText(/reopens an innings or a finished match/i)).toBeInTheDocument();
    expect(api.undo).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Keep it' }));
    expect(api.undo).not.toHaveBeenCalled();
    await user.click(screen.getByTestId('undo'));
    await user.click(await screen.findByTestId('confirm-undo'));
    await waitFor(() => expect(api.undo).toHaveBeenCalledTimes(1));
  });

  // ---- the break, the second innings, the result ----------------------------------------------------------------------

  it('at the innings break shows the target and opens the second innings with the sides swapped', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(withInnings({ status: 'COMPLETE', endReason: 'OVERS_COMPLETE', runs: 22, wickets: 2, overs: '2.0' }, { matchStatus: 'INNINGS_BREAK', seq: 21 }));
    api.command.mockResolvedValue(outcome(view({ seq: 22 })));
    renderPage();

    expect(await screen.findByTestId('innings-break')).toHaveTextContent('Lions made 22/2 in 2.0 overs.');
    expect(screen.getByTestId('innings-break')).toHaveTextContent('Tigers need 23 to win.');
    expect(screen.getByText('Tigers bat, Lions bowl.')).toBeInTheDocument();
    await user.click(screen.getByTestId('start-innings-button'));

    await waitFor(() => expect(lastCommand()).toMatchObject({ expectedSeq: 21, command: { type: 'START_INNINGS', striker: 'b-1', nonStriker: 'b-2', bowler: 'a-1' } }));
  });

  it('shows the result and the scorecards of both innings', async () => {
    api.state.mockResolvedValue(view({
      matchStatus: 'COMPLETED', innings: [{ ...VIEW.innings[0], status: 'COMPLETE' }, { ...VIEW.innings[0], number: 2, battingTeam: { id: 'tb', name: 'Tigers' }, status: 'COMPLETE' }],
      result: { type: 'WON_BY_WICKETS', winner: { id: 'tb', name: 'Tigers' }, margin: 10, ballsRemaining: 0, text: 'Tigers won by 10 wickets (0 balls left)' },
    }));
    renderPage();

    expect(await screen.findByTestId('result-text')).toHaveTextContent('Tigers won by 10 wickets (0 balls left)');
    expect(screen.getAllByTestId('innings-card')).toHaveLength(2);
    expect(screen.queryByTestId('ball-pad')).toBeNull();
  });

  // ---- the rarer commands -------------------------------------------------------------------------------------------------

  it('retires a batter hurt, or out, from the more menu', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('more'));
    await user.click(screen.getByTestId('more-retire'));
    await user.click(within(await screen.findByTestId('chooser')).getByText('Bala'));
    await user.click(screen.getByTestId('retire-hurt'));

    await waitFor(() => expect(lastCommand().command).toEqual({ type: 'RETIRE_BATTER', batterId: 'a-2', retirement: 'HURT' }));
  });

  it('replaces an injured bowler mid-over with someone who may bowl, and a four run is not a boundary', async () => {
    const user = userEvent.setup();
    api.command.mockResolvedValue(outcome(view({ seq: 13 })));
    renderPage();
    await user.click(await screen.findByTestId('more'));
    await user.click(screen.getByTestId('more-bowler'));
    expect(screen.getAllByTestId('choice')).toHaveLength(2);
    await user.click(screen.getByText('Uday'));
    await waitFor(() => expect(lastCommand().command).toEqual({ type: 'CHANGE_BOWLER', bowlerId: 'b-2' }));

    await user.click(await screen.findByTestId('more'));
    await user.click(screen.getByTestId('more-ran-4'));
    await waitFor(() => expect(lastCommand().command).toMatchObject({ delivery: { runs: 4, boundary: false } }));
  });

  it('declares only after asking, calls the match off with a reason, and revises a target', async () => {
    const user = userEvent.setup();
    api.state.mockResolvedValue(withInnings({ target: 40, runsNeeded: 26 }));
    api.command.mockResolvedValue(outcome(withInnings({ target: 40, runsNeeded: 26 }, { seq: 13 })));
    renderPage();

    await user.click(await screen.findByTestId('more'));
    await user.click(screen.getByTestId('more-declare'));
    expect(api.command).not.toHaveBeenCalled();
    await user.click(screen.getByTestId('confirm-declare'));
    await waitFor(() => expect(lastCommand().command).toEqual({ type: 'END_INNINGS', reason: 'DECLARED' }));

    await user.click(await screen.findByTestId('more'));
    await user.click(screen.getByTestId('more-target'));
    const field = screen.getByLabelText('New target');
    await user.clear(field);
    await user.type(field, '28');
    await user.click(screen.getByTestId('confirm-target'));
    await waitFor(() => expect(lastCommand().command).toEqual({ type: 'REVISE_TARGET', target: 28 }));

    await user.click(await screen.findByTestId('more'));
    await user.click(screen.getByTestId('more-end'));
    await user.click(screen.getByTestId('confirm-abandon'));
    await waitFor(() => expect(lastCommand().command).toEqual({ type: 'END_MATCH', reason: 'ABANDONED' }));
  });

  // ---- access ---------------------------------------------------------------------------------------------------------------

  it('says only the creator or a scorer can score when the server says no', async () => {
    api.state.mockRejectedValue(serverError(403, 'NOT_ALLOWED', 'no'));
    renderPage();
    expect(await screen.findByText(/only the match creator or a scorer can score/i)).toBeInTheDocument();
  });

  it('says the server cannot be reached, and that a saved ball is safe, when the page is reloaded offline', async () => {
    localStorage.setItem(OUTBOX_KEY, JSON.stringify({ kind: 'undo', clientEventId: 'u-1', expectedSeq: 12, mode: 'BALL', label: 'Undo: 4 runs', createdAt: '2026-11-20T10:00:00Z' }));
    api.state.mockRejectedValue(networkError());
    renderPage();

    expect(await screen.findByText(/can't reach the server right now/i)).toBeInTheDocument();
    expect(screen.getByText(/Undo: 4 runs\) is saved on this phone/)).toBeInTheDocument();
    expect(api.undo).not.toHaveBeenCalled();
  });

  it('says it cannot find a match that is not yours', async () => {
    api.state.mockRejectedValue(serverError(404, 'MATCH_NOT_FOUND', 'no'));
    renderPage();
    expect(await screen.findByText(/couldn't find that match/i)).toBeInTheDocument();
  });

  it('the batters at the crease can be read from the fixtures', () => {
    expect([ARUN, BALA].map((p) => p.name)).toEqual(['Arun', 'Bala']);
  });
});
