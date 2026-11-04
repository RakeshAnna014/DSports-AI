import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { MemoryRouter } from 'react-router-dom';
import theme from '@/theme';
import type { CalcResult, Compare, Dashboard, ScoreResult } from '@/api/sourcing';
import { CostBreakdown, OfferChart, PipelineFunnel, ProductWorkflow, ScoreBars, SourceDiagnostics } from './visuals';
import { StockBasisNotice } from '@/features/inventory/ui';

const wrap = (ui: React.ReactNode) => render(<ThemeProvider theme={theme}><MemoryRouter>{ui}</MemoryRouter></ThemeProvider>);

const DASH = (over: Partial<Dashboard> = {}): Dashboard => ({
  as_of: '2026-10-16T05:00:00Z', scope: 'live', total_products: 2, by_status: { DISCOVERED: 5, SHORTLISTED: 1 }, awaiting_review: 5, shortlisted: 1, approved_opportunity: 0, approved_for_onboarding: 0, with_supplier_quotes: 0, with_comparable_offers: 0,
  margin_at_least_25_pct: 0, blocked_by_missing_information: 2, with_product_image: 1, with_exact_model_image: 1, demand_gaps: { total: 0, by_status: {} }, by_provenance: {}, excluded: { fixtures: 0, unknown_provenance: 0 }, recent_runs: [],
  demand_signals_in_window: 0, window_days: 30, notes: [], sources: { external_configured: false, external_feeds: [], configuration_problems: [], fx_currencies: [], base_currency: 'INR' }, ...over,
} as Dashboard);

describe('Product Intelligence visuals', () => {
  it('breaks the landed cost into its components with amounts from the calculator', () => {
    const drivers: CalcResult['cost_drivers'] = [{ component: 'Goods', amount: '800', share_pct: '80' }, { component: 'Freight', amount: '150', share_pct: '15' }, { component: 'Duty', amount: '50', share_pct: '5' }, { component: 'Nothing', amount: '0', share_pct: '0' }];
    wrap(<CostBreakdown drivers={drivers} />);
    const box = screen.getByTestId('cost-breakdown');
    expect(box).toHaveTextContent('Goods 80%');
    expect(box).toHaveTextContent('Freight 15%');
    expect(box).not.toHaveTextContent('Nothing');                  // a zero component is not drawn
  });

  it('shows the opportunity score as its dimensions with weights and says what was left out for lack of data', () => {
    const score = { dimensions: [{ dimension: 'CUSTOMER_DEMAND', score: 80, weight: 0.3, why: '35 searches' }, { dimension: 'MARGIN', score: 40, weight: 0.2, why: 'estimated margin' }], missing: ['SEASONALITY'] } as unknown as ScoreResult;
    wrap(<ScoreBars score={score} />);
    expect(screen.getByTestId('score-bars')).toHaveTextContent('Customer demand · weight 30%');
    expect(screen.getByTestId('score-bars')).toHaveTextContent('80/100');
    expect(screen.getByTestId('score-bars')).toHaveTextContent('Not scored for lack of data: Seasonality');
  });

  const offer = (id: string, supplier: string, landed: string | null, kind: 'QUOTE_ENTERED' | 'PUBLIC_LISTING', rank: number | null) => ({
    quote: { id, supplier_name: supplier, label: kind === 'PUBLIC_LISTING' ? 'Public listing' : 'Quoted', source_kind: kind, currency: 'INR', unit_price: 100, status: 'ACTIVE' },
    calculation: landed ? { status: 'OK', values: { landed_cost_per_unit: landed }, missing: [], issues: [] } : { status: 'INCOMPLETE', values: {}, missing: ['freight_total'], issues: [] }, warnings: [], rank_by_landed_cost: rank,
  });
  it('compares landed cost per unit across offers, marks a public listing as a listing, and never draws an offer that cannot be calculated', () => {
    const offers = [offer('1', 'Acme', '120.5', 'QUOTE_ENTERED', 1), offer('2', 'Beta', '140', 'PUBLIC_LISTING', 2), offer('3', 'Gamma', null, 'PUBLIC_LISTING', null)] as unknown as Compare['offers'];
    wrap(<OfferChart offers={offers} />);
    const chart = screen.getByTestId('offer-chart');
    expect(chart).toHaveTextContent('Acme');
    expect(chart).toHaveTextContent('lowest landed cost');
    expect(chart).toHaveTextContent('no data');
    expect(chart).toHaveTextContent('cannot calculate: freight_total');
    expect(screen.getByTestId('prov-QUOTE')).toBeInTheDocument();
    expect(screen.getByTestId('prov-LISTING')).toBeInTheDocument();
  });
  it('draws no comparison for a single offer', () => {
    wrap(<OfferChart offers={[offer('1', 'Acme', '120', 'QUOTE_ENTERED', 1)] as unknown as Compare['offers']} />);
    expect(screen.queryByTestId('offer-chart')).toBeNull();
  });

  it('shows the pipeline by status and keeps customer searches out of it', () => {
    wrap(<PipelineFunnel byStatus={{ DISCOVERED: 5, SHORTLISTED: 1 }} />);
    const f = screen.getByTestId('src-pipeline');
    expect(f).toHaveTextContent('Discovered');
    expect(f).toHaveTextContent('demand gap, not a product');
  });

  it('says exactly what to configure when no external source exists', () => {
    wrap(<SourceDiagnostics d={DASH()} onRuns={() => undefined} />);
    expect(screen.getByTestId('src-diag-missing')).toHaveTextContent('No external source is configured');
    expect(screen.getByTestId('src-diag-missing')).toHaveTextContent('SOURCING_PROVIDERS');
    expect(screen.getByTestId('src-diag-missing')).toHaveTextContent('SOURCING_ALLOWED_HOSTS');
  });
  it('calls a source connected only when a run stored records from it, and shows the reason otherwise', () => {
    const run = { id: 'r', started_at: '2026-10-16T05:00:00Z', status: 'SUCCEEDED', params: {}, stats: { created: 0, refreshed: 0 }, sources: [{ name: 'victor-sport', status: 'ok', records: 0, reason: 'No product in the sitemap matched the requested keywords.' }] };
    const base = { sources: { external_configured: true, external_feeds: [{ name: 'victor-sport' }], configuration_problems: [], fx_currencies: [], base_currency: 'INR' }, recent_runs: [run] } as Partial<Dashboard>;
    const { unmount } = wrap(<SourceDiagnostics d={DASH(base)} onRuns={() => undefined} />);
    expect(screen.getByTestId('src-diag-source')).toHaveTextContent('last run: Ok');
    expect(screen.getByTestId('src-diag-source')).not.toHaveTextContent('connected');
    expect(screen.getByTestId('src-diag-source')).toHaveTextContent('No product in the sitemap');
    unmount();
    wrap(<SourceDiagnostics d={DASH({ ...base, recent_runs: [{ ...run, sources: [{ ...run.sources[0], records: 12, reason: null }] }] } as Partial<Dashboard>)} onRuns={() => undefined} />);
    expect(screen.getByTestId('src-diag-source')).toHaveTextContent('connected: 12 record(s) stored');
  });

  it('shows where a product is in the buyer sequence', () => {
    wrap(<ProductWorkflow status="QUOTE_COMPARISON" />);
    const states = [...screen.getByTestId('opp-workflow').querySelectorAll('[data-state]')].map((c) => c.getAttribute('data-state'));
    expect(states).toEqual(['done', 'done', 'done', 'current', 'todo', 'todo', 'todo']);
  });
});

describe('Inventory AI stock basis', () => {
  it('says the stock is demonstration data held per product and region, never split across stores, and links to store-level planning', () => {
    wrap(<StockBasisNotice />);
    const n = screen.getByTestId('stock-basis');
    expect(n).toHaveTextContent('Demonstration stock');
    expect(n).toHaveTextContent('not split across stores');
    expect(n).toHaveTextContent('nothing is ordered or moved');
    expect(screen.getByRole('link', { name: /Store-level planning/ })).toHaveAttribute('href', '/sales-planning?tab=inventory');
  });
});
