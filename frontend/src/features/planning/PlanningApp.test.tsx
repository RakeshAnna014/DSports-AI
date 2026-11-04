import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import PlanningApp from './PlanningApp';
import { planningApi, type AllocPage, type Dashboard, type Evaluation, type Line, type LinesPage, type Plan, type Readiness } from '@/api/planning';
import { useAuthStore } from '@/store/authStore';

vi.mock('@/api/planning', async (orig) => {
  const real = await orig<typeof import('@/api/planning')>();
  const names = ['readiness', 'locations', 'taxonomy', 'seedDemo', 'ingest', 'runForecast', 'forecastOverview', 'forecastSeries', 'evaluation', 'plans', 'plan', 'createPlan', 'lines', 'editLines', 'growthTargets', 'transition',
    'revise', 'impact', 'performance', 'investigate', 'planAudit', 'actuals', 'importActuals', 'adjust', 'adjustments', 'decideAdjustment', 'generateAllocation', 'allocations', 'decideAllocation', 'decideTransfer',
    'scenarios', 'scenario', 'createScenario', 'compareScenarios', 'adoptScenario', 'pilots', 'proposePilot', 'pilotDecision', 'pilotObserve', 'dashboard', 'regional', 'ask', 'analyze', 'latestAnalysis', 'adoptFinding', 'dismissFinding', 'reviewQueue', 'breakdown', 'bulkReview'];
  return { ...real, planningApi: Object.fromEntries(names.map((k) => [k, vi.fn()])) };
});

const asRoles = (...roles: string[]) => useAuthStore.setState({ isAuthenticated: true, user: { userId: 'u1', email: 'p@example.com', roles }, hydrate: () => {} });
const PLAN = (over: Partial<Plan> = {}): Plan => ({ id: 'p1', name: 'Q3 plan', fy: 2026, fy_label: 'FY2026-27', first_week: '2026-10-05', last_week: '2026-12-28', source: 'POC_SYNTHETIC', status: 'DRAFT', version: 4, revision: 1,
  parent_plan_id: null, created_by: 'h', created_at: '2026-10-15T00:00:00Z', submitted_by: null, note: null, forecast_run_id: 'r1', scope: {}, allowed_next: ['SUBMITTED'], editable: true,
  data_scope: { source: 'POC_SYNTHETIC', synthetic: true, label: 'demo' }, ...over });
const LINE = (over: Partial<Line> = {}): Line => ({ product_id: 'prod1', product_name: 'Test Bat One', sku: 'T-BAT-1', sport: 'Cricket', category: 'Bat', location_id: 'N-Z1-S1', store_name: 'North Zone 1 Store 1', region: 'N', zone: 'N-Z1',
  week_start: '2026-10-05', week_closed: false, model_forecast: 10, model_lo: 6, model_hi: 15, confidence: 'MEDIUM', manager_forecast: null, target_units: null, approved_forecast: null, adj_reason: null, adj_note: null,
  line_version: 3, latest_forecast: 11, actual_units: null, authoritative_units: null, adjustment_units: 0, actual_source: null, sellable_stock: 40, reserved_stock: 0, incoming_stock: 5, ...over });
const PAGE = (items: Line[], editable = true, status: Plan['status'] = 'DRAFT'): LinesPage => ({ items, total: 120, page: 0, size: 50, plan: { id: 'p1', status, version: 4, source: 'POC_SYNTHETIC', editable } });
const DASH = (over: Partial<Dashboard> = {}): Dashboard => ({ source: 'POC_SYNTHETIC', freshness: { data_through: '2026-10-07', last_forecast: '2026-10-15T00:00:00Z', forecast_as_of_week: '2026-09-28', last_ingest: null }, data_scope: { source: 'POC_SYNTHETIC', synthetic: true },
  plan: null, period: null, message: 'There is no plan for this data scope yet: generate a forecast and create a plan.', metrics: {}, queues: { plans_awaiting_approval: 0, plans_in_draft: 0 }, ...over });
const mount = (route = '/sales-planning?tab=workspace&plan=p1') => renderWithProviders(<PlanningApp />, route);

const api = vi.mocked(planningApi);

describe('Sales & Inventory Planning', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    asRoles('SALES_PLANNER');
    api.taxonomy.mockResolvedValue({ Cricket: ['Ball', 'Bat'], Badminton: ['Racket'] });
    api.locations.mockResolvedValue({ scope: 'ALL', items: [{ id: 'N', name: 'North', level: 'REGION', parent_id: null, channel: 'NONE', status: 'ACTIVE', opened_on: null, data_source: 'POC_REFERENCE' },
      { id: 'N-Z1-S1', name: 'North Zone 1 Store 1', level: 'STORE', parent_id: 'N-Z1', channel: 'STORE', status: 'ACTIVE', opened_on: null, data_source: 'POC_REFERENCE' }] });
    api.plans.mockResolvedValue([PLAN()]);
    api.plan.mockResolvedValue(PLAN());
    api.dashboard.mockResolvedValue(DASH());
    api.lines.mockResolvedValue(PAGE([LINE()]));
    api.latestAnalysis.mockResolvedValue({ id: null, findings: [], message: 'No analysis has been run for this plan yet.' });
    api.reviewQueue.mockResolvedValue({ items: [], statuses: ['SUBMITTED', 'UNDER_REVIEW'], notice: 'Approving records a decision about the plan. It does not order stock, move stock, publish products or change prices.' });
    api.regional.mockResolvedValue({ weeks: { first: '2026-04-06', last: '2026-10-05' }, matrix: [], store_ranking: [], top_products: [], insights: [], notice: 'n' });
    api.performance.mockResolvedValue({ plan: { id: 'p1', name: 'Q3 plan', status: 'DRAFT', source: 'POC_SYNTHETIC' }, grain: 'week', rows: [], total: null, basis: 'MANAGER_OR_MODEL', data_through: '2026-10-07', formulas: {}, notice: '' } as never);
    api.allocations.mockResolvedValue({ items: [], total: 0, page: 0, size: 8, summary: { recs: 0, high_risk: 0, to_order: 0, with_surplus: 0, units_to_order: 0, products_to_order: 0, generated_at: null, transfers: 0, transfer_units: 0 }, transfers: [] });
  });

  it('is restricted for a customer and never calls the service', async () => {
    asRoles('CUSTOMER');
    mount();
    expect(await screen.findByTestId('pln-restricted')).toBeInTheDocument();
    expect(api.plans).not.toHaveBeenCalled();
    expect(api.dashboard).not.toHaveBeenCalled();
  });

  it('always labels demonstration data and shows an honest empty dashboard with no invented numbers', async () => {
    mount('/sales-planning');
    expect(await screen.findByTestId('dash-no-plan')).toHaveTextContent('no plan for this data scope yet');
    expect(screen.getAllByTestId('scope-banner-demo')[0]).toHaveTextContent('DEMONSTRATION DATA');
    expect(screen.getByTestId('metric-target')).toHaveTextContent('None set');
    expect(screen.getByTestId('metric-actual')).toHaveTextContent('No closed week');
    expect(screen.getByTestId('metric-accuracy')).toHaveTextContent('Not enough data');
  });

  it('shows the dashboard metrics from the plan, withholds accuracy it cannot support, and says a missing actual is not zero', async () => {
    api.dashboard.mockResolvedValue(DASH({ plan: { id: 'p1', name: 'Q3 plan', status: 'APPROVED', fy_label: 'FY2026-27', version: 6 }, period: { first_week: '2026-10-05', last_week: '2026-12-28', weeks: 13 },
      metrics: { model_forecast_units: 6052, plan_forecast_units: 6100, target_units: 6500, actual_units: 910, target_attainment_pct: 93.2, lines: 100, lines_with_actual: 20, lines_with_target: 80,
        forecast_accuracy: { wape: 0.62, bias: -0.02, baseline_wape: 0.69, n_obs: 30934, sufficient: true, basis: 'held-out weeks, synthetic data' } }, queues: { plans_awaiting_approval: 2, stores_at_stockout_risk: 3, products_to_replenish: 7, units_to_order: 420, plans_in_draft: 1 } }));
    api.performance.mockResolvedValue({ plan: { id: 'p1', name: 'x', status: 'APPROVED', source: 'POC_SYNTHETIC' }, grain: 'week', basis: 'APPROVED', data_through: '2026-10-07', formulas: {}, notice: '', total: null,
      rows: [{ gkey: '2026-10-05', forecast: 100, target: 110, actual: null }, { gkey: '2026-10-12', forecast: 90, target: 100, actual: null }] as never });
    mount('/sales-planning?plan=p1');
    expect(await screen.findByTestId('metric-model')).toHaveTextContent('6,052');
    expect(screen.getByTestId('metric-target')).toHaveTextContent('6,500');
    expect(screen.getByTestId('metric-attainment')).toHaveTextContent('93.2%');
    expect(screen.getByTestId('metric-accuracy')).toHaveTextContent('WAPE 62%');
    expect(screen.getByTestId('metric-risk')).toHaveTextContent('3');
    expect(screen.getByTestId('dash-chart')).toHaveTextContent('not closed');
  });

  it('shows model forecast, latest model, manager forecast, approved forecast, target and actual as separate columns', async () => {
    api.lines.mockResolvedValue(PAGE([LINE({ manager_forecast: 12, target_units: 15, approved_forecast: 12, actual_units: 9, week_closed: true, actual_source: 'IMPORTED', adj_reason: 'LOCAL_EVENT' })]));
    mount();
    const row = await screen.findByTestId('ws-row');
    expect(within(row).getByTestId('ws-model')).toHaveTextContent('10.0');
    expect(within(row).getByTestId('ws-manager')).toHaveValue('12');
    expect(within(row).getByTestId('ws-approved')).toHaveTextContent('12.0');
    expect(within(row).getByTestId('ws-target')).toHaveValue('15');
    expect(within(row).getByTestId('ws-actual')).toHaveTextContent('9');
    expect(within(row).getByTestId('ws-fvar')).toHaveTextContent('-3.0');                    // actual 9 - approved 12
    expect(within(row).getByTestId('ws-tvar')).toHaveTextContent('-6.0');                    // actual 9 - target 15
    for (const h of ['Model forecast', 'Latest model', 'Manager forecast', 'Approved forecast', 'Target', 'Actual']) expect(screen.getByText(h, { selector: 'th, th *' })).toBeInTheDocument();
  });

  it('asks the server for one page at a time and passes the filters, never one request per row', async () => {
    const user = userEvent.setup();
    mount();
    await screen.findByTestId('ws-row');
    expect(api.lines).toHaveBeenCalledWith('p1', {}, 'product', 0, 50);
    await user.type(screen.getByTestId('f-q'), 'bat');
    await waitFor(() => expect(api.lines).toHaveBeenLastCalledWith('p1', expect.objectContaining({ q: 'bat' }), 'product', 0, 50));
    await user.click(await screen.findByRole('button', { name: /next page/i }));
    await waitFor(() => expect(api.lines).toHaveBeenLastCalledWith('p1', expect.anything(), 'product', 1, 50));
    expect(api.impact).not.toHaveBeenCalled();
  });

  it('saves a target with the version the planner saw, and a forecast adjustment only after a reason', async () => {
    const user = userEvent.setup();
    api.editLines.mockResolvedValue({ changed: 1 });
    mount();
    const row = await screen.findByTestId('ws-row');
    await user.type(within(row).getByTestId('ws-target'), '15');
    await user.click(screen.getByTestId('ws-save'));
    await waitFor(() => expect(api.editLines).toHaveBeenCalledWith('p1', [{ product_id: 'prod1', location_id: 'N-Z1-S1', week_start: '2026-10-05', expected_line_version: 3, target_units: 15 }]));
    expect(await screen.findByTestId('ws-saved')).toHaveTextContent('1 line(s) saved');
    await user.type(within(await screen.findByTestId('ws-row')).getByTestId('ws-manager'), '14');
    await user.click(screen.getByTestId('ws-save'));
    const dialog = await screen.findByTestId('reason-dialog');
    expect(api.editLines).toHaveBeenCalledTimes(1);                                        // nothing is sent until the reason is given
    await user.type(within(dialog).getByTestId('reason-note'), 'regional tournament');
    await user.click(within(dialog).getByTestId('reason-save'));
    await waitFor(() => expect(api.editLines).toHaveBeenLastCalledWith('p1', [expect.objectContaining({ manager_forecast: 14, reason_code: 'PROMOTION', note: 'regional tournament', expected_line_version: 3 })]));
  });

  it('blocks a save with an invalid number and says why', async () => {
    const user = userEvent.setup();
    mount();
    const row = await screen.findByTestId('ws-row');
    await user.type(within(row).getByTestId('ws-target'), '-4');
    expect(await screen.findByTestId('ws-invalid')).toHaveTextContent('not valid');
    expect(screen.getByTestId('ws-save')).toBeDisabled();
  });

  it('on an edit conflict shows that nothing was saved and offers a reload', async () => {
    const user = userEvent.setup();
    api.editLines.mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: 'EDIT_CONFLICT', message: 'Nothing was saved', details: { info: { conflicts: [{ product_id: 'prod1', location_id: 'N-Z1-S1', week_start: '2026-10-05', code: 'VERSION_CONFLICT', message: 'Someone else changed this line' }] } } } } });
    mount();
    await user.type(within(await screen.findByTestId('ws-row')).getByTestId('ws-target'), '20');
    await user.click(screen.getByTestId('ws-save'));
    expect(await screen.findByTestId('ws-conflict')).toHaveTextContent('Nothing was saved: 1 line(s) changed');
    expect(screen.getByTestId('ws-conflict')).toHaveTextContent('Someone else changed this line');
  });

  it('shows a frozen plan read-only with no inputs and no save button', async () => {
    api.plan.mockResolvedValue(PLAN({ status: 'APPROVED', editable: false, allowed_next: ['PUBLISHED'] }));
    api.lines.mockResolvedValue(PAGE([LINE({ approved_forecast: 10 })], false, 'APPROVED'));
    mount();
    expect(await screen.findByTestId('ws-frozen')).toHaveTextContent('cannot be edited');
    expect(screen.queryByTestId('ws-target')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ws-save')).not.toBeInTheDocument();
  });

  it('moves a plan with the version the user saw; approving warns that nothing is bought or published and rejecting needs a reason', async () => {
    const user = userEvent.setup();
    asRoles('CATEGORY_MANAGER');
    api.plan.mockResolvedValue(PLAN({ status: 'UNDER_REVIEW', allowed_next: ['APPROVED', 'RETURNED', 'REJECTED'], editable: false }));
    api.transition.mockResolvedValue(PLAN({ status: 'APPROVED' }));
    mount();
    await user.click(await screen.findByTestId('plan-to-APPROVED'));
    const dlg = await screen.findByTestId('transition-dialog');
    expect(dlg).toHaveTextContent('orders, publishes and prices nothing');
    await user.click(within(dlg).getByTestId('transition-confirm'));
    await waitFor(() => expect(api.transition).toHaveBeenCalledWith('p1', 'APPROVED', undefined, 4));
    await user.click(await screen.findByTestId('plan-to-REJECTED'));
    expect(within(await screen.findByTestId('transition-dialog')).getByTestId('transition-confirm')).toBeDisabled();
  });

  it('shows the backend refusal of an approval (self-approval) without pretending success', async () => {
    const user = userEvent.setup();
    asRoles('CATEGORY_MANAGER');
    api.plan.mockResolvedValue(PLAN({ status: 'UNDER_REVIEW', allowed_next: ['APPROVED'], editable: false }));
    api.transition.mockRejectedValue({ isAxiosError: true, response: { status: 403, data: { code: 'SELF_APPROVAL', message: 'You submitted this plan: someone else must approve it' } } });
    mount();
    await user.click(await screen.findByTestId('plan-to-APPROVED'));
    await user.click(within(await screen.findByTestId('transition-dialog')).getByTestId('transition-confirm'));
    expect(await screen.findByTestId('transition-error')).toHaveTextContent('someone else must approve it');
  });

  it('compares plan and actual by a chosen grain and shows no variance where no week is closed', async () => {
    api.performance.mockResolvedValue({ plan: { id: 'p1', name: 'Q3', status: 'APPROVED', source: 'POC_SYNTHETIC' }, grain: 'category', basis: 'APPROVED', data_through: '2026-10-07', formulas: {}, notice: 'Forecast variance is against the APPROVED forecast.',
      total: { gkey: 'ALL', lines: 20, with_actual: 0, with_forecast: 20, with_target: 5, actual: null, forecast: 800, target: 900, model: 780, a_f: null, f_a: null, a_t: null, t_a: null, adjustments: null, forecast_variance: null, forecast_variance_pct: null, target_variance: null, target_attainment_pct: null, first_week: '2026-10-05' },
      rows: [{ gkey: 'Cricket / Bat', lines: 10, with_actual: 4, with_forecast: 10, with_target: 3, actual: 52, forecast: 400, target: 450, model: 380, a_f: 52, f_a: 60, a_t: 52, t_a: 65, adjustments: 0, forecast_variance: -8, forecast_variance_pct: -13.3, target_variance: -13, target_attainment_pct: 80, first_week: '2026-10-05' },
        { gkey: 'Cricket / Ball', lines: 10, with_actual: 0, with_forecast: 10, with_target: 0, actual: null, forecast: 400, target: null, model: 400, a_f: null, f_a: null, a_t: null, t_a: null, adjustments: null, forecast_variance: null, forecast_variance_pct: null, target_variance: null, target_attainment_pct: null, first_week: '2026-10-05' }] });
    mount('/sales-planning?tab=performance&plan=p1');
    const rows = await screen.findAllByTestId('perf-row');
    expect(within(rows[0]).getByTestId('perf-fvar')).toHaveTextContent('-8');
    expect(within(rows[0]).getByTestId('perf-attain')).toHaveTextContent('80.0%');
    expect(rows[1]).toHaveTextContent('no closed week');
    expect(within(rows[1]).getByTestId('perf-fvar')).toHaveTextContent('—');
    expect(screen.getByTestId('perf-notice')).toHaveTextContent('Forecast variance = actual − forecast');
  });

  it('withholds accuracy percentages that rest on too few observations and shows the baseline comparison', async () => {
    const row = (key: string, ok: boolean) => ({ scope: 'STORE', scope_key: key, model: 'SELECTED', horizon_group: 'ALL', n_obs: ok ? 900 : 9, n_series: 3, actual_units: ok ? 1200 : 11, wape: ok ? 0.5 : null, bias: ok ? -0.02 : null, mae: ok ? 1.2 : null,
      baseline_wape: ok ? 0.6 : null, sufficient: ok, note: ok ? null : 'Only 9 observation(s): too few to quote an accuracy.', better_than_baseline: ok ? true : null });
    const ev = { run: { id: 'r1', source: 'POC_SYNTHETIC', as_of_week: '2026-09-28', first_week: '2026-10-05', horizon_weeks: 13, model_version: 'weekly-v1', n_series: 2680, n_ok: 2412, n_fallback: 268, n_insufficient: 0, created_at: '' },
      features: { used: ['weekly net units'], not_used: ['promotion calendar'], data_scope: 'POC_SYNTHETIC', history_weeks: 104, data_through: '2026-10-07' }, method_choice: { LOW: { n_obs: 40000, wape: { MA4: 0.66, TOP_DOWN: 0.58 }, chosen: 'TOP_DOWN' } },
      chosen: { LOW: 'TOP_DOWN' }, notice: 'Accuracy is measured on the last weeks of history the model did not see. This is SYNTHETIC history.', baseline: 'MA4', rows: [row('N-Z1-S1', true), row('E-Z2-S2', false)] } as Evaluation;
    api.evaluation.mockResolvedValue(ev);
    api.forecastSeries.mockResolvedValue({ items: [], total: 0, page: 0, size: 15, run: { id: 'r1', source: 'POC_SYNTHETIC', as_of_week: '', first_week: '', horizon_weeks: 13, model_version: '', created_at: '' } });
    mount('/sales-planning?tab=evaluation');
    const rows = await screen.findAllByTestId('eval-row');
    expect(within(rows[0]).getByTestId('eval-wape')).toHaveTextContent('50.0%');
    expect(within(rows[1]).getByTestId('eval-wape')).toHaveTextContent('withheld');
    expect(rows[1]).toHaveTextContent('too few to quote');
    expect(screen.getByTestId('eval-notice')).toHaveTextContent('SYNTHETIC');
    expect(screen.getByTestId('eval-methods')).toHaveTextContent('chosen Top down');
    expect(screen.getByTestId('eval-features')).toHaveTextContent('Not used: promotion calendar');
  });

  it('explains inventory recommendations as drafts and hides approval from a planner', async () => {
    const page: AllocPage = { items: [{ id: 'a1', product_id: 'prod1', product_name: 'Test Bat One', sku: 'T-BAT-1', sport: 'Cricket', category: 'Bat', location_id: 'N-Z1-S1', store_name: 'North Zone 1 Store 1', region: 'N', action: 'ORDER', risk_level: 'HIGH',
      required_stock: 80, on_hand: 4, reserved: 0, incoming: 0, shortage: 76, surplus: 0, recommended_qty: 60, order_by: '2026-10-08', arrival_date: '2026-10-22', safety_stock: 12, coverage_days: 2, confidence: 'MEDIUM', reason: 'Target stock 80', status: 'DRAFT', detail: {} }],
      total: 1, page: 0, size: 25, summary: { recs: 57, high_risk: 5, to_order: 9, with_surplus: 4, units_to_order: 420, products_to_order: 3, generated_at: '2026-10-15T00:00:00Z', transfers: 1, transfer_units: 12 },
      transfers: [{ id: 't1', product_name: 'Test Bat One', from_location: 'N-Z1-S2', to_location: 'N-Z1-S1', from_name: 'North Zone 1 Store 2', to_name: 'North Zone 1 Store 1', qty: 12, lead_days: 3, arrival_date: '2026-10-11', reason: 'keeps its own target level', status: 'DRAFT', avoids_order_qty: 12 }] };
    api.allocations.mockResolvedValue(page);
    mount('/sales-planning?tab=inventory&plan=p1');
    expect(await screen.findByTestId('inv-row')).toHaveTextContent('60');
    expect(screen.getByTestId('metric-inv-high')).toHaveTextContent('5');
    expect(screen.getByTestId('transfer-row')).toHaveTextContent('12 × Test Bat One');
    expect(screen.getByTestId('inventory')).toHaveTextContent('drafts');
    expect(screen.queryByTestId('inv-approve')).not.toBeInTheDocument();                  // a planner cannot approve
    expect(screen.queryByTestId('transfer-approve')).not.toBeInTheDocument();
  });

  it('lets an approver approve a recommendation and says that nothing was ordered', async () => {
    const user = userEvent.setup();
    asRoles('CATEGORY_MANAGER');
    api.allocations.mockResolvedValue({ items: [{ id: 'a1', product_id: 'p', product_name: 'Bat', sku: 's', sport: 'c', category: 'b', location_id: 'l', store_name: 'Store', region: 'N', action: 'ORDER', risk_level: 'LOW', required_stock: 1, on_hand: 1, reserved: 0, incoming: 0,
      shortage: 0, surplus: 0, recommended_qty: 5, order_by: null, arrival_date: null, safety_stock: 1, coverage_days: 9, confidence: null, reason: 'r', status: 'DRAFT', detail: {} }], total: 1, page: 0, size: 25,
      summary: { recs: 1, high_risk: 0, to_order: 1, with_surplus: 0, units_to_order: 5, products_to_order: 1, generated_at: null, transfers: 0, transfer_units: 0 }, transfers: [] });
    api.decideAllocation.mockResolvedValue({ notice: 'Recorded. No purchase order or stock transfer has been created.' } as never);
    mount('/sales-planning?tab=inventory&plan=p1');
    await user.click(await screen.findByTestId('inv-approve'));
    expect(await screen.findByTestId('inv-decided')).toHaveTextContent('No purchase order');
  });

  it('creates a scenario only for a chosen scope, labels the result a simulation, and shows the elasticity limits', async () => {
    const user = userEvent.setup();
    api.scenarios.mockResolvedValue([]);
    api.pilots.mockResolvedValue([]);
    api.createScenario.mockResolvedValue({ id: 's1', name: '+10% bats', kind: 'GROWTH_TARGET', params: {}, status: 'SIMULATION', created_at: '', plan_id: 'p1', results: { notice: 'A simulation. It changes no plan, forecast, target, price, order or stock.',
      summary: { baseline: { demand_units: 100, order_units: 40, stores_high_risk: 2 }, scenario: { demand_units: 110, order_units: 60, stores_high_risk: 3 }, delta: { demand_units: 10, order_units: 20, stores_high_risk: 1 } }, items: [], limits: ['A target is not a forecast.'], skipped: [] } });
    mount('/sales-planning?tab=scenarios&plan=p1');
    expect(await screen.findByTestId('scn-run')).toBeDisabled();                           // no sport or category chosen yet
    await user.click(screen.getByTestId('f-sport'));
    await user.click(within(await screen.findByRole('listbox')).getByText('Cricket'));
    await user.click(await screen.findByTestId('scn-run'));
    expect(await screen.findByTestId('scn-result')).toHaveTextContent('A simulation');
    expect(screen.getByTestId('scn-order_units')).toHaveTextContent('60');
    expect(screen.getByTestId('scn-result')).toHaveTextContent('A target is not a forecast');
    expect(api.createScenario).toHaveBeenCalledWith(expect.objectContaining({ plan_id: 'p1', kind: 'GROWTH_TARGET', params: { growth_pct: 10 } }));
  });

  it('refuses a pilot for an unapproved product with the backend reason', async () => {
    const user = userEvent.setup();
    api.scenarios.mockResolvedValue([]);
    api.pilots.mockResolvedValue([]);
    api.proposePilot.mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: 'NOT_APPROVED', message: 'demo, test and unapproved products are never piloted' } } });
    mount('/sales-planning?tab=scenarios&plan=p1');
    await user.type(await screen.findByTestId('pilot-opp'), '12345678-1234-1234-1234-123456789012');
    await user.click(screen.getByTestId('pilot-propose'));
    expect(await screen.findByTestId('pilot-error')).toHaveTextContent('never piloted');
    expect(screen.getByTestId('pilots-empty')).toBeInTheDocument();
  });

  it('shows the data readiness with demonstration and unavailable inputs, and only an administrator gets the data controls', async () => {
    const r = { capabilities: [{ key: 'online_sales_actuals', label: 'Online sales actuals', status: 'INSUFFICIENT', evidence: '4 weeks', note: 'needs 26' }, { key: 'store_level_history', label: 'Store-level sales history', status: 'DEMONSTRATION_ONLY', evidence: '104 weeks synthetic', note: 'no physical stores' },
      { key: 'promotion_history', label: 'Promotion history', status: 'UNAVAILABLE', evidence: '0', note: 'not exposed' }], forecasting: { POC_SYNTHETIC: { status: 'DEMONSTRATION_ONLY', reason: 'Built on synthetic history.', run: 'r1' }, DSPORTS_LIVE: { status: 'INSUFFICIENT', reason: '4 weeks', run: null } },
      live: { weeks: 4, data_through: '2026-10-14', lines: { orders: 2624, order_days: 14 } }, synthetic: {}, imported: {}, last_ingest: null, reconciliation: { reconciled: true, lines: 2737, excluded: 2, line_net: 2491, week_net: 2491 },
      thresholds: { min_history_weeks: 26, min_eval_observations: 30, min_eval_units: 100 }, conventions: { week_start: 'Mon', timezone: 'Asia/Kolkata', fy_start_month: '4', net_units: 'gross - cancelled - returned', restatement: 'a later cancellation restates the original week' } } as unknown as Readiness;
    api.readiness.mockResolvedValue(r);
    mount('/sales-planning?tab=readiness');
    const rows = await screen.findAllByTestId('cap-row');
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByTestId('cap-status')).toHaveTextContent('Demonstration only');
    expect(within(rows[2]).getByTestId('cap-status')).toHaveTextContent('Unavailable');
    expect(screen.getByTestId('reconcile')).toHaveTextContent('exact (units and money agree)');
    expect(screen.queryByTestId('ingest-incremental')).not.toBeInTheDocument();
    expect(screen.queryByTestId('seed-demo')).not.toBeInTheDocument();
    expect(screen.getByTestId('run-forecast-POC_SYNTHETIC')).toBeInTheDocument();           // a planner can regenerate the forecast
  });

  it('gives an administrator the ingest and demo controls and reports the ingest result', async () => {
    const user = userEvent.setup();
    asRoles('ADMIN');
    api.readiness.mockResolvedValue({ capabilities: [], forecasting: { POC_SYNTHETIC: { status: 'DEMONSTRATION_ONLY', reason: '', run: null }, DSPORTS_LIVE: { status: 'UNAVAILABLE', reason: '', run: null } }, live: { weeks: 0, lines: {} }, synthetic: {}, imported: {}, last_ingest: null,
      reconciliation: { reconciled: true, lines: 0, excluded: 0, line_net: 0, week_net: 0 }, thresholds: { min_history_weeks: 26, min_eval_observations: 30, min_eval_units: 100 }, conventions: { week_start: 'Mon', timezone: 'UTC', fy_start_month: '4', net_units: 'x', restatement: 'y' } } as unknown as Readiness);
    api.ingest.mockResolvedValue({ status: 'SUCCEEDED', lines_read: 2737, lines_changed: 2737, lines_excluded: 2 });
    mount('/sales-planning?tab=readiness');
    await user.click(await screen.findByTestId('ingest-incremental'));
    expect(await screen.findByTestId('ingest-result')).toHaveTextContent('2737 line(s) read');
  });

  it('shows the validation errors of a rejected actuals import and keeps nothing', async () => {
    const user = userEvent.setup();
    api.actuals.mockResolvedValue({ rows: [], data_through: '2026-10-07', notice: 'A week is CLOSED only when its last day is covered.' });
    api.adjustments.mockResolvedValue([]);
    api.importActuals.mockRejectedValue({ isAxiosError: true, response: { status: 422, data: { code: 'IMPORT_REJECTED', message: '1 row(s) are invalid: nothing was imported', details: { info: { errors: [{ row: 1, errors: ['the week has not ended: only closed weeks can be imported'] }] } } } } });
    mount('/sales-planning?tab=actuals');
    await user.click(await screen.findByTestId('act-import'));
    await user.click(within(await screen.findByTestId('import-dialog')).getByTestId('import-run'));
    expect(await screen.findByTestId('import-error')).toHaveTextContent('Row 1: the week has not ended');
    expect(screen.getByTestId('act-empty')).toBeInTheDocument();
  });

  it('separates the recorded facts from the hypotheses in a variance investigation', async () => {
    const user = userEvent.setup();
    api.lines.mockResolvedValue(PAGE([LINE({ week_closed: true, actual_units: 2, actual_source: 'IMPORTED' })]));
    api.investigate.mockResolvedValue({ comparison: { actual: 2, forecast: 10, target: null, forecast_variance: -8, forecast_variance_pct: -80, target_variance: null, target_attainment_pct: null }, flagged: true,
      facts: ['Actual 2 against forecast 10: -8 units (-80%).', 'The source records 3 stock-out day(s) in the week.'], hypotheses: ['Lower sales than forecast may reflect unavailable stock rather than lower demand.'],
      limits: ['A hypothesis is a possible reason, not a finding.'], line: LINE(), thresholds: { percent: 20, units: 10 } });
    mount();
    await user.click(await screen.findByTestId('ws-why'));
    const dlg = await screen.findByTestId('investigate-dialog');
    expect(within(dlg).getAllByTestId('fact')).toHaveLength(2);
    expect(within(dlg).getByTestId('hypothesis')).toHaveTextContent('may reflect unavailable stock');
    expect(dlg).toHaveTextContent('hypotheses, not findings');
  });

  it('answers a question from the planning records and links the evidence to a tab', async () => {
    const user = userEvent.setup();
    api.ask.mockResolvedValue({ intent: 'ACCURACY', answer: 'On the last 13 weeks held out from the model, WAPE is 62% against 69%.', evidence: [{ label: 'Forecast evaluation', link: '/sales-planning?tab=evaluation' }], limitations: ['Synthetic history.'] });
    api.evaluation.mockResolvedValue({ run: { id: 'r' }, rows: [], notice: '', features: { used: [], not_used: [] }, method_choice: null } as unknown as Evaluation);
    api.forecastSeries.mockResolvedValue({ items: [], total: 0, page: 0, size: 15, run: { id: 'r', source: 'POC_SYNTHETIC', as_of_week: '', first_week: '', horizon_weeks: 13, model_version: '', created_at: '' } });
    mount('/sales-planning?tab=ask');
    await user.type(await screen.findByTestId('ask-input'), 'Show the forecast accuracy');
    await user.click(screen.getByTestId('ask-send'));
    expect(await screen.findByTestId('ask-answer')).toHaveTextContent('WAPE is 62%');
    await user.click(screen.getByTestId('ask-evidence'));
    await waitFor(() => expect(api.evaluation).toHaveBeenCalled());
    expect(fireEvent).toBeDefined();
  });
});


const FINDING = (over: Partial<import('@/api/planning').Finding> = {}): import('@/api/planning').Finding => ({
  id: 'f1', kind: 'FORECAST_VS_RECENT', title: 'Cricket / Ball: forecast above recent sales', severity: 'MEDIUM', statement: 'Cricket / Ball: the plan forecasts 312.6 units per week against 251.5 sold (+24%).',
  facts: ['Forecast: 312.6 units/week.', 'Actual: 251.5 units/week.'], hypotheses: ['Recent weeks may include a stock-out.'], scope: { category: 'Cricket / Ball' }, evidence: [{ label: 'Plan lines for Cricket / Ball', tab: 'workspace', params: { category: 'Ball', sport: 'Cricket' } }],
  provenance: ['DEMO', 'FORECAST'], freshness: { data_through: '2026-10-07', forecast_generated: '2026-10-15T10:00:00Z', plan_version: 1 }, assumptions: ['Recent average of closed weeks.'], confidence: 'MEDIUM', impact: 'The plan is 244 units above recent demand.',
  risks: ['Over-forecasting leaves excess stock.'], next_action: 'Review the largest lines.', decision: null,
  proposal: { kind: 'MANAGER_FORECAST', reason_code: 'DATA_CORRECTION', weeks: ['2026-10-05', '2026-10-12'], basis: 'Proposed = 50% recent + 50% model.', note: 'AI-assisted proposal', items: [{ product_id: 'x', location_id: 'S-Z1-S1', name: 'SG Ball', store: 'S-Z1-S1', model_per_week: 14.9, recent_per_week: 7, proposed_per_week: 10.9 }] }, ...over,
});
const ANALYSIS = (over: Partial<import('@/api/planning').Analysis> = {}): import('@/api/planning').Analysis => ({
  id: 'a1', plan_id: 'p1', created_at: '2026-10-16T05:00:00Z', source: 'POC_SYNTHETIC', data_through: '2026-10-07', model_version: 'weekly-v1', plan_version: 1, summary: '* This is demonstration data.\n* Cricket / Ball is above recent sales.', summary_source: 'LLM',
  llm: { used: true, model: 'gemini-2.5-flash', tokensIn: 800, tokensOut: 120, grounded: true }, findings: [FINDING()], unavailable: [{ analysis: 'Plan versus actual variance', why: 'No week has closed.', needs: 'Wait for a week to close.' }],
  data_scope: { source: 'POC_SYNTHETIC', synthetic: true }, notice: 'Findings are computed from stored records.', ...over,
});
const QITEM = (over: Partial<import('@/api/planning').ReviewItem> = {}): import('@/api/planning').ReviewItem => ({
  id: 'q1', name: 'North bats', status: 'SUBMITTED', source: 'POC_SYNTHETIC', version: 2, fy_label: 'FY2026-27', first_week: '2026-10-05', last_week: '2026-12-28', scope: { region: 'N' }, revision: 1, submitted_by_ref: 'ab12cd34', submitted_at: '2026-10-16T05:00:00Z',
  decided_by_ref: null, decided_at: null, note: null, lines: 676, stores: 4, products: 12, low_confidence_lines: 500, no_forecast_lines: 0, low_confidence_share: 74, model_units: 900, planned_units: 910, target_units: null, adjusted_lines: 3, regions: ['N'], can_review: true,
  data_scope: { source: 'POC_SYNTHETIC', synthetic: true }, ...over,
});

describe('AI analysis', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    asRoles('SALES_PLANNER');
    api.taxonomy.mockResolvedValue({ Cricket: ['Ball', 'Bat'] });
    api.locations.mockResolvedValue({ scope: 'ALL', items: [] });
    api.plans.mockResolvedValue([PLAN()]);
    api.plan.mockResolvedValue(PLAN({ editable: true }));
    api.dashboard.mockResolvedValue(DASH());
    api.reviewQueue.mockResolvedValue({ items: [], statuses: ['SUBMITTED', 'UNDER_REVIEW'], notice: 'n' });
    api.latestAnalysis.mockResolvedValue(ANALYSIS());
  });

  it('asks for a plan first instead of analysing nothing', async () => {
    mount('/sales-planning?tab=insights');
    expect(await screen.findByTestId('ins-no-plan')).toBeInTheDocument();
    expect(api.analyze).not.toHaveBeenCalled();
  });

  it('separates observed facts from labelled hypotheses, shows provenance, evidence, limits and what was not analysed', async () => {
    mount('/sales-planning?tab=insights&plan=p1');
    const card = await screen.findByTestId('finding');
    expect(within(card).getByTestId('finding-facts')).toHaveTextContent('Forecast: 312.6 units/week.');
    expect(within(card).getByTestId('finding-hypotheses')).toHaveTextContent('may include a stock-out');
    expect(card).toHaveTextContent('Hypothesis, not a finding');
    expect(card).toHaveTextContent('Demonstration');
    expect(card).toHaveTextContent('Evidence as of: sales through 2026-10-07');
    expect(card).toHaveTextContent('Assumptions');
    expect(card).toHaveTextContent('Expected impact');
    expect(screen.getByTestId('ins-summary')).toHaveTextContent('AI-worded summary');
    expect(screen.getByTestId('ins-unavailable')).toHaveTextContent('Plan versus actual variance is not available');
  });

  it('runs the analysis with the AI wording switch and shows the result', async () => {
    api.latestAnalysis.mockResolvedValue({ id: null, findings: [], message: 'No analysis has been run for this plan yet.' });
    api.analyze.mockResolvedValue(ANALYSIS({ summary_source: 'RULES', llm: { used: false } }));
    mount('/sales-planning?tab=insights&plan=p1');
    expect(await screen.findByTestId('ins-none')).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('ins-run'));
    await waitFor(() => expect(api.analyze).toHaveBeenCalledWith('p1', expect.objectContaining({ use_llm: true })));
    expect(await screen.findByTestId('finding')).toBeInTheDocument();
    expect(screen.getByTestId('ins-summary')).toHaveTextContent('Rules-based summary');
  });

  it('says when the AI wording was set aside because it contained a number that is not in the findings', async () => {
    api.latestAnalysis.mockResolvedValue(ANALYSIS({ summary_source: 'RULES', llm: { used: true, grounded: false, grounding_fallback: true } }));
    mount('/sales-planning?tab=insights&plan=p1');
    expect(await screen.findByTestId('ins-grounding')).toHaveTextContent('set aside');
  });

  it('saves a proposed manager forecast only when asked, and never approves anything', async () => {
    api.adoptFinding.mockResolvedValue(ANALYSIS({ findings: [FINDING({ decision: 'ADOPTED' })] }));
    mount('/sales-planning?tab=insights&plan=p1');
    const box = await screen.findByTestId('finding-proposal');
    expect(box).toHaveTextContent('10.9');
    expect(box).toHaveTextContent('does not approve anything');
    expect(api.adoptFinding).not.toHaveBeenCalled();
    await userEvent.click(screen.getByTestId('finding-adopt'));
    await waitFor(() => expect(api.adoptFinding).toHaveBeenCalledWith('a1', 'f1'));
    expect(await screen.findByTestId('finding-decision')).toHaveTextContent('Proposal adopted');
    expect(api.transition).not.toHaveBeenCalled();
  });

  it('cannot save a proposal into a plan that is no longer editable', async () => {
    api.plan.mockResolvedValue(PLAN({ editable: false, status: 'APPROVED' }));
    mount('/sales-planning?tab=insights&plan=p1');
    expect(await screen.findByTestId('finding-adopt')).toBeDisabled();
    expect(screen.getByTestId('finding-proposal')).toHaveTextContent('not editable');
  });

  it('opens the evidence in the planning screen with the filters applied', async () => {
    api.lines.mockResolvedValue(PAGE([LINE()]));
    mount('/sales-planning?tab=insights&plan=p1');
    await userEvent.click(await screen.findByTestId('finding-evidence'));
    expect(await screen.findByTestId('pln-tab-workspace')).toHaveAttribute('aria-selected', 'true');
  });
});

describe('Review queue', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    asRoles('CATEGORY_MANAGER');
    api.taxonomy.mockResolvedValue({ Cricket: ['Ball'] });
    api.locations.mockResolvedValue({ scope: 'ALL', items: [{ id: 'N', name: 'North', level: 'REGION', parent_id: null, channel: 'NONE', status: 'ACTIVE', opened_on: null, data_source: 'POC_REFERENCE' }] as never });
    api.plans.mockResolvedValue([PLAN()]);
    api.dashboard.mockResolvedValue(DASH());
    api.reviewQueue.mockResolvedValue({ items: [QITEM(), QITEM({ id: 'q2', name: 'South balls', regions: ['S'] })], statuses: ['SUBMITTED', 'UNDER_REVIEW'], notice: 'Approving records a decision about the plan. It does not order stock, move stock, publish products or change prices.' });
  });

  it('lists the plans waiting for a decision with their regions, data scope and evidence strength', async () => {
    mount('/sales-planning?tab=review');
    const rows = await screen.findAllByTestId('rq-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('North bats');
    expect(rows[0]).toHaveTextContent('Demonstration');
    expect(rows[0]).toHaveTextContent('N');
    expect(rows[0]).toHaveTextContent('676');
    expect(screen.getByTestId('review-queue')).toHaveTextContent('does not order stock');
  });

  it('previews every consequence first, and approving demonstration plans needs an explicit acknowledgement', async () => {
    api.bulkReview.mockImplementation(async (ids, action, _note, preview) => ({
      action, preview, all_eligible: true, applied: !preview, requires_acknowledgement: true, warning: 'These plans are built on DEMONSTRATION data (synthetic stores and sales). Approving them is a demonstration decision, not a real operational one.',
      results: ids.map((id) => ({ plan_id: id, name: id === 'q1' ? 'North bats' : 'South balls', status: 'SUBMITTED' as const, synthetic: true, eligible: true, reason: null, version: 2, lines: 676, consequence: "Freezes 676 line(s) at the manager's forecast. Orders, moves, publishes and prices nothing.", ...(preview ? {} : { applied: true, status_after: 'APPROVED' as const }) })),
    }));
    mount('/sales-planning?tab=review');
    await userEvent.click((await screen.findAllByTestId('rq-pick'))[0]);
    await userEvent.click(screen.getByTestId('rq-approve'));
    expect(await screen.findByTestId('rq-warning')).toHaveTextContent('DEMONSTRATION data');
    expect(screen.getByTestId('rq-preview')).toHaveTextContent('Orders, moves, publishes and prices nothing');
    expect(api.bulkReview).toHaveBeenCalledWith(['q1'], 'APPROVE', undefined, true);
    expect(screen.getByTestId('rq-confirm')).toBeDisabled();                                    // not until the evidence is acknowledged
    await userEvent.click(screen.getByTestId('rq-ack'));
    expect(screen.getByTestId('rq-confirm')).toBeEnabled();
    await userEvent.click(screen.getByTestId('rq-confirm'));
    await waitFor(() => expect(api.bulkReview).toHaveBeenLastCalledWith(['q1'], 'APPROVE', undefined, false, true));
    expect(await screen.findByTestId('rq-result')).toHaveTextContent('applied to 1 plan');
  });

  it('shows why a plan cannot take the action and applies nothing', async () => {
    api.bulkReview.mockResolvedValue({ action: 'APPROVE', preview: true, all_eligible: false, applied: false, requires_acknowledgement: false, results: [{ plan_id: 'q1', name: 'North bats', status: 'SUBMITTED', eligible: false, reason: 'The plan includes 3 store(s) outside your scope' }] });
    mount('/sales-planning?tab=review');
    await userEvent.click((await screen.findAllByTestId('rq-pick'))[0]);
    await userEvent.click(screen.getByTestId('rq-approve'));
    expect(await screen.findByTestId('rq-preview')).toHaveTextContent('outside your scope');
    expect(screen.getByTestId('rq-confirm')).toBeDisabled();
  });

  it('requires a reason to return or reject', async () => {
    api.bulkReview.mockResolvedValue({ action: 'REJECT', preview: true, all_eligible: true, applied: false, requires_acknowledgement: false, results: [{ plan_id: 'q1', name: 'North bats', status: 'SUBMITTED', eligible: true, reason: null, consequence: 'Rejects the plan permanently.' }] });
    mount('/sales-planning?tab=review');
    await userEvent.click((await screen.findAllByTestId('rq-pick'))[0]);
    await userEvent.click(screen.getByTestId('rq-reject'));
    expect(await screen.findByTestId('rq-preview')).toBeInTheDocument();
    expect(screen.getByTestId('rq-confirm')).toBeDisabled();
    await userEvent.type(screen.getByTestId('rq-note'), 'Targets are not credible');
    await waitFor(() => expect(screen.getByTestId('rq-confirm')).toBeEnabled());
  });

  it('is read-only for a planner: the queue is visible but there is no way to select or decide', async () => {
    asRoles('SALES_PLANNER');
    api.reviewQueue.mockResolvedValue({ items: [QITEM({ can_review: false })], statuses: ['SUBMITTED'], notice: 'n' });
    mount('/sales-planning?tab=review');
    expect(await screen.findByTestId('rq-readonly')).toHaveTextContent('only a regional manager');
    expect(screen.queryByTestId('rq-pick')).toBeNull();
    expect(screen.queryByTestId('rq-approve')).toBeNull();
  });

  it('says so when nothing is waiting', async () => {
    api.reviewQueue.mockResolvedValue({ items: [], statuses: ['SUBMITTED'], notice: 'n' });
    mount('/sales-planning?tab=review');
    expect(await screen.findByTestId('rq-empty')).toHaveTextContent('Nothing is waiting for review');
  });
});

describe('Dashboard intelligence', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    asRoles('SALES_PLANNER');
    api.taxonomy.mockResolvedValue({ Cricket: ['Ball'] });
    api.locations.mockResolvedValue({ scope: 'ALL', items: [] });
    api.plans.mockResolvedValue([PLAN()]);
    api.plan.mockResolvedValue(PLAN());
    api.reviewQueue.mockResolvedValue({ items: [], statuses: [], notice: 'n' });
    api.dashboard.mockResolvedValue(DASH({ plan: { id: 'p1', name: 'Q3 plan', status: 'DRAFT', fy_label: 'FY2026-27', version: 1 }, period: { first_week: '2026-10-05', last_week: '2026-12-28', weeks: 13 }, metrics: { model_forecast_units: 100, plan_forecast_units: 100, lines: 10, lines_with_target: 0, lines_with_actual: 0 } }));
    api.performance.mockResolvedValue({ plan: { id: 'p1', name: 'Q3 plan', status: 'DRAFT', source: 'POC_SYNTHETIC' }, grain: 'week', rows: [], total: null, basis: 'MANAGER_OR_MODEL', data_through: '2026-10-07', formulas: {}, notice: '' } as never);
    api.allocations.mockResolvedValue({ items: [], total: 0, page: 0, size: 8, summary: { recs: 0, high_risk: 0, to_order: 0, with_surplus: 0, units_to_order: 0, products_to_order: 0, generated_at: null, transfers: 0, transfer_units: 0 }, transfers: [] });
    api.regional.mockResolvedValue({ weeks: { first: '2026-04-06', last: '2026-10-05' }, matrix: [{ region: 'N', sport: 'Cricket', category: 'Bat', net_units: 120, stores: 3, store_weeks: 60, weeks_stockout: 0 }, { region: 'S', sport: 'Cricket', category: 'Bat', net_units: 60, stores: 3, store_weeks: 60, weeks_stockout: 0 }], store_ranking: [], top_products: [], insights: [], notice: 'n' });
  });

  it('shows the latest analysis findings, an honest unavailable variance, the stock-risk gap and a regional heatmap', async () => {
    api.latestAnalysis.mockResolvedValue(ANALYSIS());
    mount('/sales-planning?plan=p1');
    expect(await screen.findByTestId('dash-finding')).toHaveTextContent('Cricket / Ball');
    expect(await screen.findByTestId('dash-variance-unavailable')).toHaveTextContent('No week of this plan has closed');
    expect(await screen.findByTestId('dash-risk-unavailable')).toHaveTextContent('generate the recommendations');
    expect((await screen.findAllByTestId('heat-cell')).length).toBe(2);
  });

  it('offers to analyse when no analysis exists', async () => {
    api.latestAnalysis.mockResolvedValue({ id: null, findings: [], message: 'none' });
    mount('/sales-planning?plan=p1');
    expect(await screen.findByTestId('dash-no-analysis')).toBeInTheDocument();
    expect(screen.getByTestId('dash-open-insights')).toHaveTextContent('Analyse plan');
  });
});