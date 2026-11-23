import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { cricketPublicApi } from '@/api/cricketPublic';
import { AnalyticsPanel, Commentary, Manhattan, Partnerships, Worm } from './AnalyticsPanel';
import { ANALYTICS } from './analyticsFixtures';

vi.mock('@/api/cricketPublic', async (original) => ({ ...(await original<typeof import('@/api/cricketPublic')>()), cricketPublicApi: { scorecard: vi.fn(), live: vi.fn(), analytics: vi.fn() } }));
const analytics = vi.mocked(cricketPublicApi.analytics);

beforeEach(() => {
  analytics.mockReset();
});

describe('the charts', () => {
  it('draws one bar per over with the runs it holds, a dot for each wicket, and says the same in words', () => {
    render(<Manhattan innings={ANALYTICS.innings[0]} />);
    const bars = screen.getAllByTestId('over-bar');
    expect(bars.map((b) => [b.getAttribute('data-over'), b.getAttribute('data-runs'), b.getAttribute('data-wickets')])).toEqual([['1', '11', '2'], ['2', '11', '0']]);
    expect(bars[0].querySelectorAll('circle')).toHaveLength(2);
    expect(bars[1].querySelectorAll('circle')).toHaveLength(0);
    expect(screen.getByRole('img')).toHaveAttribute('aria-label', 'Runs in each over of the Lions innings: over 1 11 (2 wickets), over 2 11.');
  });

  it('draws a line for each innings, marks the wickets, and the target of the chase', () => {
    render(<Worm innings={ANALYTICS.innings} />);
    const lines = screen.getAllByTestId('worm-line');
    expect(lines.map((l) => l.getAttribute('data-team'))).toEqual(['Lions', 'Tigers']);
    expect(lines[0].querySelectorAll('circle')).toHaveLength(1);
    expect(lines[1].querySelectorAll('line')).toHaveLength(1);
    expect(screen.getByRole('img').getAttribute('aria-label')).toContain('Lions: 0/0 after 0 overs, 11/2 after 1 over, 22/2 after 2 overs');
  });

  it('copes with an innings with no wicket and no over yet', () => {
    const empty = { ...ANALYTICS.innings[0], overRuns: [], worm: [{ over: 0, legalBalls: 0, runs: 0, wickets: 0 }], partnerships: [] };
    const { unmount } = render(<Manhattan innings={empty} />);
    expect(screen.queryAllByTestId('over-bar')).toHaveLength(0);
    unmount();
    render(<Partnerships innings={empty} />);
    expect(screen.getByText('No wicket has fallen yet.')).toBeInTheDocument();
  });

  it('lists the partnerships with who batted together and the runs and balls', () => {
    render(<Partnerships innings={ANALYTICS.innings[0]} />);
    const rows = screen.getAllByTestId('partnership').map((r) => r.textContent);
    expect(rows).toEqual(['1st wicket: Arun and Bala11 (4)', '2nd wicket: Chetan and Bala0 (1)', 'Unbroken: Bala and Vik11 (7)']);
  });

  it('shows the newest ball first, marks wickets and boundaries, and offers all of them when there are many', async () => {
    const user = userEvent.setup();
    const first = render(<Commentary lines={ANALYTICS.commentary} inningsNames={{ 1: 'Lions', 2: 'Tigers' }} />);
    const lines = within(first.container).getAllByTestId('commentary-line');
    expect(lines[0]).toHaveTextContent('Tarun to Arun: SIX');
    expect(lines.filter((l) => l.getAttribute('data-wicket') === 'true').map((l) => l.textContent)).toEqual(['0.5Tarun to Arun: 1 run. OUT! Arun, run out (Wasim) (11/1)']);
    expect(lines.filter((l) => l.getAttribute('data-boundary') === 'true')).toHaveLength(2);
    first.unmount();
    const many = Array.from({ length: 30 }, (_, i) => ({ innings: 1, seq: i + 2, over: `0.${(i % 6) + 1}`, text: `ball ${i + 1}`, runs: 0, wicket: false, boundary: false }));
    render(<Commentary lines={many} inningsNames={{ 1: 'Lions' }} />);
    expect(screen.getAllByTestId('commentary-line')).toHaveLength(12);
    expect(screen.getAllByTestId('commentary-line')[0]).toHaveTextContent('ball 30');
    await user.click(screen.getByTestId('commentary-all'));
    expect(screen.getAllByTestId('commentary-line')).toHaveLength(30);
  });
});

describe('the panel', () => {
  it('asks for the analytics once per seq and shows the tabs, switching between them and between innings', async () => {
    const user = userEvent.setup();
    analytics.mockResolvedValue(ANALYTICS);
    const { rerender } = render(<AnalyticsPanel matchId="m-1" seq={36} />);
    expect(await screen.findByTestId('analytics-panel')).toBeInTheDocument();
    expect(analytics).toHaveBeenCalledTimes(1);
    expect(screen.getAllByTestId('commentary-line')).toHaveLength(5);
    await user.click(screen.getByTestId('tab-overs'));
    expect(screen.getByTestId('manhattan')).toBeInTheDocument();
    await user.click(screen.getByRole('tab', { name: 'Tigers' }));
    expect(screen.getAllByTestId('over-bar').map((b) => b.getAttribute('data-runs'))).toEqual(['17', '6']);
    await user.click(screen.getByTestId('tab-worm'));
    expect(screen.getByTestId('worm')).toBeInTheDocument();
    await user.click(screen.getByTestId('tab-partnerships'));
    expect(screen.getAllByTestId('partnership')).toHaveLength(1);
    rerender(<AnalyticsPanel matchId="m-1" seq={36} />);
    expect(analytics).toHaveBeenCalledTimes(1);
    rerender(<AnalyticsPanel matchId="m-1" seq={37} />);
    await screen.findByTestId('analytics-panel');
    expect(analytics).toHaveBeenCalledTimes(2);
  });

  it('says so when the charts cannot be loaded, and keeps the last ones when a later refresh fails', async () => {
    analytics.mockRejectedValueOnce(new Error('down'));
    const first = render(<AnalyticsPanel matchId="m-1" seq={1} />);
    expect(await screen.findByTestId('analytics-failing')).toHaveTextContent('The scorecard above is not affected');
    first.unmount();
    analytics.mockResolvedValueOnce(ANALYTICS).mockRejectedValueOnce(new Error('down'));
    const { rerender } = render(<AnalyticsPanel matchId="m-1" seq={1} />);
    await screen.findByTestId('analytics-panel');
    rerender(<AnalyticsPanel matchId="m-1" seq={2} />);
    expect(await screen.findByText('Showing the last charts that arrived.')).toBeInTheDocument();
  });

  it('shows nothing before the first ball and never asks before the scorecard has a seq', async () => {
    analytics.mockResolvedValue({ seq: 1, innings: [], commentary: [] });
    const { container } = render(<AnalyticsPanel matchId="m-1" seq={1} />);
    await vi.waitFor(() => expect(analytics).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
    analytics.mockClear();
    render(<AnalyticsPanel matchId="m-1" seq={null} />);
    expect(analytics).not.toHaveBeenCalled();
  });
});
