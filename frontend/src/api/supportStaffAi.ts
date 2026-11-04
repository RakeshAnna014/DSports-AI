import axios from 'axios';
import apiClient, { installAuth } from '@/api/client';

/** Staff AI assistance lives in the AI service (proxied at /ai-api) and shares the bearer token; the report comes from the Java backend. Both enforce the staff role. */
const ai = installAuth(axios.create({ baseURL: '/ai-api/v1/support', headers: { 'Content-Type': 'application/json' }, timeout: 60_000 }));

export type ReportRange = 'today' | 'yesterday' | 'last7' | 'last30' | 'custom';
export interface ReportParams { range: ReportRange; from?: string; to?: string }
export interface SupportReport {
  range: string; from: string; to: string; timeZone: string; createdInPeriod: number; resolvedInPeriod: number; reopenedInPeriod: number; escalatedInPeriod: number;
  createdInPeriodStillUnresolved: number; openNow: number; inProgressNow: number; unresolvedNow: number; unassignedNow: number; escalationPendingNow: number;
  createdByCategory: Record<string, number>; createdByPriority: Record<string, number>; daily: { date: string; created: number; resolved: number }[];
  oldestUnresolvedAgeHours: number | null; slaNote: string; definitions: Record<string, string>;
}

export type ActionState = 'SUGGESTED' | 'ATTEMPTED' | 'SUCCEEDED' | 'FAILED';
export type ActionKind = 'ASSIGN_SELF' | 'SET_STATUS' | 'REPLY_CUSTOMER' | 'ADD_NOTE' | 'RESOLVE';
export interface PlanAction {
  id: string; kind: ActionKind; label: string; operation: string; body: Record<string, unknown>; confirmation: string; state: ActionState;
  editable_text?: boolean; draft_basis?: string[]; outcomes?: string[]; must_verify?: string[]; blocked_reason?: string;
}
export interface PlanFact { statement: string; source: string; ref?: string | null }
export interface PlanHypothesis { status: 'HYPOTHESIS'; statement: string; confidence: 'LOW' | 'MEDIUM'; basis: string; to_confirm: string }
export interface ActionHistory { actionId: string; kind: string; label: string; state: ActionState; trail: { state: ActionState; at: string; detail: Record<string, unknown> }[] }
export interface StaffPlan {
  caseRef: string; generatedAt: string; category: string; playbook: string; status: string; version: number; problem: string;
  known: PlanFact[]; uncertain: string[]; likelyCause: { status: string; hypotheses: PlanHypothesis[] };
  evidence: { kind: string; ref?: string | null; summary: string; source: string }[]; steps: { order: number; text: string }[]; tools: string[]; actions: PlanAction[];
  verification: string[]; customerDraft: { text: string; basis: string[]; never_includes: string; review_required: boolean };
  escalation: { conditions: string[]; recommended: boolean; reason: string | null }; live: { available: boolean; reason?: string };
  knowledge: { available: boolean; reason?: string; sources: { title: string; snippet: string; internal: boolean }[]; note?: string };
  engineering: { available: boolean; reason?: string; groups?: { reference: string; title: string; category: string; count: number }[]; note?: string };
  limitations: string[]; history: ActionHistory[]; sensitive: boolean;
}
export interface InsightItem { label: 'OBSERVATION' | 'HYPOTHESIS'; text: string; denominator: string; to_confirm?: string }
export interface SupportInsights {
  period: string; items: InsightItem[]; recommendations: { label: string; text: string; basis: string }[]; limitations: string[];
  chatbot: { conversations_with_support_turns: number; linked_to_a_case: number; feedback: Record<string, number>; resolution_rate: null | { helpful: number; answered_feedback: number; percent: number | null }; note: string };
}

const qs = (p: ReportParams) => (p.range === 'custom' ? { range: p.range, from: p.from, to: p.to } : { range: p.range });

export const supportStaffAi = {
  report: async (p: ReportParams) => (await apiClient.get<SupportReport>('/v1/support/staff/report', { params: qs(p) })).data,
  insights: async (p: ReportParams) => (await ai.get<SupportInsights>('/staff/insights', { params: qs(p) })).data,
  assist: async (ref: string) => (await ai.post<StaffPlan>(`/staff/cases/${ref}/assist`)).data,
  record: async (ref: string, actionId: string, state: Exclude<ActionState, 'SUGGESTED'>, detail?: Record<string, unknown>) =>
    (await ai.post<{ history: ActionHistory[] }>(`/staff/cases/${ref}/actions/${actionId}`, { state, detail })).data.history,
  feedback: async (conversationId: string, outcome: 'HELPFUL' | 'NOT_HELPFUL' | 'WANTED_PERSON') => { await ai.post('/feedback', { conversationId, outcome }); },
};
