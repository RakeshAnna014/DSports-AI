import axios from 'axios';
import { installAuth } from '@/api/client';

/** Product & Supplier Intelligence lives in the AI service (proxied at /ai-api) and shares the bearer token. The service enforces the buyer roles on every route. */
const client = installAuth(axios.create({ baseURL: '/ai-api/v1/sourcing', headers: { 'Content-Type': 'application/json' }, timeout: 120_000 }));

export const BUYER_ROLES = ['ADMIN', 'ROLE_ADMIN', 'PROCUREMENT_BUYER', 'ROLE_PROCUREMENT_BUYER', 'CATEGORY_MANAGER', 'ROLE_CATEGORY_MANAGER'];
export const APPROVER_ROLES = ['ADMIN', 'ROLE_ADMIN', 'CATEGORY_MANAGER', 'ROLE_CATEGORY_MANAGER'];
export const isBuyer = (roles?: string[]) => !!roles?.some((r) => BUYER_ROLES.includes(r));
export const isApprover = (roles?: string[]) => !!roles?.some((r) => APPROVER_ROLES.includes(r));

export type OppStatus = 'DISCOVERED' | 'UNDER_REVIEW' | 'SHORTLISTED' | 'APPROVED_OPPORTUNITY' | 'SUPPLIER_RESEARCH' | 'QUOTE_COMPARISON' | 'APPROVED_FOR_ONBOARDING' | 'REJECTED' | 'ON_HOLD' | 'NEEDS_MORE_EVIDENCE';
export type Origin = 'LIVE_RESEARCH' | 'DEMAND_SIGNAL' | 'MANUAL' | 'DEMO';
export type Provenance = 'LIVE_EXTERNAL_SOURCE' | 'BUYER_ENTERED' | 'CUSTOMER_DEMAND_SIGNAL' | 'DEMO_FIXTURE' | 'TEST_FIXTURE' | 'UNKNOWN_PROVENANCE';
export type SourceKind = 'PUBLIC_LISTING' | 'QUOTE_ENTERED' | 'VERIFIED' | 'AI_ESTIMATE';
export type MatchLevel = 'EXACT_MODEL' | 'LIKELY_MATCH' | 'SIMILAR_REFERENCE' | 'UNVERIFIED';
export type SupplierStage = 'DISCOVERED' | 'WEBSITE_CHECKED' | 'IDENTITY_VERIFIED' | 'BRAND_AUTHORISATION_VERIFIED';

export interface CatalogueMatch { level: 'EXACT' | 'PROBABLE_DUPLICATE' | 'SIMILAR' | 'REVIEW' | 'NONE'; reason: string; product_id?: string | null; score?: number }
export interface Opportunity {
  id: string; name: string; brand?: string | null; model?: string | null; gtin?: string | null; mfr_sku?: string | null; manufacturer?: string | null; sport?: string | null; category?: string | null;
  description?: string | null; specs: Record<string, { label: string; value: string }>; trend_note?: string | null; relevance?: string | null; status: OppStatus; origin: Origin; catalogue_match: CatalogueMatch;
  provenance: Provenance; record_kind: 'PRODUCT_CANDIDATE' | 'DEMAND_GAP'; source_title?: string | null; product_url?: string | null; linked_candidate_id?: string | null;
  possible_dupes: { id: string; name: string; score: number }[]; unanswered: string[]; selling_price?: number | null; first_seen: string; last_verified?: string | null; version: number;
  opp_score?: number | null; risk?: string | null; margin?: number | null; demand_band?: string | null; confidence?: string | null; score_status?: string | null;
  image_url?: string | null; image_match_level?: MatchLevel | null; image_status?: string | null; image_source_url?: string | null; image_attribution?: string | null;
  calc_quote_id?: string | null; calc_basis?: CalcBasis | null; quote_count?: number;
}
export interface CalcBasis { opportunity_id?: string; quote_id?: string; supplier?: string; quantity?: number; currency?: string; unit_price?: number; label?: string; price_includes_tax?: boolean; calculated_at?: string }
export interface ProductImage {
  id: string; url: string; match_level: MatchLevel; status: 'VALID' | 'BROKEN' | 'BLOCKED' | 'UNSUPPORTED' | 'UNCHECKED'; status_detail?: string | null; source_type: string; source_page_url?: string | null;
  attribution?: string | null; rights_note?: string | null; is_primary: boolean; reported: boolean; reported_reason?: string | null; retrieved_at?: string | null; checked_at?: string | null; note?: string | null;
}
export interface ReadinessItem { code: string; label: string; ok: boolean; blocking?: boolean; hint?: string }
export interface Readiness { applicable: boolean; ready: boolean; missing: string[]; items: ReadinessItem[]; summary: string; blocking_count?: number }
export interface Evidence { id: number; kind: string; source_label: string; source_type?: string | null; url?: string | null; claim: string; excerpt?: string | null; retrieved_at: string; verified_at?: string | null }
export interface Quote {
  id: string; supplier_id: string; supplier_name: string; supplier_type?: string; supplier_verification?: string; equivalence: string; equivalence_note?: string | null; offered_product: string;
  model?: string | null; currency: string; unit_price: number; moq?: number | null; price_breaks: { min_quantity: number; unit_price: number }[]; shipping_cost?: number | null;
  lead_time_days?: number | null; warranty?: string | null; return_terms?: string | null; certifications?: string | null; quoted_on?: string | null; valid_until?: string | null;
  source_kind: SourceKind; label: string; status: 'ACTIVE' | 'EXPIRED' | 'STALE'; warnings: string[]; reference?: string | null; verified_by?: string | null; origin: Origin; provenance?: Provenance;
}
export interface CalcResult {
  status: 'OK' | 'INCOMPLETE' | 'INVALID'; issues: string[]; missing: string[]; missing_for_margin: string[]; values: Record<string, string | number | boolean | null>; assumptions: string[];
  cost_drivers: { component: string; amount: string; share_pct: string }[]; warnings?: string[]; sensitivity?: { scenario: string; landed_cost_per_unit: string; change_pct: string; gross_margin_pct?: string | null }[];
  by_quantity?: ({ quantity: number } & Omit<CalcResult, 'by_quantity'>)[]; snapshot_id?: string; basis?: CalcBasis;
}
export interface ScoreResult {
  model_version: string; status: 'SCORED' | 'INSUFFICIENT_EVIDENCE' | 'AWAITING_PRODUCT_DISCOVERY'; opportunity: number | null; priority: string | null; confidence: 'LOW' | 'MEDIUM' | 'HIGH'; data_completeness: number;
  dimensions: { dimension: string; score: number; weight: number; why: string }[]; missing: string[]; supplier_risk: { level: string; factors: string[] }; reasons: string[]; next_action: string;
  financial_status?: 'QUOTED' | 'ESTIMATED' | 'INCOMPLETE' | 'NONE'; financial_basis?: CalcBasis | null; provenance?: Provenance;
  inputs_summary?: { demand?: { queries?: string[]; searches?: number; customers?: number }; inventory_signal?: string; demand_window_days?: number };
}
export interface Decision { id: number; at: string; actor_hash: string; action: string; from_status?: string | null; to_status?: string | null; reason?: string | null; detail: Record<string, unknown> }
export interface ProposalPayload { notice?: string; supplier?: { supplier?: string } | null; purchase?: { landed_cost_per_unit?: string | null }; pricing?: { proposed_selling_price?: number | string | null; gross_margin_pct?: string | null } }
export interface Proposal { id: string; opportunity_id: string; status: 'DRAFT' | 'READY_FOR_REVIEW' | 'REVIEWED'; payload: ProposalPayload; validation: { missing_mandatory: string[]; ready: boolean }; created_at: string; opportunity_name?: string }
export interface OpportunityDetail {
  opportunity: Opportunity; evidence: Evidence[]; quotes: Quote[]; score: ScoreResult | null; score_at: string | null; calculations: { id: string; created_at: string; result: CalcResult; inputs: Record<string, unknown> }[];
  history: Decision[]; proposals: Proposal[]; allowed_next: OppStatus[]; origin_label: string;
  images: ProductImage[]; specifications: Record<string, string>; missing_specifications: string[]; readiness: Readiness; possible_suppliers: PossibleSupplier[]; provenance_label: string; is_fixture: boolean;
}
export interface PossibleSupplier { id: string; name: string; type: string; country?: string | null; verification_status: SupplierStage; discovery_url?: string | null; discovery_note?: string | null }
export interface RunSource { name: string; status: string; records: number; reason?: string | null; skipped?: { url: string; why: string }[]; suppliers?: number; provider_stats?: Record<string, number> }
export interface Run { id: string; started_at: string; finished_at?: string | null; status: 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED'; params: Record<string, unknown>; sources: RunSource[]; stats: Record<string, number>; error?: string | null }
export interface RunResults { run: Run; products: { id: string; name: string; source_url?: string | null; provenance: Provenance; image_url?: string | null }[] }
export interface Supplier {
  id: string; name: string; type: string; website?: string | null; country?: string | null; region?: string | null; categories: string[]; brands: string[]; authorised_brands: string[];
  authorisation_note?: string | null; verification_status: SupplierStage; origin: Origin; quote_count?: number; contact: Record<string, string>; provenance: Provenance;
  discovery_url?: string | null; discovery_note?: string | null; verification_history?: { stage: SupplierStage; by: string; at: string; note?: string | null; evidence_url?: string | null }[];
}
export interface SupplierDetail { supplier: Supplier; offers: Quote[]; provenance_label: string; ladder: SupplierStage[]; is_fixture: boolean }
export interface Dashboard {
  as_of: string; scope: 'live'; total_products: number; by_status: Record<string, number>; awaiting_review: number; shortlisted: number; approved_opportunity: number; approved_for_onboarding: number;
  with_supplier_quotes: number; with_comparable_offers: number; margin_at_least_25_pct: number; blocked_by_missing_information: number; with_product_image: number; with_exact_model_image: number;
  demand_gaps: { total: number; by_status: Record<string, number> }; by_provenance: Record<string, Record<string, number>>; excluded: { fixtures: number; unknown_provenance: number };
  recent_runs: Run[]; demand_signals_in_window: number;
  window_days: number; notes: string[]; sources: { external_configured: boolean; external_feeds: { name: string }[]; configuration_problems: string[]; fx_currencies: string[]; base_currency: string };
}
export interface Compare {
  opportunity_id: string; selling_price?: number | null;
  summary: { quantity: number; compared: number; usable: number; lowest_landed_quote: string | null; lowest_unit_price_quote: string | null; explanation: string | null; excluded?: { quote_id: string; supplier: string; why: string[] }[] };
  offers: { quote: Quote; calculation: CalcResult; warnings: string[]; rank_by_landed_cost: number | null }[];
}

export interface AskAnswer {
  intent: string; answer: string; evidence: { claim?: string; supplier?: string; source?: string; url?: string | null; reference?: string | null }[]; limitations: string[];
  needs_choice: { id: string; name: string }[]; opportunity?: { id: string; name: string };
}

export type OppFilters = {
  q?: string; status?: string[]; sport?: string; category?: string; brand?: string; origin?: string; kind?: string; evidence?: string; has_supplier?: boolean; risk?: string;
  min_margin?: number; max_margin?: number; demand?: string; sort?: string; page?: number; size?: number;
  record_kind?: 'PRODUCT_CANDIDATE' | 'DEMAND_GAP'; include_fixtures?: boolean; provenance?: string[]; has_image?: boolean; image_match?: MatchLevel; catalogue_match?: string;
  supplier_verification?: SupplierStage; has_margin?: boolean; min_score?: number; confidence?: string;
};
const clean = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== '' && !(Array.isArray(v) && v.length === 0)));

export const sourcingApi = {
  dashboard: async () => (await client.get<Dashboard>('/dashboard')).data,
  opportunities: async (f: OppFilters) => (await client.get<{ items: Opportunity[]; total: number; page: number; size: number }>('/opportunities', {
    params: clean({ ...f }), paramsSerializer: { indexes: null } })).data,
  opportunity: async (id: string) => (await client.get<OpportunityDetail>(`/opportunities/${id}`)).data,
  createOpportunity: async (body: Record<string, unknown>) => (await client.post<Opportunity>('/opportunities', body)).data,
  changeStatus: async (id: string, status: string, reason: string | undefined, expectedVersion: number) => (await client.post<Opportunity>(`/opportunities/${id}/status`, { status, reason, expected_version: expectedVersion })).data,
  note: async (id: string, text: string) => { await client.post(`/opportunities/${id}/notes`, { text }); },
  rescore: async (id: string) => (await client.post<ScoreResult>(`/opportunities/${id}/score`)).data,
  suppliers: async (p: { q?: string; type?: string; origin?: string; include_fixtures?: boolean; stage?: SupplierStage; page?: number; size?: number }) => (await client.get<{ items: Supplier[]; total: number }>('/suppliers', { params: clean(p) })).data,
  supplier: async (id: string) => (await client.get<SupplierDetail>(`/suppliers/${id}`)).data,
  setSupplierStage: async (id: string, stage: SupplierStage, note: string, evidenceUrl?: string) => (await client.post<Supplier>(`/suppliers/${id}/stage`, { stage, note, evidence_url: evidenceUrl || undefined })).data,
  classify: async (id: string, provenance: Provenance, reason: string) => (await client.post<Opportunity>(`/opportunities/${id}/provenance`, { provenance, reason })).data,
  linkGap: async (gapId: string, candidateId: string) => (await client.post<Opportunity>(`/gaps/${gapId}/link`, { candidate_id: candidateId })).data,
  addImage: async (id: string, url: string, matchLevel: MatchLevel, note?: string) => (await client.post<ProductImage>(`/opportunities/${id}/images`, { url, match_level: matchLevel, note: note || undefined })).data,
  setPrimaryImage: async (id: string, imageId: string) => { await client.post(`/opportunities/${id}/images/${imageId}/primary`); },
  reportImage: async (imageId: string, reason: string) => (await client.post<ProductImage>(`/images/${imageId}/report`, { reason })).data,
  recheckImage: async (imageId: string) => (await client.post<ProductImage>(`/images/${imageId}/recheck`)).data,
  readiness: async (id: string, p: { quote_id?: string; quantity?: number; selling_price?: number; image_exception?: string }) => (await client.get<Readiness>(`/opportunities/${id}/readiness`, { params: clean(p) })).data,
  run: async (id: string) => (await client.get<Run>(`/runs/${id}`)).data,
  runResults: async (id: string) => (await client.get<RunResults>(`/runs/${id}/results`)).data,
  createSupplier: async (body: Record<string, unknown>) => (await client.post<Supplier>('/suppliers', body)).data,
  addQuote: async (oppId: string, body: Record<string, unknown>) => (await client.post<Quote>(`/opportunities/${oppId}/quotes`, body)).data,
  verifyQuote: async (id: string) => (await client.post<Quote>(`/quotes/${id}/verify`)).data,
  calculate: async (oppId: string, body: Record<string, unknown>) => (await client.post<CalcResult>(`/opportunities/${oppId}/calculate`, body)).data,
  compare: async (oppId: string, body: Record<string, unknown>) => (await client.post<Compare>(`/opportunities/${oppId}/compare`, body)).data,
  calculator: async (body: Record<string, unknown>) => (await client.post<CalcResult>('/calculator', body)).data,
  runs: async () => (await client.get<{ runs: Run[] }>('/runs')).data.runs,
  startRun: async (body: Record<string, unknown>) => (await client.post<Run>('/runs', body)).data,
  proposals: async () => (await client.get<{ proposals: Proposal[] }>('/proposals')).data.proposals,
  prepareProposal: async (oppId: string, body: Record<string, unknown>) => (await client.post<Proposal>(`/opportunities/${oppId}/proposal`, body)).data,
  reviewProposal: async (id: string) => (await client.post<Proposal>(`/proposals/${id}/review`)).data,
  queue: async () => (await client.get<{ items: Opportunity[]; total: number; can_approve: boolean }>('/queue')).data,
  ask: async (question: string) => (await client.post<AskAnswer>('/ask', { question })).data,
  seedDemo: async () => (await client.post<{ created: Record<string, number>; label: string }>('/demo/seed')).data,
};

export const sourcingError = (err: unknown): string => {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as { message?: string; code?: string } | undefined;
    if (!err.response) return 'The sourcing service could not be reached. The rest of the shop is not affected.';
    if (err.response.status === 403) return body?.message ?? 'You do not have permission for this action.';
    if (body?.message) return body.message;
  }
  return 'That could not be completed.';
};
