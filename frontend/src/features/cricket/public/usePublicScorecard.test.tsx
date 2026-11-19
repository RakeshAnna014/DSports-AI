import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { AxiosError, type AxiosResponse } from 'axios';
import { cricketPublicApi } from '@/api/cricketPublic';
import { FINISHED_CARD, LIVE_CARD } from './publicFixtures';
import { NOT_STARTED_POLL_MS, POLL_MS, usePublicScorecard } from './usePublicScorecard';

vi.mock('@/api/cricketPublic', async (original) => ({ ...(await original<typeof import('@/api/cricketPublic')>()), cricketPublicApi: { scorecard: vi.fn(), live: vi.fn() } }));
const scorecard = vi.mocked(cricketPublicApi.scorecard);

const httpError = (status: number) => new AxiosError('failed', 'ERR', undefined, undefined, { status, data: {} } as AxiosResponse);
const changed = (data = LIVE_CARD, etag = `"${data.seq}"`) => ({ kind: 'changed' as const, data, etag });
const unchanged = { kind: 'unchanged' as const };

const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  act(() => { document.dispatchEvent(new Event('visibilitychange')); });
};
const advance = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] });
  scorecard.mockReset();
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
});

afterEach(() => {
  vi.useRealTimers();
});

const start = async () => {
  const hook = renderHook(() => usePublicScorecard('m-1'));
  await advance(0);
  return hook;
};

describe('usePublicScorecard', () => {
  it('asks at once without an ETag, then sends the ETag it was given on every poll and keeps the card when the answer is 304', async () => {
    scorecard.mockResolvedValueOnce(changed()).mockResolvedValue(unchanged);
    const { result } = await start();

    expect(scorecard).toHaveBeenNthCalledWith(1, 'm-1', null);
    expect(result.current.card).toEqual(LIVE_CARD);
    expect(result.current.loading).toBe(false);

    await advance(POLL_MS);
    await advance(POLL_MS);
    expect(scorecard).toHaveBeenCalledTimes(3);
    expect(scorecard).toHaveBeenNthCalledWith(2, 'm-1', '"25"');
    expect(scorecard).toHaveBeenNthCalledWith(3, 'm-1', '"25"');
    expect(result.current.card).toEqual(LIVE_CARD);
  });

  it('shows a change and then asks with the new ETag', async () => {
    const next = { ...LIVE_CARD, seq: 26 };
    scorecard.mockResolvedValueOnce(changed()).mockResolvedValueOnce(changed(next)).mockResolvedValue(unchanged);
    const { result } = await start();

    await advance(POLL_MS);
    expect(result.current.card?.seq).toBe(26);
    await advance(POLL_MS);
    expect(scorecard).toHaveBeenLastCalledWith('m-1', '"26"');
  });

  it('does not ask while the tab is hidden, and asks at once when it is visible again', async () => {
    scorecard.mockResolvedValue(unchanged).mockResolvedValueOnce(changed());
    await start();
    expect(scorecard).toHaveBeenCalledTimes(1);

    setVisibility('hidden');
    await advance(POLL_MS * 5);
    expect(scorecard).toHaveBeenCalledTimes(1);

    setVisibility('visible');
    await advance(0);
    expect(scorecard).toHaveBeenCalledTimes(2);
    await advance(POLL_MS);
    expect(scorecard).toHaveBeenCalledTimes(3);
  });

  it('a tab opened in the background asks only when it first becomes visible', async () => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    scorecard.mockResolvedValue(changed());
    const { result } = await start();
    await advance(POLL_MS * 3);
    expect(scorecard).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(true);

    setVisibility('visible');
    await advance(0);
    expect(scorecard).toHaveBeenCalledTimes(1);
    expect(result.current.card).toEqual(LIVE_CARD);
  });

  it('stops asking when the match is over, and a manual check starts again if it turns out to be live', async () => {
    scorecard.mockResolvedValueOnce(changed()).mockResolvedValueOnce(changed(FINISHED_CARD)).mockResolvedValue(unchanged);
    const { result } = await start();
    await advance(POLL_MS);

    expect(result.current.finished).toBe(true);
    expect(scorecard).toHaveBeenCalledTimes(2);
    await advance(POLL_MS * 10);
    expect(scorecard).toHaveBeenCalledTimes(2);

    scorecard.mockResolvedValueOnce(changed({ ...LIVE_CARD, seq: 37 })).mockResolvedValue(unchanged);
    act(() => result.current.refresh());
    await advance(0);
    expect(scorecard).toHaveBeenCalledTimes(3);
    expect(result.current.finished).toBe(false);
    await advance(POLL_MS);
    expect(scorecard).toHaveBeenCalledTimes(4);
  });

  it('says not found for a match that has not started and keeps checking, slowly, until it has', async () => {
    scorecard.mockRejectedValueOnce(httpError(404)).mockResolvedValue(changed());
    const { result } = await start();

    expect(result.current.notFound).toBe(true);
    expect(result.current.card).toBeNull();
    await advance(POLL_MS);
    expect(scorecard).toHaveBeenCalledTimes(1);
    await advance(NOT_STARTED_POLL_MS - POLL_MS);
    expect(scorecard).toHaveBeenCalledTimes(2);
    expect(result.current.notFound).toBe(false);
    expect(result.current.card).toEqual(LIVE_CARD);
  });

  it('keeps the last score when the server fails, backs off, and recovers by itself', async () => {
    scorecard.mockResolvedValueOnce(changed()).mockRejectedValueOnce(httpError(503)).mockRejectedValueOnce(httpError(503)).mockResolvedValue(unchanged);
    const { result } = await start();

    await advance(POLL_MS);
    expect(result.current.failing).toBe(true);
    expect(result.current.card).toEqual(LIVE_CARD);

    await advance(POLL_MS);
    expect(scorecard, 'the next try waits longer after a failure').toHaveBeenCalledTimes(2);
    await advance(POLL_MS * 3);
    expect(scorecard).toHaveBeenCalledTimes(3);
    await advance(POLL_MS * 4);
    expect(result.current.failing).toBe(false);
    expect(result.current.card).toEqual(LIVE_CARD);
  });

  it('asks again the moment the network comes back', async () => {
    scorecard.mockResolvedValueOnce(changed()).mockRejectedValueOnce(new AxiosError('offline')).mockResolvedValue(unchanged);
    const { result } = await start();
    await advance(POLL_MS);
    expect(result.current.failing).toBe(true);

    act(() => { window.dispatchEvent(new Event('online')); });
    await advance(0);
    expect(scorecard).toHaveBeenCalledTimes(3);
    expect(result.current.failing).toBe(false);
  });

  it('stops everything when the page is left', async () => {
    scorecard.mockResolvedValue(unchanged).mockResolvedValueOnce(changed());
    const { unmount } = await start();
    unmount();

    await advance(POLL_MS * 5);
    expect(scorecard).toHaveBeenCalledTimes(1);
  });
});
