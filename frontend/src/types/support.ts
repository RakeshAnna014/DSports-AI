/** Support cases. Two wire shapes meet here: the AI service (snake_case, `response.support`) and the Java case API (camelCase). Both are normalised into CaseView. */

export type CaseStatus = 'OPEN' | 'INVESTIGATING' | 'WAITING_FOR_CUSTOMER' | 'WAITING_FOR_EXTERNAL_PARTY' | 'RESOLVED';
export type CasePriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type EscalationState = 'NONE' | 'REQUESTED' | 'ACCEPTED';

/** REQUESTED means "a person has been asked"; only ACCEPTED means a support executive actually took the case. */
export interface EscalationView { state: EscalationState; reason: string | null; humanAccepted: boolean }

export interface TimelineEvent { type: string; fromStatus?: string | null; toStatus?: string | null; detail?: string | null; at: string }
export interface CaseMessage { from: 'CUSTOMER' | 'SUPPORT'; body: string; createdAt: string }
export interface Evidence { kind: string; ref?: string | null; summary: string; source: string }

/** What every customer-facing component renders. */
export interface CaseView {
  id: string;
  caseNumber: string;
  category: string;
  status: CaseStatus;
  priority: CasePriority;
  subject: string;
  orderNumber?: string | null;
  escalation: EscalationView;
  createdAt: string;
  updatedAt: string;
  resolvedAt?: string | null;
  description?: string;
  resolutionOutcome?: string | null;
  resolutionNote?: string | null;
  canReopen?: boolean;
  assignedToSupportTeam?: boolean;
  investigationSummary?: string | null;
  evidence?: Evidence[];
  policyCitations?: string[];
  missingInformation?: string[];
  resolutionRecommendation?: string | null;
  messages?: CaseMessage[];
  timeline?: TimelineEvent[];
  version?: number;
}

// ---- the chat payload (snake_case, from the AI service)
export interface ChatCase {
  case_id: string;
  case_number: string;
  category: string;
  status: CaseStatus;
  priority: CasePriority;
  subject: string;
  order_number?: string | null;
  escalation: { state: EscalationState; reason: string | null; human_accepted: boolean };
  created_at: string;
  updated_at: string;
  resolved_at?: string | null;
  description?: string;
  resolution_outcome?: string | null;
  resolution_note?: string | null;
  can_reopen?: boolean;
  assigned_to_support_team?: boolean;
  messages?: { from: 'CUSTOMER' | 'SUPPORT'; body: string; created_at: string }[];
  timeline?: { type: string; from_status?: string | null; to_status?: string | null; detail?: string | null; at: string }[];
}

export interface InvestigationFact { kind: string; statement: string; ref?: string | null; source: string }

export type AiSupport =
  | {
      kind: 'INVESTIGATION';
      complaint: string;
      order?: { order_id: string; order_number: string; status: string } | null;
      product?: { product_id?: string; name?: string | null } | null;
      evidence: {
        verified: InvestigationFact[];
        inferences: { statement: string; basis: string }[];
        missing: { item: string; why: string }[];
        unverified_lookups: string[];
      };
      policy: { title: string; source: string; snippet: string }[];
      resolution: {
        outcome: 'ANSWER' | 'ASK_INFO' | 'SAFE_NEXT_STEP' | 'CREATE_CASE' | 'ESCALATE' | 'PERFORM_ACTION' | 'UNVERIFIABLE';
        recommendation: string;
        category: string;
        escalation: { recommended: boolean; reason: string | null; state: string; human_accepted: boolean };
        reasons: string[];
      };
      safety_related?: boolean;
    }
  | { kind: 'ASK_INFO'; complaint?: string; ask?: string }
  | { kind: 'CASE' | 'CASE_CREATED'; case: ChatCase; outcome?: string }
  | { kind: 'CASE_LIST'; cases: ChatCase[]; count?: number };

// ---- the Java API (camelCase)
export interface ApiSummary {
  id: string; caseNumber: string; category: string; status: CaseStatus; priority: CasePriority; subject: string; orderNumber?: string | null;
  escalation: EscalationView; createdAt: string; updatedAt: string; resolvedAt?: string | null;
}

export interface ApiCase extends ApiSummary {
  subcategory?: string | null; description: string; orderId?: string | null; productName?: string | null; orderStatusAtCreation?: string | null;
  shipmentStatusAtCreation?: string | null; investigationSummary?: string | null; evidence: Evidence[]; policyCitations: string[]; missingInformation: string[];
  resolutionRecommendation?: string | null; resolutionOutcome?: string | null; resolutionNote?: string | null; assignedToSupportTeam: boolean; canReopen: boolean;
  reopenWindowDays: number; version: number; messages: { id: string; from: 'CUSTOMER' | 'SUPPORT'; body: string; createdAt: string }[];
  timeline: TimelineEvent[];
}

export interface StaffCase extends ApiSummary {
  customerId: string; subcategory?: string | null; priorityReasons: string[]; description: string; orderId?: string | null; productName?: string | null;
  orderStatusAtCreation?: string | null; shipmentStatusAtCreation?: string | null; orderTotal?: number | null; currency?: string | null;
  investigationSummary?: string | null; evidence: Evidence[]; policyCitations: string[]; missingInformation: string[]; resolutionRecommendation?: string | null;
  resolutionOutcome?: string | null; resolutionNote?: string | null; resolvedBy?: string | null; assignedQueue?: string | null; assignedTo?: string | null;
  safetyRelated: boolean; source: string; reopenCount: number; version: number;
  messages: { id: string; authorType: string; authorId?: string | null; internal: boolean; body: string; createdAt: string }[];
  events: { type: string; actorType: string; actorId?: string | null; fromStatus?: string | null; toStatus?: string | null; detail?: string | null; customerVisible: boolean; at: string }[];
}

export interface StaffPage { items: ApiSummary[]; total: number; page: number; size: number }

export interface SupportAnalytics {
  totalCases: number; unresolvedCases: number; byStatus: Record<string, number>; byCategory: Record<string, number>; unresolvedByPriority: Record<string, number>;
  escalationRequestedNotYetAccepted: number; escalationAccepted: number; unassignedUnresolved: number; resolvedByOutcome: Record<string, number>; resolvedByHuman: number;
  automaticallyResolved: number; reopenedCases: number; casesWithRepeatContact: number; averageUnresolvedAgeHours: number | null; oldestUnresolvedAgeHours: number | null;
  createdByAssistant: number; createdByWeb: number; definitions: Record<string, string>;
}

export const OUTCOMES = ['ANSWERED', 'REFUND_PROCESSED_VIA_PAYMENT_FLOW', 'RETURN_APPROVED_VIA_RETURNS_FLOW', 'REPLACEMENT_ARRANGED', 'DELIVERY_CONFIRMED', 'NOT_ELIGIBLE',
  'NO_ACTION_NEEDED', 'CUSTOMER_WITHDREW', 'DUPLICATE_OF_ANOTHER_CASE'] as const;

/** The transitions the backend allows (CaseStatus.canTransitionTo). The UI only offers these; the backend still validates. */
export const NEXT_STATUS: Record<CaseStatus, CaseStatus[]> = {
  OPEN: ['INVESTIGATING', 'WAITING_FOR_CUSTOMER'],
  INVESTIGATING: ['WAITING_FOR_CUSTOMER', 'WAITING_FOR_EXTERNAL_PARTY'],
  WAITING_FOR_CUSTOMER: ['INVESTIGATING'],
  WAITING_FOR_EXTERNAL_PARTY: ['INVESTIGATING'],
  RESOLVED: ['INVESTIGATING'],
};

export const fromChat = (c: ChatCase): CaseView => ({
  id: c.case_id, caseNumber: c.case_number, category: c.category, status: c.status, priority: c.priority, subject: c.subject, orderNumber: c.order_number,
  escalation: { state: c.escalation.state, reason: c.escalation.reason, humanAccepted: c.escalation.human_accepted }, createdAt: c.created_at, updatedAt: c.updated_at,
  resolvedAt: c.resolved_at, description: c.description, resolutionOutcome: c.resolution_outcome, resolutionNote: c.resolution_note, canReopen: c.can_reopen,
  assignedToSupportTeam: c.assigned_to_support_team,
  messages: c.messages?.map((m) => ({ from: m.from, body: m.body, createdAt: m.created_at })),
  timeline: c.timeline?.map((e) => ({ type: e.type, fromStatus: e.from_status, toStatus: e.to_status, detail: e.detail, at: e.at })),
});

export const fromApi = (c: ApiCase): CaseView => ({ ...c, messages: c.messages });
