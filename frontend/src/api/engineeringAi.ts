import axios from 'axios';
import { installAuth } from '@/api/client';

/** Engineering AI lives in the AI service (proxied at /ai-api) and shares the bearer token. The service enforces the ADMIN role on every route. */
const client = installAuth(axios.create({ baseURL: '/ai-api/v1/engineering', headers: { 'Content-Type': 'application/json' }, timeout: 120_000 }));

export interface EngEvidence {
  id: number; ts: string; service: string; origin: 'real' | 'synthetic'; level: string; logger?: string | null; message: string;
  exception_head?: string | null; correlation_id?: string | null; http_status?: number | null; injection_flagged: boolean;
}
export interface EngCluster {
  signature: string; text: string; count: number; first_seen: string; last_seen: string; services: string[]; level: string; category: string;
  sample_ids: number[]; previous_window_count: number | null; change: string;
}
export interface EngHypothesis { status: 'HYPOTHESIS'; statement: string; evidence_ids: number[]; confidence: 'LOW' | 'MEDIUM'; to_confirm: string; category: string }
export interface EngInvestigation {
  question: string; interpretation: string[]; intent: string; window: { since: string; until: string }; facts: string[]; hypotheses: EngHypothesis[]; clusters: EngCluster[];
  evidence: EngEvidence[]; next_steps: string[]; limitations: string[]; sources_connected: { service: string; origin: string; source: string; records: number }[];
  trend?: { direction: string; first_half_errors: number | null; second_half_errors: number | null };
  summary: { text: string; source: 'deterministic' | 'llm'; guard: { ok?: boolean; reason?: string } | null };
  meta: { latency_ms: number; records_in_evidence: number; llm_used: boolean };
}

export type EngFreshness = 'LIVE' | 'DELAYED' | 'STALE' | 'SYNTHETIC';
export interface EngSource { service: string; origin: 'real' | 'synthetic'; source: string; records: number; first_ts: string; last_ts: string; lag_seconds?: number; freshness?: EngFreshness }
export interface EngSourceStatus { server_time: string; live_threshold_seconds: number; delayed_threshold_seconds: number; sources: EngSource[] }
export interface EngRefreshResult {
  refreshed_at: string; throttled: boolean; stored_new: number;
  sources: { service: string; source: string; kind: string; status: 'ok' | 'unavailable'; reason?: string; lines?: number; stored_new?: number }[];
}

export interface EngLogRecord extends EngEvidence {
  category?: string | null; reference?: string | null; signature?: string | null; exception_excerpt?: string | null; source?: string | null;
}
export interface EngLogPage { window: { since: string; until: string }; count: number; total: number; offset: number; limit: number; has_more: boolean; records: EngLogRecord[] }
export interface EngLogQuery {
  hours?: number; since?: string; until?: string; service?: string[]; level?: string; q?: string[]; correlationId?: string; endpoint?: string; signature?: string;
  origin?: 'real' | 'synthetic'; limit?: number; offset?: number;
}

export interface EngIncident {
  reference: string; signature: string; title: string; level: string; category: string; services: string[]; count: number; first_seen: string; last_seen: string;
  first_seen_overall: string; previous_window_count: number | null; change: string; status: 'NEW' | 'INCREASING' | 'RECURRING' | 'DECREASING'; score: number;
  score_breakdown: Record<string, number>; endpoints: string[]; correlation_ids: string[];
  impact: { affected_requests_estimate: number; http_5xx_records: number; label: string };
  evidence_ids: number[]; evidence: EngEvidence[]; origin: 'real' | 'synthetic' | 'mixed' | null;
  investigation: { status: 'NOT_INVESTIGATED' | 'INVESTIGATED'; at: string | null };
}
export interface EngIncidentPage {
  window: { since: string; until: string }; total: number; offset: number; limit: number; has_more: boolean; incidents: EngIncident[]; ranking: string; note: string;
  data_freshness: { service: string; origin: string; source: string; last_ts: string; lag_seconds: number; freshness: EngFreshness }[];
}
export interface EngIncidentQuery { hours?: number; since?: string; until?: string; service?: string[]; origin?: 'real' | 'synthetic'; limit?: number; offset?: number }
export interface EngIncidentRef { signature: string; since: string; until: string; origin?: 'real' | 'synthetic' }

export interface EngIncidentReport {
  reference: string; signature: string; window: { since: string; until: string }; generated_at: string; origin: 'real' | 'synthetic' | 'mixed'; summary: string;
  observed_evidence: EngEvidence[]; endpoints: string[];
  before_after: { errors_2h_before_first_seen: number; errors_2h_after_first_seen: number; other_error_groups_within_30_min: { signature: string; text: string; count: number; category: string; services: string[] }[] };
  cross_service: { traced_requests: { correlation_id: string; services: string[]; records: number; first_error_service: string | null }[]; first_failing_service_counts: Record<string, number> };
  dependency_errors: { co_occurring_groups: { signature: string; text: string; category: string; count: number; services: string[] }[]; postgres_error_records_nearby: number };
  code: { files: { path: string; line: number | null; symbol: string | null; excerpt: string[]; tests: string[]; module: string | null }[] };
  git: { available: boolean; head: { commit: string; date: string; subject: string } | null; commits_touching_suspected_files: { commit: string; date: string; subject: string }[] | null;
         recent_commits_repository_wide: { commit: string; date: string; subject: string }[] | null; uncommitted_suspected_files: string[]; note: string };
  deployment: { available: boolean; service?: string; version?: string; ts?: string; minutes_before_first_seen?: number; note?: string; reason?: string };
  configuration_changes: { available: boolean; reason: string };
  related_incidents: { reference: string; text: string; level: string; shared_requests: number }[];
  hypotheses: { status: 'HYPOTHESIS'; statement: string; evidence_ids: number[]; confidence: 'LOW' | 'MEDIUM'; to_confirm: string; basis: string }[];
  likely_cause_status: string; remediation: string[]; reproduction: string[]; validation_plan: string[]; additional_evidence_needed: string[]; limitations: string[]; confidence: 'LOW' | 'MEDIUM';
}
export interface EngPrompt { reference: string; prompt: string; characters: number; warnings: string[]; origin: string }

const clean = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== '' && !(Array.isArray(v) && v.length === 0)));

export const engineeringApi = {
  investigate: async (question: string, origin?: 'real' | 'synthetic', useLlm = false) =>
    (await client.post<EngInvestigation>('/investigate', { question, origin, useLlm })).data,
  sources: async () => (await client.get<EngSourceStatus>('/sources')).data.sources,
  /** The sources with how far behind each one is (LIVE / DELAYED / STALE; synthetic data is labelled as such). */
  sourceStatus: async () => (await client.get<EngSourceStatus>('/sources')).data,
  /** Near-live: the service re-reads the configured real sources and stores what is new (idempotent, throttled). */
  refresh: async () => (await client.post<EngRefreshResult>('/refresh')).data,
  logs: async (q: EngLogQuery) => (await client.get<EngLogPage>('/logs', { params: clean({ ...q }), paramsSerializer: { indexes: null } })).data,
  incidents: async (q: EngIncidentQuery) => (await client.get<EngIncidentPage>('/incidents', { params: clean({ ...q }), paramsSerializer: { indexes: null } })).data,
  investigateIncident: async (r: EngIncidentRef) => (await client.post<EngIncidentReport>('/incidents/investigate', r)).data,
  incidentPrompt: async (r: EngIncidentRef) => (await client.post<EngPrompt>('/incidents/prompt', r)).data,
};
