import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import theme from '@/theme';
import { MemoryRouter } from 'react-router-dom';
import { ConfidenceMeter, CoverageBars, DataUnavailable, EmptyState, GroupedBars, Heatmap, KpiCard, PageHero, ProvenanceChip, RankBars, TimeSeriesChart, WorkflowSteps, compact, num, provenanceOf, signedPct } from '.';

const wrap = (ui: React.ReactNode) => render(<ThemeProvider theme={theme}><MemoryRouter>{ui}</MemoryRouter></ThemeProvider>);

describe('analytics formatting', () => {
  it('never turns a missing value into zero', () => {
    expect(num(null)).toBe('—');
    expect(num(undefined)).toBe('—');
    expect(num(Number.NaN)).toBe('—');
    expect(signedPct(null)).toBe('—');
    expect(signedPct(12.34)).toBe('+12.3%');
    expect(num(0)).toBe('0');
  });
  it('writes chart ticks without noisy decimals', () => {
    expect(compact(956.6)).toBe('957');
    expect(compact(1400)).toBe('1.4K');
    expect(compact(2.34)).toBe('2.3');
    expect(compact(120000)).toBe('1.2L');
  });
  it('maps persisted source values to a provenance kind and never guesses an unknown one', () => {
    expect(provenanceOf('POC_SYNTHETIC')).toBe('DEMO');
    expect(provenanceOf('DSPORTS_LIVE')).toBe('LIVE');
    expect(provenanceOf('PUBLIC_LISTING')).toBe('LISTING');
    expect(provenanceOf('ENTERED_QUOTATION')).toBe('QUOTE');
    expect(provenanceOf('AI_ESTIMATE')).toBe('ESTIMATE');
    expect(provenanceOf('something-new')).toBe('UNAVAILABLE');
    expect(provenanceOf(null)).toBe('UNAVAILABLE');
  });
});

describe('provenance, confidence and states', () => {
  it('labels demonstration data so it cannot pass for live data', () => {
    wrap(<><ProvenanceChip kind="DEMO" /><ProvenanceChip kind="LIVE" /></>);
    expect(screen.getByTestId('prov-DEMO')).toHaveTextContent('Demonstration');
    expect(screen.getByTestId('prov-LIVE')).toHaveTextContent('Live');
  });
  it('shows confidence as text as well as colour', () => {
    wrap(<ConfidenceMeter level="LOW" reason="short history" />);
    expect(screen.getByTestId('confidence')).toHaveAttribute('data-level', 'LOW');
    expect(screen.getByTestId('confidence')).toHaveTextContent('Low');
  });
  it('explains an unavailable figure and what would enable it', () => {
    wrap(<DataUnavailable what="Forecast variance" why="No week has closed." needs="Wait for a week to close." />);
    expect(screen.getByTestId('unavailable')).toHaveTextContent('Forecast variance is not available');
    expect(screen.getByTestId('unavailable')).toHaveTextContent('To enable it: Wait for a week to close.');
    wrap(<EmptyState title="Nothing here" />);
    expect(screen.getByTestId('empty')).toHaveTextContent('Nothing here');
  });
});

describe('KpiCard', () => {
  it('shows a value, its delta and its provenance, and a drill-down by keyboard', () => {
    const open = vi.fn();
    wrap(<KpiCard testId="k" label="Actual" value="1,200" delta="+8%" deltaTone="good" provenance="LIVE" onClick={open} />);
    const card = screen.getByTestId('k');
    expect(card).toHaveTextContent('1,200');
    expect(card).toHaveTextContent('+8%');
    expect(card).toHaveTextContent('Live');
    fireEvent.keyDown(card, { key: 'Enter' });
    expect(open).toHaveBeenCalledTimes(1);
  });
  it('replaces the value with the reason when it cannot be computed', () => {
    wrap(<KpiCard testId="k" label="Margin" value="52%" unavailable="No supplier quotation yet" />);
    expect(screen.getByTestId('k')).not.toHaveTextContent('52%');
    expect(screen.getByTestId('k')).toHaveTextContent('No supplier quotation yet');
  });
});

describe('TimeSeriesChart', () => {
  const series = [{ key: 'f', name: 'Forecast', color: '#00f' }, { key: 'a', name: 'Actual', color: '#0a0' }];
  it('draws a gap, not a zero, where a value is missing', () => {
    const points = [1, 2, 3, 4, 5, 6].map((i) => ({ x: `2026-10-0${i}`, values: { f: 10 + i, a: i === 3 || i === 4 ? null : 8 + i } }));
    wrap(<TimeSeriesChart points={points} series={series} testId="ts" />);
    expect(screen.getAllByTestId('ts-line-a')).toHaveLength(2);          // two segments: the missing weeks split the line, no zero is drawn
    expect(screen.getAllByTestId('ts-line-f')).toHaveLength(1);
  });
  it('lists exact values on hover and says "no value" for a missing one', () => {
    const points = [1, 2, 3].map((i) => ({ x: `W${i}`, values: { f: 10 * i, a: i === 2 ? null : 5 * i } }));
    wrap(<TimeSeriesChart points={points} series={series} testId="ts" />);
    const svg = screen.getByRole('img', { name: 'Time series' });
    svg.getBoundingClientRect = () => ({ left: 0, width: 860, top: 0, height: 260, right: 860, bottom: 260, x: 0, y: 0, toJSON: () => ({}) });
    fireEvent.mouseMove(svg, { clientX: 430, clientY: 10 });
    expect(screen.getByTestId('ts-hover')).toHaveTextContent('W2');
    expect(screen.getByTestId('ts-hover')).toHaveTextContent('Actual no value');
  });
  it('says so instead of drawing an empty chart', () => {
    wrap(<TimeSeriesChart points={[{ x: 'a', values: { f: null } }]} series={series} testId="ts" />);
    expect(screen.getByTestId('ts-empty')).toBeInTheDocument();
  });
  it('draws a prediction band behind its series', () => {
    const points = [1, 2, 3].map((i) => ({ x: `W${i}`, values: { f: 10 }, band: { f: [8, 12] as [number, number] } }));
    wrap(<TimeSeriesChart points={points} series={[series[0]]} testId="ts" />);
    expect(screen.getByTestId('ts-band')).toBeInTheDocument();
  });
});

describe('Heatmap, bars and coverage', () => {
  it('hatches cells with no data and drills down on click', () => {
    const open = vi.fn();
    wrap(<Heatmap rows={['N', 'S']} cols={['Cricket', 'Badminton']} cell={(r, c) => ({ value: r === 'S' && c === 'Badminton' ? null : 10 })} onCell={open} />);
    const cells = screen.getAllByTestId('heat-cell');
    expect(cells).toHaveLength(4);
    expect(cells.filter((c) => c.getAttribute('data-empty') === '1')).toHaveLength(1);
    fireEvent.click(cells[0]);
    expect(open).toHaveBeenCalledWith('N', 'Cricket');
  });
  it('says no cell has data rather than drawing an empty grid', () => {
    wrap(<Heatmap rows={['N']} cols={['A']} cell={() => ({ value: null })} testId="hm" />);
    expect(screen.getByTestId('hm-empty')).toBeInTheDocument();
  });
  it('ranks variances around a zero axis and shows "no data" for a missing value', () => {
    const open = vi.fn();
    wrap(<RankBars onItem={open} items={[{ label: 'Store A', value: -40 }, { label: 'Store B', value: 25 }, { label: 'Store C', value: null }]} />);
    const rows = screen.getAllByTestId('rank-row');
    expect(rows[0]).toHaveTextContent('-40');
    expect(rows[1]).toHaveTextContent('+25');
    expect(rows[2]).toHaveTextContent('no data');
    fireEvent.click(rows[0]);
    expect(open).toHaveBeenCalledWith(expect.objectContaining({ label: 'Store A' }));
  });
  it('compares forecast, target and actual per group and leaves a missing bar empty', () => {
    wrap(<GroupedBars groups={[{ label: 'Bat', values: { f: 10, t: 12, a: null } }]} series={[{ key: 'f', name: 'Forecast', color: '#00f' }, { key: 't', name: 'Target', color: '#f80' }, { key: 'a', name: 'Actual', color: '#0a0' }]} />);
    expect(screen.getByText('Bat')).toBeInTheDocument();
    expect(screen.getByText('Actual')).toBeInTheDocument();
  });
  it('marks shortage and excess thresholds on days of cover', () => {
    wrap(<CoverageBars low={14} high={90} items={[{ label: 'Bat · S1', days: 1 }, { label: 'Ball · S2', days: 120 }, { label: 'Pad · S3', days: null }]} />);
    expect(screen.getByTestId('coverage')).toHaveTextContent('1 day');
    expect(screen.getByTestId('coverage')).toHaveTextContent('120 days');
    expect(screen.getByTestId('coverage')).toHaveTextContent('no forecast');
    expect(screen.getByTestId('coverage')).toHaveTextContent('14 days (shortage below)');
  });
});

describe('page shell', () => {
  it('shows the tabs with a count badge and switches tab', () => {
    const go = vi.fn();
    wrap(<PageHero overline="INTERNAL" title="Planning" tab="a" onTab={go} tabs={[{ key: 'a', label: 'Dashboard' }, { key: 'b', label: 'Review queue', badge: 3 }]} homeTestId="home" />);
    expect(screen.getByText('Planning')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Review queue/ })).toHaveTextContent('3');
    fireEvent.click(screen.getByRole('tab', { name: /Review queue/ }));
    expect(go).toHaveBeenCalledWith('b');
    expect(screen.getByTestId('home')).toHaveAttribute('href', '/');
  });
  it('marks finished, current and upcoming workflow steps', () => {
    wrap(<WorkflowSteps steps={['Generate', 'Adjust', 'Approve']} current={1} />);
    const chips = screen.getByTestId('workflow').querySelectorAll('[data-state]');
    expect([...chips].map((c) => c.getAttribute('data-state'))).toEqual(['done', 'current', 'todo']);
  });
});
