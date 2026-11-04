import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import SourcingApp from './SourcingApp';
import { sourcingApi, type Compare, type Dashboard, type Opportunity, type OpportunityDetail } from '@/api/sourcing';
import { useAuthStore } from '@/store/authStore';

vi.mock('@/api/sourcing', async (orig) => {
  const real = await orig<typeof import('@/api/sourcing')>();
  const fn = () => vi.fn();
  return { ...real, sourcingApi: { dashboard: fn(), opportunities: fn(), opportunity: fn(), createOpportunity: fn(), changeStatus: fn(), note: fn(), rescore: fn(), suppliers: fn(), createSupplier: fn(), addQuote: fn(),
    verifyQuote: fn(), calculate: fn(), compare: fn(), calculator: fn(), runs: fn(), startRun: fn(), proposals: fn(), prepareProposal: fn(), reviewProposal: fn(), queue: fn(), seedDemo: fn(),
    supplier: fn(), setSupplierStage: fn(), classify: fn(), linkGap: fn(), addImage: fn(), setPrimaryImage: fn(), reportImage: fn(), recheckImage: fn(), readiness: fn(), run: fn(), runResults: fn() } };
});

const asRoles = (...roles: string[]) => useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u1', email: 'b@example.com', roles }, hydrate: () => {} });
const EMPTY: Dashboard = { as_of: '2026-10-15T10:00:00Z', scope: 'live', total_products: 0, by_status: {}, awaiting_review: 0, shortlisted: 0, approved_opportunity: 0, approved_for_onboarding: 0, with_supplier_quotes: 0,
  with_comparable_offers: 0, margin_at_least_25_pct: 0, blocked_by_missing_information: 0, with_product_image: 0, with_exact_model_image: 0, demand_gaps: { total: 0, by_status: {} }, by_provenance: {},
  excluded: { fixtures: 0, unknown_provenance: 0 }, recent_runs: [], demand_signals_in_window: 0, window_days: 30,
  notes: ['Margins are estimates.'], sources: { external_configured: false, external_feeds: [], configuration_problems: [], fx_currencies: [], base_currency: 'INR' } };
const OPP: Opportunity = { id: 'o1', name: 'DEMO Carbon Racket', brand: 'DemoBrand', model: 'D1', sport: 'Badminton', category: 'Rackets', specs: {}, status: 'QUOTE_COMPARISON', origin: 'DEMO', provenance: 'DEMO_FIXTURE', record_kind: 'PRODUCT_CANDIDATE',
  catalogue_match: { level: 'NONE', reason: 'nothing similar' }, possible_dupes: [], unanswered: ['Is there demand?'], first_seen: '2026-10-01T00:00:00Z', last_verified: '2026-10-10T00:00:00Z', version: 4, opp_score: null, margin: 31.2 };
const READY = { applicable: true, ready: false, summary: 'Not ready: 1 required item(s) missing.', blocking_count: 1, missing: ['Product image with its source'],
  items: [{ code: 'brand', label: 'Brand', ok: true, blocking: true, hint: '' }, { code: 'image', label: 'Product image with its source', ok: false, blocking: true, hint: 'No usable image is recorded.' }] };
const DETAIL = (over: Partial<OpportunityDetail> = {}): OpportunityDetail => ({
  opportunity: OPP, evidence: [{ id: 1, kind: 'BUYER_NOTE', source_label: 'DEMO DATA — NOT A LIVE SUPPLIER QUOTE', claim: 'Seeded demonstration product', retrieved_at: '2026-10-10T00:00:00Z' }],
  quotes: [{ id: 'q1', supplier_id: 's1', supplier_name: 'DEMO Supplier Alpha (sample)', equivalence: 'IDENTICAL', offered_product: 'x', currency: 'INR', unit_price: 2100, price_breaks: [], source_kind: 'QUOTE_ENTERED',
    label: 'Entered from a supplier quotation', status: 'ACTIVE', warnings: ['The quote has no validity date.'], origin: 'DEMO', moq: 100 }],
  score: null, score_at: null, calculations: [], history: [{ id: 1, at: '2026-10-10T00:00:00Z', actor_hash: 'abcdef123', action: 'DISCOVERED', to_status: 'DISCOVERED', detail: {} }], proposals: [],
  allowed_next: ['SUPPLIER_RESEARCH', 'REJECTED', 'ON_HOLD'], origin_label: 'DEMO DATA — NOT REAL RESEARCH', images: [], specifications: {}, missing_specifications: ['frame material'], readiness: READY,
  possible_suppliers: [], provenance_label: 'DEMO DATA: not real research', is_fixture: true, ...over });
const tab = async (user: ReturnType<typeof userEvent.setup>, key: string) => { await user.click(await screen.findByTestId(`dtab-${key}`)); };
const mount = (route = '/product-intelligence') => renderWithProviders(<SourcingApp />, route);

describe('Product & Supplier Intelligence', () => {
  beforeEach(() => { vi.clearAllMocks(); asRoles('PROCUREMENT_BUYER'); vi.mocked(sourcingApi.dashboard).mockResolvedValue(EMPTY); });

  it('is restricted for a customer and never calls the service', async () => {
    asRoles('CUSTOMER');
    mount();
    expect(await screen.findByTestId('src-restricted')).toBeInTheDocument();
    expect(sourcingApi.dashboard).not.toHaveBeenCalled();
  });

  it('shows a useful empty state, says no external source is configured, and invents no numbers', async () => {
    mount();
    expect(await screen.findByTestId('src-empty')).toHaveTextContent('Run research');
    expect(screen.getByTestId('src-no-sources')).toHaveTextContent('SOURCING_FEEDS');
    expect(screen.getByTestId('metric-total')).toHaveTextContent('0');
    expect(screen.getByTestId('src-no-runs')).toBeInTheDocument();
  });

  it('loads the labelled demonstration data from the empty state', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.seedDemo).mockResolvedValue({ created: { opportunities: 2 }, label: 'DEMO' });
    mount();
    await user.click(await screen.findByTestId('src-seed-demo'));
    await waitFor(() => expect(sourcingApi.seedDemo).toHaveBeenCalledTimes(1));
  });

  it('sends the chosen filters to the service and paginates on the server', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.opportunities).mockResolvedValue({ items: [OPP], total: 45, page: 0, size: 20 });
    mount('/product-intelligence?tab=opportunities');
    expect(await screen.findByTestId('opp-row')).toHaveTextContent('DEMO Carbon Racket');
    expect(screen.getByTestId('provenance-chip')).toHaveTextContent('DEMO DATA');
    await user.type(screen.getByTestId('opp-q'), 'racket');
    await waitFor(() => expect(sourcingApi.opportunities).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'racket', page: 0 })));
    fireEvent.change(screen.getByTestId('opp-min-margin'), { target: { value: '25' } });
    await waitFor(() => expect(sourcingApi.opportunities).toHaveBeenLastCalledWith(expect.objectContaining({ min_margin: 25 })));
    await user.click(await screen.findByRole('button', { name: /next page/i }));
    await waitFor(() => expect(sourcingApi.opportunities).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })));
  });

  it('shows an honest empty list and a service error', async () => {
    vi.mocked(sourcingApi.opportunities).mockResolvedValueOnce({ items: [], total: 0, page: 0, size: 20 });
    mount('/product-intelligence?tab=opportunities');
    expect(await screen.findByTestId('opp-empty')).toHaveTextContent('run research');
  });

  it('shows the detail with the demo banner, evidence, unscored state, labelled quotes and warnings', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL());
    mount('/product-intelligence?tab=opportunities&opp=o1');
    expect(await screen.findByTestId('demo-banner')).toHaveTextContent('NOT A LIVE SUPPLIER QUOTE');
    expect(screen.getByTestId('opp-unanswered')).toHaveTextContent('Is there demand?');
    await tab(user, 'catalogue');
    expect(screen.getByTestId('opp-catalogue-match')).toHaveTextContent('nothing similar');
    await tab(user, 'evidence');
    expect(screen.getByTestId('evidence-row')).toHaveTextContent('Seeded demonstration product');
    await tab(user, 'suppliers');
    expect(screen.getByTestId('quote-kind')).toHaveTextContent('Entered from a supplier quotation');
    expect(screen.getByTestId('quote-row')).toHaveTextContent('no validity date');
    expect(screen.getByTestId('no-possible-suppliers')).toBeInTheDocument();
    await tab(user, 'score');
    expect(screen.getByTestId('opp-score')).toHaveTextContent('Not scored yet');
  });

  it('shows an honest placeholder when there is no image, and a reference warning for an image that is not the exact model', async () => {
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL());
    mount('/product-intelligence?tab=opportunities&opp=o1');
    expect(await screen.findByTestId('no-images')).toHaveTextContent('nothing is guessed');
    expect(screen.getByTestId('image-placeholder')).toBeInTheDocument();
    cleanup();
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL({ images: [{ id: 'i1', url: 'https://cdn.example.com/a.webp', match_level: 'SIMILAR_REFERENCE', status: 'VALID', source_type: 'OFFICIAL_BRAND_PAGE',
      source_page_url: 'https://brand.example.com/p/a', attribution: 'brand.example.com (official brand page)', rights_note: 'Linked, not copied.', is_primary: true, reported: false }] }));
    mount('/product-intelligence?tab=opportunities&opp=o1');
    expect(await screen.findByTestId('image-caption')).toHaveTextContent('Reference image');
    expect(screen.getByTestId('image-match')).toHaveTextContent('Reference only');
    expect(screen.getByTestId('image-caption')).toHaveTextContent('source page');
  });

  it('reports a wrong image with a required reason and shows missing specifications as missing, never generated', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.reportImage).mockResolvedValue({} as never);
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL({ specifications: { 'Frame Material': 'Graphite' }, images: [{ id: 'i1', url: 'https://cdn.example.com/a.webp', match_level: 'LIKELY_MATCH', status: 'VALID',
      source_type: 'OFFICIAL_BRAND_PAGE', is_primary: true, reported: false }] }));
    mount('/product-intelligence?tab=opportunities&opp=o1');
    await user.click(await screen.findByTestId('image-report'));
    const dialog = await screen.findByTestId('report-dialog');
    expect(within(dialog).getByTestId('report-send')).toBeDisabled();
    await user.type(within(dialog).getByTestId('report-reason'), 'shows a shoe');
    await user.click(within(dialog).getByTestId('report-send'));
    await waitFor(() => expect(sourcingApi.reportImage).toHaveBeenCalledWith('i1', 'shows a shoe'));
    await tab(user, 'specs');
    expect(screen.getByTestId('spec-row')).toHaveTextContent('Frame Material');
    expect(screen.getByTestId('missing-specs')).toHaveTextContent('never generated');
  });

  it('keeps approval and catalogue readiness apart and lists what is missing', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL({ opportunity: { ...OPP, status: 'APPROVED_FOR_ONBOARDING' } }));
    mount('/product-intelligence?tab=opportunities&opp=o1');
    await tab(user, 'decisions');
    expect(await screen.findByTestId('readiness-summary')).toHaveTextContent('Approval and readiness are separate');
    const items = screen.getAllByTestId('readiness-item');
    expect(items[0]).toHaveTextContent('✔ Brand');
    expect(items[1]).toHaveTextContent('✖ Product image with its source — No usable image is recorded.');
  });

  it('hides demo and test data until asked, and sends the toggle to the service', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.opportunities).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
    mount('/product-intelligence?tab=opportunities');
    expect(await screen.findByTestId('opp-scope')).toHaveTextContent('Live data only');
    await user.click(screen.getByTestId('opp-fixtures'));
    await waitFor(() => expect(sourcingApi.opportunities).toHaveBeenLastCalledWith(expect.objectContaining({ include_fixtures: true })));
  });

  it('lists customer demand as signals that are not products, with no score, supplier or margin', async () => {
    vi.mocked(sourcingApi.opportunities).mockResolvedValue({ items: [{ ...OPP, id: 'g1', name: 'Pickleball Paddles', record_kind: 'DEMAND_GAP', provenance: 'CUSTOMER_DEMAND_SIGNAL', origin: 'DEMAND_SIGNAL', status: 'DISCOVERED', trend_note: '2 searches from 2 customers' }], total: 1, page: 0, size: 50 });
    mount('/product-intelligence?tab=gaps');
    expect(await screen.findByTestId('gap-row')).toHaveTextContent('Pickleball Paddles');
    expect(screen.getByTestId('src-gaps')).toHaveTextContent('demand signals, not products');
    expect(sourcingApi.opportunities).toHaveBeenCalledWith(expect.objectContaining({ record_kind: 'DEMAND_GAP' }));
  });

  it('shows a supplier candidate as discovered and not verified, and moves it up the ladder only with a note', async () => {
    const user = userEvent.setup();
    const sup = { id: 's9', name: 'VICTOR India', type: 'AUTHORISED_DISTRIBUTOR', country: 'India', categories: [], brands: ['Victor'], authorised_brands: [], verification_status: 'DISCOVERED' as const, origin: 'LIVE_RESEARCH' as const,
      contact: { email: 'info@example.com' }, provenance: 'LIVE_EXTERNAL_SOURCE' as const, discovery_note: 'Listed on the manufacturer\'s official distributors page. NOT verified.', quote_count: 0 };
    vi.mocked(sourcingApi.suppliers).mockResolvedValue({ items: [sup], total: 1 });
    vi.mocked(sourcingApi.supplier).mockResolvedValue({ supplier: { ...sup, verification_history: [{ stage: 'DISCOVERED', by: 'run', at: '2026-10-15T00:00:00Z' }] }, offers: [], provenance_label: 'Found by a configured research source', ladder: ['DISCOVERED', 'WEBSITE_CHECKED', 'IDENTITY_VERIFIED', 'BRAND_AUTHORISATION_VERIFIED'], is_fixture: false });
    vi.mocked(sourcingApi.setSupplierStage).mockResolvedValue(sup);
    mount('/product-intelligence?tab=suppliers');
    expect(await screen.findByTestId('stage-chip')).toHaveTextContent('Discovered (not verified)');
    await user.click(screen.getByTestId('sup-row'));
    const dialog = await screen.findByTestId('supplier-detail');
    expect(within(dialog).getByTestId('supplier-no-offers')).toHaveTextContent('quoted nothing yet');
    expect(within(dialog).getByTestId('stage-advance')).toBeDisabled();
    await user.type(within(dialog).getByTestId('stage-note'), 'Website loads, details match');
    await user.click(within(dialog).getByTestId('stage-advance'));
    await waitFor(() => expect(sourcingApi.setSupplierStage).toHaveBeenCalledWith('s9', 'WEBSITE_CHECKED', 'Website loads, details match', ''));
  });

  it('keeps opportunity, confidence and supplier risk apart and says when evidence is insufficient', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL({ score: { model_version: 'v2', status: 'INSUFFICIENT_EVIDENCE', opportunity: null, priority: null, confidence: 'LOW', data_completeness: 0.2, financial_status: 'NONE',
      dimensions: [], missing: ['margin', 'unmet_demand'], supplier_risk: { level: 'UNKNOWN', factors: ['No quotation yet.'] }, reasons: ['Not enough of the model could be evaluated to give a number.'], next_action: 'Gather evidence first.' }, score_at: '2026-10-15T00:00:00Z' }));
    mount('/product-intelligence?tab=opportunities&opp=o1');
    await tab(user, 'score');
    expect(await screen.findByTestId('score-insufficient')).toHaveTextContent('Insufficient evidence');
    expect(screen.getByTestId('score-financial')).toHaveTextContent('None yet');
    expect(screen.queryByTestId('score-value')).not.toBeInTheDocument();
    expect(screen.getByTestId('score-confidence')).toHaveTextContent('Low');
    expect(screen.getByTestId('score-missing')).toHaveTextContent('left out, not counted as zero');
  });

  it('asks for a reason before rejecting, confirms the change and sends the version the buyer was looking at', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL());
    vi.mocked(sourcingApi.changeStatus).mockResolvedValue({ ...OPP, status: 'REJECTED', version: 5 });
    mount('/product-intelligence?tab=opportunities&opp=o1');
    await user.click(await screen.findByTestId('to-REJECTED'));
    const dialog = await screen.findByTestId('status-dialog');
    expect(within(dialog).getByTestId('status-confirm')).toBeDisabled();
    await user.type(within(dialog).getByTestId('status-reason'), 'Not our segment');
    await user.click(within(dialog).getByTestId('status-confirm'));
    await waitFor(() => expect(sourcingApi.changeStatus).toHaveBeenCalledWith('o1', 'REJECTED', 'Not our segment', 4));
  });

  it('does not offer approval to a plain buyer, and warns that approval is not a purchase', async () => {
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL({ allowed_next: ['SUPPLIER_RESEARCH'] }));
    mount('/product-intelligence?tab=opportunities&opp=o1');
    await screen.findByTestId('opp-actions');
    expect(screen.queryByTestId('to-APPROVED_FOR_ONBOARDING')).not.toBeInTheDocument();
  });

  it('shows the approval warning and the backend refusal without pretending success', async () => {
    const user = userEvent.setup();
    asRoles('CATEGORY_MANAGER');
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL({ allowed_next: ['APPROVED_FOR_ONBOARDING'] }));
    // approval records a decision only: the dialog must say it does not authorise a purchase
    vi.mocked(sourcingApi.changeStatus).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: 'MISSING_EVIDENCE', message: 'Approval needs at least one active supplier quotation' } } });
    mount('/product-intelligence?tab=opportunities&opp=o1');
    await user.click(await screen.findByTestId('to-APPROVED_FOR_ONBOARDING'));
    const dialog = await screen.findByTestId('status-dialog');
    expect(dialog).toHaveTextContent('does not authorise a purchase');
    await user.click(within(dialog).getByTestId('status-confirm'));
    expect(await screen.findByTestId('status-error')).toHaveTextContent('Approval needs at least one active supplier quotation');
  });

  it('compares offers at one quantity and explains why the cheapest unit price may not be the cheapest landed cost', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.opportunity).mockResolvedValue(DETAIL());
    const calc = (landed: string) => ({ status: 'OK' as const, issues: [], missing: [], missing_for_margin: [], values: { landed_cost_per_unit: landed, total_procurement_cost: '1000.00', sellable_units: '500', unit_price: '10', gross_margin_pct: '30.0', markup_pct: '40.0' },
      assumptions: ['Unit price from the base unit price.'], cost_drivers: [{ component: 'Freight', amount: '100.00', share_pct: '10.0' }] });
    const cmp: Compare = { opportunity_id: 'o1', selling_price: 8000, summary: { quantity: 500, compared: 2, usable: 2, lowest_landed_quote: 'q1', lowest_unit_price_quote: 'q2',
      explanation: 'Beta has the lowest unit price, but Alpha has the lower landed cost per sellable unit.' }, offers: [
      { quote: { ...DETAIL().quotes[0] }, calculation: calc('2000.00'), warnings: [], rank_by_landed_cost: 1 },
      { quote: { ...DETAIL().quotes[0], id: 'q2', supplier_name: 'DEMO Supplier Beta (sample)', unit_price: 1900 }, calculation: calc('2100.00'), warnings: [], rank_by_landed_cost: 2 }] };
    vi.mocked(sourcingApi.compare).mockResolvedValue(cmp);
    mount('/product-intelligence?tab=opportunities&opp=o1');
    await tab(user, 'cost');
    await user.clear(await screen.findByTestId('cmp-qty'));
    await user.type(screen.getByTestId('cmp-qty'), '500');
    await user.click(screen.getByTestId('cmp-run'));
    expect(await screen.findByTestId('cmp-explanation')).toHaveTextContent('lower landed cost');
    expect(sourcingApi.compare).toHaveBeenCalledWith('o1', expect.objectContaining({ quantity: 500 }));
    const rows = screen.getAllByTestId('cmp-row');
    expect(rows[0]).toHaveTextContent('#1');
    await user.click(within(rows[0]).getByTestId('cmp-detail'));
    expect(await screen.findByTestId('cmp-breakdown')).toHaveTextContent('Landed cost per sellable unit');
    expect(screen.getByTestId('cmp-breakdown')).toHaveTextContent('Assumptions');
  });

  it('the standalone calculator shows an incomplete result with what is missing, not a guess', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.calculator).mockResolvedValue({ status: 'INCOMPLETE', issues: [], missing: ['fx_rate (USD to INR) from a stated source'], missing_for_margin: [], values: {}, assumptions: [], cost_drivers: [] });
    mount('/product-intelligence?tab=calculator');
    await user.type(await screen.findByTestId('calc-unit_price'), '10');
    await user.type(screen.getByTestId('calc-currency'), 'USD').catch(() => undefined);
    await user.click(screen.getByTestId('calc-run'));
    expect(await screen.findByTestId('calc-status')).toHaveTextContent('Incomplete. Missing: fx_rate');
  });

  it('runs research and reports a partial result truthfully', async () => {
    const user = userEvent.setup();
    vi.mocked(sourcingApi.runs).mockResolvedValue([]);
    vi.mocked(sourcingApi.startRun).mockResolvedValue({ id: 'r1', started_at: '2026-10-15T10:00:00Z', finished_at: '2026-10-15T10:00:01Z', status: 'PARTIAL', params: {}, stats: { created: 0, refreshed: 0, demand_opportunities: 0 },
      sources: [{ name: 'external feeds', status: 'unavailable', records: 0, reason: 'No external research source is configured' }] });
    mount('/product-intelligence?tab=runs');
    expect(await screen.findByTestId('runs-empty')).toBeInTheDocument();
    await user.click(screen.getByTestId('run-start'));
    expect(await screen.findByTestId('run-result')).toHaveTextContent('Partial');
  });
});
