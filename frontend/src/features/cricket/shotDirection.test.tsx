import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { shotFromTap, toView, FIELD_RADIUS } from './shotGeometry';
import { isBatRunBall, ShotPicker } from './scoring/ShotPicker';
import { readShotSetting, writeShotSetting } from './scoring/shotSetting';
import { WagonWheel } from './public/WagonWheel';
import { AnalyticsPanel } from './public/AnalyticsPanel';
import { ANALYTICS } from './public/analyticsFixtures';
import { cricketPublicApi } from '@/api/cricketPublic';
import type { ShotMark } from '@/types/cricketPublic';

vi.mock('@/api/cricketPublic', async (original) => ({ ...(await original<typeof import('@/api/cricketPublic')>()), cricketPublicApi: { scorecard: vi.fn(), live: vi.fn(), analytics: vi.fn(), playerStats: vi.fn() } }));

const mark = (seq: number, batter: string, runs: number, boundary: boolean, x: number, y: number): ShotMark => ({ innings: 1, seq, over: '0.1', batter, runs, boundary, x, y });

const shots = [mark(3, 'Arun', 1, false, 300, -200), mark(4, 'Arun', 4, true, -900, 100), mark(9, 'Bala', 6, true, 0, 950)];

describe('the geometry', () => {
  it('turns the middle of the drawing into the middle of the pitch and the edges into the rope', () => {
    expect(shotFromTap(140, 140, 280)).toEqual({ x: 0, y: 0 });
    expect(shotFromTap(280, 140, 280)).toEqual({ x: 1000, y: 0 });
    expect(shotFromTap(140, 0, 280)).toEqual({ x: 0, y: 1000 });
    expect(shotFromTap(0, 140, 280)).toEqual({ x: -1000, y: 0 });
  });

  it('pulls a tap in the corner of the square, outside the field, onto the rope and never produces a point the server would refuse', () => {
    for (const [px, py] of [[0, 0], [280, 280], [280, 0], [0, 280], [-50, 400], [1e9, 1e9]]) {
      const shot = shotFromTap(px, py, 280);
      expect(shot.x * shot.x + shot.y * shot.y).toBeLessThanOrEqual(FIELD_RADIUS * FIELD_RADIUS);
    }
    const corner = shotFromTap(280, 280, 280);
    expect(corner.x).toBeGreaterThan(0);
    expect(corner.y).toBeLessThan(0);
  });

  it('draws up as up: a positive y is above the middle of the SVG', () => {
    expect(toView({ x: 0, y: 1000 })).toEqual({ cx: 0, cy: -100 });
    expect(toView({ x: 500, y: 0 })).toEqual({ cx: 50, cy: -0 });
  });
});

describe('which balls can be marked', () => {
  it('is only a ball that scored off the bat', () => {
    expect(isBatRunBall({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 1 } })).toBe(true);
    expect(isBatRunBall({ type: 'DELIVER', delivery: { kind: 'NO_BALL', runs: 4, runsType: 'BAT' } })).toBe(true);
    expect(isBatRunBall({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 0 } })).toBe(false);
    expect(isBatRunBall({ type: 'DELIVER', delivery: { kind: 'WIDE', runs: 1 } })).toBe(false);
    expect(isBatRunBall({ type: 'DELIVER', delivery: { kind: 'NORMAL', runs: 2, runsType: 'BYE' } })).toBe(false);
    expect(isBatRunBall({ type: 'SELECT_BATTER' })).toBe(false);
  });

  it('keeps the setting off until it is turned on, and survives storage being unavailable', () => {
    window.localStorage.clear();
    expect(readShotSetting()).toBe(false);
    writeShotSetting(true);
    expect(readShotSetting()).toBe(true);
    writeShotSetting(false);
    expect(readShotSetting()).toBe(false);
    const broken = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(readShotSetting()).toBe(false);
    broken.mockRestore();
  });
});

describe('the picker', () => {
  it('reports the tapped point, and skipping records nothing', async () => {
    const user = userEvent.setup();
    const onPick = vi.fn();
    const onSkip = vi.fn();
    render(<ShotPicker open runs={4} onPick={onPick} onSkip={onSkip} />);
    expect(screen.getByText('Where did it go? (4 runs)')).toBeInTheDocument();
    await user.click(screen.getByTestId('shot-field'));
    expect(onPick).toHaveBeenCalledTimes(1);
    expect(onPick.mock.calls[0][0]).toEqual(expect.objectContaining({ x: expect.any(Number), y: expect.any(Number) }));
    await user.click(screen.getByTestId('shot-skip'));
    expect(onSkip).toHaveBeenCalledTimes(1);
  });
});

describe('the wagon wheel', () => {
  it('draws one line for each marked ball, thick for boundaries, and says the same in words', () => {
    render(<WagonWheel shots={shots} />);
    expect(screen.getAllByTestId('wheel-line')).toHaveLength(3);
    expect(screen.getByRole('img').getAttribute('aria-label')).toBe('Wagon wheel: 3 shots marked, 1 fours and 1 sixes.');
    const six = screen.getAllByTestId('wheel-line').find((l) => l.getAttribute('data-runs') === '6')!;
    expect(six.getAttribute('stroke-dasharray')).toBeTruthy();
    expect(screen.getAllByTestId('wheel-line').find((l) => l.getAttribute('data-runs') === '1')!.getAttribute('stroke-width')).toBe('1.5');
  });

  it('can be narrowed to one batter', async () => {
    const user = userEvent.setup();
    render(<WagonWheel shots={shots} />);
    await user.click(within(screen.getByTestId('wheel-batter')).getByRole('combobox'));
    await user.click(await screen.findByRole('option', { name: 'Bala' }));
    expect(screen.getAllByTestId('wheel-line')).toHaveLength(1);
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('for Bala');
  });
});

describe('the panel', () => {
  beforeEach(() => vi.mocked(cricketPublicApi.analytics).mockReset());

  it('offers the wagon wheel tab only when at least one shot was marked', async () => {
    const user = userEvent.setup();
    vi.mocked(cricketPublicApi.analytics).mockResolvedValue(ANALYTICS);
    const without = render(<AnalyticsPanel matchId="m-1" seq={1} />);
    await screen.findByTestId('analytics-panel');
    expect(screen.queryByTestId('tab-wheel')).not.toBeInTheDocument();
    without.unmount();
    vi.mocked(cricketPublicApi.analytics).mockResolvedValue({ ...ANALYTICS, shots });
    render(<AnalyticsPanel matchId="m-1" seq={2} />);
    await user.click(await screen.findByTestId('tab-wheel'));
    expect(screen.getByTestId('wagon-wheel')).toBeInTheDocument();
  });
});
