import axios from 'axios';
import { installAuth } from '@/api/client';

/** Sales & Inventory Planning lives in the AI service (proxied at /ai-api) and shares the bearer token. The service enforces the roles and each user's store scope on every route. */
const client = installAuth(axios.create({ baseURL: '/ai-api/v1/planning', headers: { 'Content-Type': 'application/json' }, timeout: 180_000 }));

export const ALL_SCOPE_ROLES = ['ADMIN', 'ROLE_ADMIN', 'SUPER_ADMIN', 'CATEGORY_MANAGER', 'ROLE_CATEGORY_MANAGER', 'SALES_PLANNER', 'ROLE_SALES_PLANNER'];
export const PLANNER_ROLES = [...ALL_SCOPE_ROLES, 'REGIONAL_MANAGER', 'ROLE_REGIONAL_MANAGER', 'STORE_MANAGER', 'ROLE_STORE_MANAGER'];
export const APPROVER_ROLES = ['ADMIN', 'ROLE_ADMIN', 'SUPER_ADMIN', 'CATEGORY_MANAGER', 'ROLE_CATEGORY_MANAGER', 'REGIONAL_MANAGER', 'ROLE_REGIONAL_MANAGER'];
const has = (roles: string[] | undefined, set: string[]) => !!roles?.some((r) => set.includes(r));
export const isPlanner = (roles?: string[]) => has(roles, PLANNER_ROLES);
export const isApprover = (roles?: string[]) => has(roles, APPROVER_ROLES);
export const isAdmin = (roles?: string[]) => has(roles, ['ADMIN', 'ROLE_ADMIN', 'SUPER_ADMIN']);
export const seesAll = (roles?: string[]) => has(roles, ALL_SCOPE_ROLES);

export type Source = 'POC_SYNTHETIC' | 'DSPORTS_LIVE';
export type PlanStatus = 'DRAFT' | 'SUBMITTED' | 'UNDER_REVIEW' | 'APPROVED' | 'PUBLISHED' | 'REJECTED' | 'RETURNED' | 'SUPERSEDED';
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';
export const REASON_CODES = ['PROMOTION', 'LOCAL_EVENT', 'SUPPLY_CONSTRAINT', 'COMPETITOR_ACTIVITY', 'STORE_CLOSURE', 'SPECIAL_ORDER', 'DATA_CORRECTION', 'OTHER'] as const;
export const ADJUSTMENT_REASONS = ['DATA_CORRECTION', 'RETURNS_NOT_RECORDED', 'DUPLICATE_ORDER', 'MISSING_ORDERS', 'WRONG_PRODUCT_MAPPING', 'OTHER'] as const;

export interface Filters { sport?: string; category?: string; region?: string; zone?: string; store?: string[]; q?: string; product_id?: string; week_from?: string; week_to?: string; has_target?: boolean; has_adjustment?: boolean; confidence?: string }
export interface Capability { key: string; label: string; status: 'RELIABLE' | 'LIMITED_ESTIMATE' | 'INSUFFICIENT' | 'DEMONSTRATION_ONLY' | 'UNAVAILABLE'; evidence: string; note: string }
export interface Readiness {
  capabilities: Capability[]; forecasting: Record<Source, { status: string; reason: string; run: string | null }>; live: Record<string, number | string | null>; synthetic: Record<string, number | string | null>;
  imported: Record<string, number | string | null>; last_ingest: Record<string, unknown> | null; reconciliation: { reconciled: boolean; lines: number; excluded: number; line_net: number; week_net: number };
  thresholds: { min_history_weeks: number; min_eval_observations: number; min_eval_units: number }; conventions: Record<string, string>;
}
export interface Location { id: string; name: string; level: 'REGION' | 'ZONE' | 'STORE' | 'WAREHOUSE'; parent_id: string | null; channel: string; status: string; opened_on: string | null; data_source: string }
export interface Plan {
  id: string; name: string; fy: number; fy_label?: string; first_week: string; last_week: string; source: Source; status: PlanStatus; version: number; revision: number; parent_plan_id: string | null; created_by: string;
  created_at: string; submitted_by: string | null; note: string | null; line_count?: number; adjusted_lines?: number; target_lines?: number; forecast_run_id: string | null; scope: Record<string, unknown>;
  counts?: Record<string, number>; allowed_next?: PlanStatus[]; editable?: boolean; weeks?: { week_start: string; week_end: string; fy_label: string; fy_quarter: number; month: string }[];
  data_scope?: { source: Source; synthetic: boolean; label: string };
}
export interface Line {
  product_id: string; product_name: string; sku: string; sport: string; category: string; location_id: string; store_name: string; region: string; zone: string; week_start: string; week_closed: boolean;
  model_forecast: number | null; model_lo: number | null; model_hi: number | null; confidence: Confidence | null; manager_forecast: number | null; target_units: number | null; approved_forecast: number | null;
  adj_reason: string | null; adj_note: string | null; line_version: number; latest_forecast: number | null; actual_units: number | null; authoritative_units: number | null; adjustment_units: number;
  actual_source: string | null; sellable_stock: number | null; reserved_stock: number | null; incoming_stock: number | null;
}
export interface LinesPage { items: Line[]; total: number; page: number; size: number; plan: { id: string; status: PlanStatus; version: number; source: Source; editable: boolean } }
export interface LineEdit { product_id: string; location_id: string; week_start: string; expected_line_version: number; target_units?: number | null; manager_forecast?: number | null; reason_code?: string; note?: string }
export interface Conflict { product_id: string; location_id: string; week_start: string; code: string; message: string; current_version?: number }
export interface ReportRow {
  gkey: string; lines: number; with_actual: number; with_forecast: number; with_target: number; actual: number | null; forecast: number | null; target: number | null; model: number | null; a_f: number | null; f_a: number | null;
  a_t: number | null; t_a: number | null; adjustments: number | null; forecast_variance: number | null; forecast_variance_pct: number | null; target_variance: number | null; target_attainment_pct: number | null; first_week: string;
}
export interface Performance { plan: { id: string; name: string; status: PlanStatus; source: Source }; grain: string; rows: ReportRow[]; total: ReportRow | null; basis: string; data_through: string | null; formulas: Record<string, string>; notice: string }
export interface Investigation {
  comparison: { actual: number | null; forecast: number | null; target: number | null; forecast_variance: number | null; forecast_variance_pct: number | null; target_variance: number | null; target_attainment_pct: number | null };
  flagged: boolean; facts: string[]; hypotheses: string[]; limits: string[]; line: Line; thresholds: { percent: number; units: number };
}
export interface EvalRow { scope: string; scope_key: string; model: string; horizon_group: string; n_obs: number; n_series: number; actual_units: number; wape: number | null; bias: number | null; mae: number | null; baseline_wape: number | null; sufficient: boolean; note: string | null; better_than_baseline: boolean | null }
export interface Evaluation {
  run: { id: string; source: Source; as_of_week: string; first_week: string; horizon_weeks: number; model_version: string; n_series: number; n_ok: number; n_fallback: number; n_insufficient: number; created_at: string };
  features: { used: string[]; not_used: string[]; data_scope: string; history_weeks: number; data_through: string }; method_choice: Record<string, { n_obs: number; wape?: Record<string, number | null>; chosen?: string }> | null; chosen: Record<string, string> | null;
  rows: EvalRow[]; notice: string; baseline: string;
}
export interface ForecastItem { product_id: string; product_name: string; sku: string; sport: string; category: string; location_id: string; store_name: string; region: string; method: string; bucket: string | null; confidence: Confidence; status: string; reason: string | null; history_weeks: number; weeks: { week_start: string; forecast: number; lo: number | null; hi: number | null; baseline: number | null }[] }
export interface ForecastPage { items: ForecastItem[]; total: number; page: number; size: number; run: { id: string; source: Source; as_of_week: string; first_week: string; horizon_weeks: number; model_version: string; created_at: string } }
export interface Alloc {
  id: string; product_id: string; product_name: string; sku: string; sport: string; category: string; location_id: string; store_name: string; region: string; action: string; risk_level: string | null; required_stock: number | null;
  on_hand: number; reserved: number; incoming: number; shortage: number; surplus: number; recommended_qty: number; order_by: string | null; arrival_date: string | null; safety_stock: number | null; coverage_days: number | null;
  confidence: string | null; reason: string; status: 'DRAFT' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED'; detail: Record<string, unknown>;
}
export interface Transfer { id: string; product_name: string; from_location: string; to_location: string; from_name: string; to_name: string; qty: number; lead_days: number; arrival_date: string; reason: string; status: string; avoids_order_qty: number | null }
export interface AllocSummary { recs: number; high_risk: number; to_order: number; with_surplus: number; units_to_order: number; products_to_order: number; generated_at: string | null; transfers: number; transfer_units: number }
export interface AllocPage { items: Alloc[]; total: number; page: number; size: number; summary: AllocSummary; transfers: Transfer[] }
export interface ScenarioSummary { baseline: Record<string, number | null>; scenario: Record<string, number | null>; delta: Record<string, number | null> }
export interface Scenario { id: string; created_at: string; name: string; kind: string; params: Record<string, unknown>; status: 'SIMULATION' | 'ADOPTED'; summary?: ScenarioSummary; results?: { summary: ScenarioSummary; items: { name: string; scenario: Record<string, unknown>; baseline: Record<string, unknown> }[]; limits: string[]; notice: string; skipped: { name: string; why: string }[] }; plan_id: string | null }
export interface Pilot { id: string; product_name: string; opportunity_id: string; status: string; stores: { location_id: string; region: string; weekly_lo: number; weekly_mid: number; weekly_hi: number; pilot_qty: number }[]; duration_weeks: number; estimate: { status: string; confidence: string; reason: string; assumptions: string[]; comparables: string[]; category_options?: string[]; hint?: string }; observations: { week_start: string; location_id: string; units: number; available_days: number }[] }
export interface Dashboard {
  source: Source; freshness: { data_through: string | null; last_forecast: string | null; forecast_as_of_week: string | null; last_ingest: Record<string, unknown> | null }; data_scope: { source: Source; synthetic: boolean };
  plan: { id: string; name: string; status: PlanStatus; fy_label: string; version: number } | null; period: { first_week: string | null; last_week: string | null; weeks: number } | null; message?: string;
  metrics: { model_forecast_units?: number | null; plan_forecast_units?: number | null; target_units?: number | null; actual_units?: number | null; target_attainment_pct?: number | null; forecast_variance?: number | null; forecast_variance_pct?: number | null;
    lines?: number; lines_with_actual?: number; lines_with_target?: number; forecast_accuracy?: { wape: number | null; bias: number | null; baseline_wape: number | null; n_obs: number; sufficient: boolean; basis: string } | null };
  queues: { plans_awaiting_approval?: number; plans_in_draft?: number; forecasts_low_confidence?: number | null; series_without_forecast?: number | null; stores_at_stockout_risk?: number | null; products_to_replenish?: number | null; units_to_order?: number | null; stock_lines_with_surplus?: number | null };
}
export interface Regional {
  weeks: { first: string; last: string }; matrix: { region: string; sport: string; category: string; net_units: number; stores: number; store_weeks: number; weeks_stockout: number }[];
  store_ranking: { rank: number; location_id: string; store_name: string; region: string; net_units: number; vs_region_median_pct: number | null; weeks_stockout: number; store_weeks: number }[];
  top_products: { product_id: string; name: string; sport: string; category: string; net_units: number; stores: number }[];
  insights: { region: string; sport: string; category: string; relative_difference_pct: number; confidence: 'LOW' | 'MEDIUM'; statement: string; net_units: number; store_weeks: number }[]; notice: string;
}
export interface ActualRow { gkey: string; week_start: string; week_closed: boolean; gross_units: number; cancelled_units: number; returned_units: number; net_units: number; net_revenue: number; stockout_days: number; sources: string[] }
export interface Adjustment { id: string; product_id: string; product_name?: string; location_id: string; store_name?: string; week_start: string; delta_units: number; reason_code: string; note: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; requested_by: string }
export interface Finding {
  id: string; kind: string; title: string; severity: 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'; statement: string; facts: string[]; hypotheses: string[]; scope: Record<string, string>;
  evidence: { label: string; tab: string; params?: Record<string, string> }[]; provenance: string[]; freshness: { data_through: string | null; forecast_generated: string | null; plan_version: number | null };
  assumptions: string[]; confidence: Confidence; impact: string | null; risks: string[]; next_action: string; decision: 'ADOPTED' | 'DISMISSED' | null;
  proposal: null | { kind: 'MANAGER_FORECAST'; reason_code: string; weeks: string[]; basis: string; note: string; items: { product_id: string; location_id: string; name: string; store: string; model_per_week: number | null; recent_per_week: number | null; proposed_per_week: number | null }[] };
}
export interface Analysis {
  id: string | null; plan_id?: string; created_at?: string; source?: Source; data_through?: string | null; model_version?: string | null; plan_version?: number; summary?: string; summary_source?: 'RULES' | 'LLM';
  llm?: { used?: boolean; model?: string; tokensIn?: number; tokensOut?: number; grounded?: boolean; grounding_fallback?: boolean; error?: string }; findings: Finding[];
  unavailable?: { analysis: string; why: string; needs: string }[]; data_scope?: { source: Source; synthetic: boolean }; notice?: string; message?: string;
}
export interface ReviewItem {
  id: string; name: string; status: PlanStatus; source: Source; version: number; fy_label: string; first_week: string; last_week: string; scope: Record<string, unknown>; revision: number; submitted_by_ref: string | null; submitted_at: string | null;
  decided_by_ref: string | null; decided_at: string | null; note: string | null; lines: number; stores: number; products: number; low_confidence_lines: number; no_forecast_lines: number; low_confidence_share: number;
  model_units: number | null; planned_units: number | null; target_units: number | null; adjusted_lines: number; regions: string[]; can_review: boolean; data_scope: { source: Source; synthetic: boolean };
}
export interface BreakdownRow { gkey: string; label: string; lines: number; products: number; model_units: number | null; planned_units: number | null; target_units: number | null; adjusted_lines: number; low_confidence_lines: number; no_forecast_lines: number }
export interface BulkResult { action: string; preview: boolean; all_eligible: boolean; applied: boolean; requires_acknowledgement?: boolean; warning?: string | null; results: { plan_id: string; name?: string; status?: PlanStatus; synthetic?: boolean; eligible: boolean; reason: string | null; consequence?: string; lines?: number; applied?: boolean; status_after?: PlanStatus; error?: string }[] }export interface AskAnswer { intent: string; answer: string; evidence: { label: string; link: string }[]; limitations: string[] }

const clean = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== '' && v !== null && !(Array.isArray(v) && v.length === 0)));
const P = { paramsSerializer: { indexes: null } } as const;
const flt = (f: Filters) => clean({ ...f });

export const planningApi = {
  analyze: async (planId: string, f: Filters & { use_llm?: boolean }) => (await client.post<Analysis>(`/plans/${planId}/analysis`, clean({ sport: f.sport, category: f.category, region: f.region, zone: f.zone, stores: f.store, product_id: f.product_id, use_llm: f.use_llm ?? true }))).data,
  latestAnalysis: async (planId: string) => (await client.get<Analysis>(`/plans/${planId}/analysis`)).data,
  adoptFinding: async (analysisId: string, findingId: string) => (await client.post<Analysis>(`/analyses/${analysisId}/findings/${findingId}/adopt`)).data,
  dismissFinding: async (analysisId: string, findingId: string) => (await client.post<Analysis>(`/analyses/${analysisId}/findings/${findingId}/dismiss`)).data,
  reviewQueue: async (status?: string, region?: string) => (await client.get<{ items: ReviewItem[]; statuses: string[]; notice: string }>('/review/queue', { params: clean({ status, region }) })).data,
  breakdown: async (planId: string, by: 'region' | 'zone' | 'store') => (await client.get<{ plan: { id: string; name: string; status: PlanStatus; source: Source }; by: string; rows: BreakdownRow[]; data_scope: { source: Source; synthetic: boolean } }>(`/plans/${planId}/breakdown`, { params: { by } })).data,
  bulkReview: async (planIds: string[], action: 'APPROVE' | 'RETURN' | 'REJECT', note: string | undefined, preview: boolean, acknowledgeSynthetic = false) => (await client.post<BulkResult>('/review/bulk', { plan_ids: planIds, action, preview, ...(note ? { note } : {}), ...(acknowledgeSynthetic ? { acknowledge_synthetic: true } : {}) })).data,  readiness: async () => (await client.get<Readiness>('/readiness')).data,
  locations: async () => (await client.get<{ items: Location[]; scope: string }>('/locations')).data,
  taxonomy: async () => (await client.get<{ sports: Record<string, string[]> }>('/taxonomy')).data.sports,
  seedDemo: async () => (await client.post<Record<string, unknown>>('/demo/seed')).data,
  ingest: async (mode: 'INCREMENTAL' | 'BACKFILL') => (await client.post<Record<string, unknown>>('/ingest', { mode })).data,
  runForecast: async (scope: Source) => (await client.post<Record<string, unknown>>('/forecast/runs', { scope })).data,
  forecastOverview: async (source: Source) => (await client.get<{ run: Record<string, unknown> | null; counts?: Record<string, number>; message?: string }>('/forecast/overview', { params: { source } })).data,
  forecastSeries: async (source: Source, f: Filters, page: number, size: number, status?: string) => (await client.get<ForecastPage>('/forecast/series', { ...P, params: clean({ source, ...flt(f), page, size, status }) })).data,
  evaluation: async (source: Source, scope?: string, horizon?: string) => (await client.get<Evaluation>('/forecast/evaluation', { params: clean({ source, scope, horizon }) })).data,
  plans: async (status?: string) => (await client.get<{ items: Plan[] }>('/plans', { params: clean({ status }) })).data.items,
  plan: async (id: string) => (await client.get<Plan>(`/plans/${id}`)).data,
  createPlan: async (body: Record<string, unknown>) => (await client.post<Plan>('/plans', body)).data,
  lines: async (id: string, f: Filters, sort: string, page: number, size: number) => (await client.get<LinesPage>(`/plans/${id}/lines`, { ...P, params: clean({ ...flt(f), sort, page, size }) })).data,
  editLines: async (id: string, edits: LineEdit[]) => (await client.patch<{ changed: number }>(`/plans/${id}/lines`, { edits })).data,
  growthTargets: async (id: string, body: { growth_pct: number; basis: 'MODEL' | 'MANAGER'; overwrite: boolean; filters: Filters }) =>
    (await client.post<{ lines_set: number; lines_without_baseline: number }>(`/plans/${id}/growth-targets`, { ...body, filters: { sport: body.filters.sport, category: body.filters.category, region: body.filters.region, zone: body.filters.zone, stores: body.filters.store, product_id: body.filters.product_id } })).data,
  transition: async (id: string, status: PlanStatus, reason: string | undefined, expectedVersion: number) => (await client.post<Plan>(`/plans/${id}/transition`, { status, reason, expected_version: expectedVersion })).data,
  revise: async (id: string, name?: string) => (await client.post<Plan>(`/plans/${id}/revise`, { name })).data,
  impact: async (id: string, productId: string, locationId: string) => (await client.get<{ available: boolean; reason?: string; model?: Record<string, unknown>; plan?: Record<string, unknown>; stock?: Record<string, number>; note?: string }>(`/plans/${id}/impact`, { params: { product_id: productId, location_id: locationId } })).data,
  performance: async (id: string, grain: string, f: Filters, basis: 'PLAN' | 'MODEL') => (await client.get<Performance>(`/plans/${id}/performance`, { ...P, params: clean({ grain, basis, ...flt(f) }) })).data,
  investigate: async (id: string, productId: string, locationId: string, week: string) => (await client.get<Investigation>(`/plans/${id}/investigate`, { params: { product_id: productId, location_id: locationId, week_start: week } })).data,
  planAudit: async (id: string) => (await client.get<{ items: { id: number; ts: string; actor_hash: string; entity: string; action: string; reason: string | null; roles: string | null }[] }>(`/plans/${id}/audit`)).data.items,
  actuals: async (source: Source, from: string, to: string, dim: string, f: Filters) => (await client.get<{ rows: ActualRow[]; data_through: string | null; notice: string }>('/actuals', { ...P, params: clean({ source, week_from: from, week_to: to, dim, ...flt(f) }) })).data,
  importActuals: async (body: Record<string, unknown>) => (await client.post<{ rows_applied: number; already_applied: boolean }>('/actuals/import', body)).data,
  adjust: async (body: Record<string, unknown>) => (await client.post<Adjustment>('/actuals/adjustments', body)).data,
  adjustments: async (status?: string) => (await client.get<{ items: Adjustment[] }>('/actuals/adjustments', { params: clean({ status }) })).data.items,
  decideAdjustment: async (id: string, approve: boolean, note?: string) => (await client.post<Adjustment>(`/actuals/adjustments/${id}/decision`, { approve, note })).data,
  generateAllocation: async (id: string, f: Filters) => (await client.post<{ recommendations: number; transfers: number; summary: AllocSummary; notice: string }>(`/plans/${id}/allocations/generate`, null, { ...P, params: clean(flt(f)) })).data,
  allocations: async (id: string, f: Filters & { risk?: string; action?: string; only_actions?: boolean }, page: number, size: number) => (await client.get<AllocPage>(`/plans/${id}/allocations`, { ...P, params: clean({ ...flt(f), risk: f.risk, action: f.action, only_actions: f.only_actions, page, size }) })).data,
  decideAllocation: async (id: string, approve: boolean) => (await client.post<Alloc & { notice: string }>(`/allocations/${id}/decision`, { approve })).data,
  decideTransfer: async (id: string, approve: boolean) => (await client.post<Transfer & { notice: string }>(`/transfers/${id}/decision`, { approve })).data,
  scenarios: async (planId?: string) => (await client.get<{ items: Scenario[] }>('/scenarios', { params: clean({ plan_id: planId }) })).data.items,
  scenario: async (id: string) => (await client.get<Scenario>(`/scenarios/${id}`)).data,
  createScenario: async (body: Record<string, unknown>) => (await client.post<Scenario>('/scenarios', body)).data,
  compareScenarios: async (ids: string[]) => (await client.post<{ scenarios: { id: string; name: string; kind: string; params: Record<string, unknown>; summary: ScenarioSummary; limits: string[] }[]; notice: string }>('/scenarios/compare', { ids })).data,
  adoptScenario: async (id: string) => (await client.post<{ lines_set: number }>(`/scenarios/${id}/adopt`)).data,
  pilots: async () => (await client.get<{ items: Pilot[] }>('/pilots')).data.items,
  proposePilot: async (body: Record<string, unknown>) => (await client.post<Pilot>('/pilots', body)).data,
  pilotDecision: async (id: string, status: string, note?: string) => (await client.post<Pilot>(`/pilots/${id}/decision`, { status, note })).data,
  pilotObserve: async (id: string, observations: { week_start: string; location_id: string; units: number; available_days: number }[]) =>
    (await client.post<{ assessment: { decision: string; reason: string; sufficient: boolean } }>(`/pilots/${id}/observations`, { observations })).data,
  dashboard: async (source: Source, planId: string | undefined, f: Filters) => (await client.get<Dashboard>('/dashboard', { ...P, params: clean({ source, plan_id: planId, ...flt(f) }) })).data,
  regional: async (source: Source, weeks: number, region?: string) => (await client.get<Regional>('/analysis/regional', { params: clean({ source, weeks, region }) })).data,
  ask: async (question: string) => (await client.post<AskAnswer>('/ask', { question })).data,
};

/** The service answers with { code, message, details: { info: { conflicts } } }: the page shows the message and, for a conflict, which lines changed. */
export const planningError = (err: unknown): string => {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as { message?: string; code?: string; details?: { info?: { errors?: { row: number; errors: string[] }[] } }; validationErrors?: { field: string; message: string }[] } | undefined;
    if (!err.response) return 'The planning service could not be reached. The rest of the shop is not affected.';
    if (body?.details?.info?.errors?.length) return `${body.message}. ${body.details.info.errors.slice(0, 3).map((e) => `Row ${e.row}: ${e.errors.join('; ')}`).join(' | ')}`;
    if (body?.validationErrors?.length) return `${body.message}: ${body.validationErrors.map((v) => `${v.field} ${v.message}`).slice(0, 3).join('; ')}`;
    if (body?.message) return body.message;
  }
  return 'That could not be completed.';
};
export const conflictsOf = (err: unknown): Conflict[] => {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as { code?: string; details?: { info?: { conflicts?: Conflict[] } } } | undefined;
    if (body?.code === 'EDIT_CONFLICT') return body.details?.info?.conflicts ?? [];
  }
  return [];
};
