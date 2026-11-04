/** Inventory Intelligence (internal, business-facing). Mirrors the AI service's /v1/inventory responses. Every payload carries a data source label. */
export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW';

export interface InvAssessment {
  product_id: string;
  name: string;
  sku: string;
  sport: string;
  category: string;
  status: string;
  reason: string | null;
  on_hand: number | null;
  incoming: number | null;
  lead_time_days: number | null;
  daily_demand: number | null;
  forecast_7: number | null;
  forecast_30: number | null;
  forecast_90: number | null;
  days_of_cover: number | null;
  stockout_date: string | null;
  p_stockout_lt: number | null;
  safety_stock: number | null;
  reorder_point: number | null;
  risk_level: RiskLevel | null;
  reorder_qty: number | null;
  order_by: string | null;
  arrival_date: string | null;
  priority: string | null;
  excess_units: number | null;
  excess_value: number | null;
  coverage_days: number | null;
  overstock_level: string | null;
  health_class: string | null;
  age_days: number | null;
  supplier?: string | null;
  moq?: number | null;
  price?: number | null;
  cost?: number | null;
  detail: {
    action?: 'ORDER' | 'EXPEDITE_INCOMING' | 'NONE';
    demand_during_lead_time?: number;
    target_level?: number;
    incoming_arrives_after_stockout?: boolean;
    next_arrival_in_days?: number | null;
    demand_trend_pct?: number | null;
    event?: { id: string; name: string; uplift: number; uplift_range: number[]; basis: string };
  };
  projected_shortage_by_event_end?: number | null;
  event_window_demand?: number;
  baseline_window_demand?: number;
}

export interface InvModelInfo {
  run_id: string;
  model_name: string;
  model_version: string;
  as_of: string;
  training_start: string;
  training_end: string;
  horizon_days: number;
  series: number;
  created_at: string;
  data_source: string;
  note: string;
  metrics: {
    selected: string;
    baseline: string;
    holdout: Record<string, { wape: number; mae: number; rmse: number; mape: number | null; bias: number; n_days: number; sum_wape: Record<string, number | null> }>;
    selection: Record<string, { wape: number }>;
    holdout_improvement_vs_baseline_pct: number | null;
    interval_coverage_holdout: Record<string, number | null>;
    note: string;
  };
}

export interface InvEvent {
  id: string;
  name: string;
  event_type: string;
  sport: string;
  start_date: string;
  end_date: string;
  popularity: number;
  regions: Record<string, number>;
  status: 'UPCOMING' | 'PAST';
  starts_in_days: number;
  data_source: string;
}

export interface InvOverview {
  total: number;
  healthy: number;
  watch: number;
  slow_only: number;
  critical_aging: number;
  at_risk: number;
  stockout_high: number;
  stockout_medium: number;
  overstock: number;
  slow_moving: number;
  aging_over_90: number;
  excess_units: number;
  excess_value: number;
  reorder_units: number;
  replenish_skus: number;
  inventory_value_at_cost: number;
  inventory_units: number;
  clearance_candidates: number;
  insufficient: number;
  upcoming_events: InvEvent[];
  model: InvModelInfo;
  data_source: string;
  data_note: string;
}

export interface ForecastPoint { day: string; mean: number; lo: number; hi: number }
export interface HistoryPoint { day: string; units: number }
export interface InvForecast {
  product_id?: string;
  name?: string;
  sport?: string | null;
  category?: string | null;
  status?: string;
  reason?: string;
  products?: number;
  as_of: string;
  model_name: string;
  model_version: string;
  daily: ForecastPoint[];
  history: HistoryPoint[];
  totals: Record<string, number | { mean: number }>;
  assessment?: InvAssessment | null;
  data_source: string;
}

export interface RegionRow { region: string; name: string; demand_30d: number; demand_share_pct: number; stock: number; days_of_cover: number | null; growth_pct: number | null }
export interface InvRegions {
  regions: RegionRow[];
  transfers: { from: string; to: string; units: number }[];
  note: string;
  selection: { products: number; sport?: string | null; category?: string | null; excess_only?: boolean };
}

export interface ScenarioSummary {
  discount_pct: number;
  units_sold: number;
  units_low: number;
  units_high: number;
  revenue: number;
  margin: number;
  ending_stock: number;
  inventory_reduction_units: number;
  inventory_reduction_value: number;
  products_at_stockout_risk: number;
  min_unit_margin_pct: number | null;
  net_benefit: number;
}
export interface InvScenario {
  duration_days: number;
  min_margin_pct: number;
  products: number;
  scenarios: ScenarioSummary[];
  recommended_discount_pct: number | null;
  selective: { discounted_products: number; products: number; units_sold: number; revenue: number; margin: number; inventory_reduction_units: number;
               inventory_reduction_value: number; net_benefit: number; discount_by_product: { name: string; discount_pct: number }[] } | null;
  elasticity: { category: string; elasticity: number; range: number[]; pull_forward: number; basis: string; n_promo_days: number }[];
  assumptions: string[];
  recommendation_status: string;
  data_source: string;
}

export interface ClearanceCandidate {
  product_id: string;
  name: string;
  sport: string;
  category: string;
  on_hand: number;
  age_days: number;
  forecast_90: number;
  excess_units: number;
  excess_value: number;
  coverage_days: number;
  health_class: string;
  recommended_discount_pct: number | null;
  expected_units_sold_30d: number | null;
  reason: string;
}

export interface InvEventImpact {
  event: InvEvent;
  impact: { category: string; uplift: number; uplift_lo: number; uplift_hi: number; n_events: number; n_products: number; basis: string }[];
  readiness: InvAssessment[];
  assumption_note: string;
}

export interface InvDataQuality {
  checks: { check: string; status: string; detail: string }[];
  issue_count: number;
  disclaimer: string;
}

export type BlockType =
  | 'overview' | 'risk_table' | 'replenishment_table' | 'overstock_table' | 'clearance_table' | 'forecast_chart' | 'scenario_comparison'
  | 'event_readiness' | 'region_table' | 'sources' | 'quality' | 'notice';

// The payload shape depends on `type` and is defined by the AI service; each renderer narrows it. A per-block union would duplicate the server contract.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export interface InvBlock { type: BlockType; title: string; data: any }

export interface InvCopilotResponse {
  conversationId: string;
  intent: string;
  status: string;
  message: string;
  headline: string;
  blocks: InvBlock[];
  tools: { tool: string; status: string; latency_ms: number }[];
  sources: { source: string; title: string; page: number; section: string | null }[];
  warnings: string[];
  dataSource: string;
  recommendationLabel: string | null;
  llm: { used?: boolean; model?: string; tokensIn?: number; tokensOut?: number; latency_ms?: number; grounding_fallback?: boolean; error?: string };
  suggestions: string[];
  provenance?: { run_id: string; model_name: string; model_version: string; as_of: string; training_end: string };
}
