/** Mirrors the AI service response (ai-service/app/agent/schemas.py). Everything except `message` is VERIFIED data from DSports. */
import type { AiSupport } from '@/types/support';

export type AiStatus = 'ok' | 'refused' | 'needs_login' | 'ai_unavailable' | 'error';

export interface AiProduct {
  product_id: string;
  name: string;
  brand?: string | null;
  category?: string | null;
  sport?: string | null;
  description?: string | null;
  attributes?: Record<string, string>;
  weight_g?: number | null;
  dimensions?: string | null;
  image_url?: string | null;
  price?: number;
  mrp?: number | null;
  currency?: string;
  you_save?: number;
  in_stock?: boolean;
  stock_status?: string;
  available_quantity?: number;
  rating?: { average: number; count: number };
  over_budget?: boolean;
}

/** The structured kind of a response: the UI renders a component per type instead of a paragraph. */
export type AiResponseType =
  | 'TEXT' | 'PRODUCT_LIST' | 'PRODUCT_SELECTION' | 'PRODUCT_DETAIL' | 'PRODUCT_COMPARISON' | 'RECOMMENDATIONS'
  | 'ORDER_LIST' | 'ORDER_TRACKING' | 'RETURN_PRODUCT_SELECTION' | 'RETURN_OPTIONS' | 'CONFIRMATION' | 'REVIEWS'
  | 'CART' | 'PAYMENTS' | 'REFUNDS' | 'SUPPORT_CASE' | 'SUPPORT_CASES' | 'SUPPORT_INVESTIGATION' | 'ERROR';

/** One tappable option the service offered (for example a product to return). `choice_id` is opaque: ids stay on the server. */
export interface AiSelectionOption {
  choice_id: string;
  product_id: string;
  name: string;
  order_number: string;
  quantity: number;
  unit_price?: number | null;
  status?: string;
}

export interface AiSelection {
  purpose: 'RETURN' | 'REORDER';
  prompt: string;
  options: AiSelectionOption[];
}

/** A follow-up the customer can tap: a plain message, or (with choiceId) a specific step the service already resolved. */
export interface AiSuggestion {
  label: string;
  message?: string | null;
  choiceId?: string | null;
}

export interface AiRecommendationItem extends AiProduct {
  reasons: string[];
  anchor?: string;
  /** Scenario pipeline only. All of these come from DSports data computed by code, never from a model. */
  original_price?: number | null;
  discount_pct?: number | null;
  availability?: string;
  scenario?: string;
  budget_note?: string | null;
}

export type RecoScenario = 'COMPLEMENTARY' | 'FREQUENTLY_BOUGHT_TOGETHER' | 'SIMILAR' | 'UPGRADE' | 'PERSONALIZED';

export interface RecoBudget {
  explicit?: number | null;
  complementary_ceiling_pct?: number | null;
  applied: { low: number | null; high: number | null; source: 'explicit' | 'policy' | 'fallback' | 'profile' | 'none'; pct: number | null; label: string };
  fallback_used: boolean;
  tried: { label: string; source: string; pct: number | null; candidates: number }[];
}

export interface AiRecommendations {
  title: string;
  personalized: boolean;
  because: string[];
  items: AiRecommendationItem[];
  bundle?: { products: { product_id: string; name: string }[]; total: number; savings?: number | null; budget?: number | null } | null;
  scenario?: RecoScenario | null;
  reference?: { product_id: string; name: string; price: number | null } | null;
  budget?: RecoBudget | null;
  profile?: { profile_id: string; display_name: string; kind: 'OWNER' | 'FAMILY'; personalization_applied: boolean; personalization_enabled: boolean } | null;
  evidence?: { minimum_orders: number; pairs: number; available: boolean } | null;
  message?: string | null;
  suggestedScenario?: RecoScenario | null;
  personalizationNote?: string | null;
  meta?: { candidates_evaluated: number; results: number; latency_ms: number; llm_tokens: number } | null;
}

export interface RecoOptions {
  referenceProductId?: string;
  scenario?: RecoScenario;
  profileId?: string;
  budget?: number;
}

export interface AiProductRef {
  productId: string;
  name: string;
}

export interface AiProductContext {
  selectedProduct: AiProductRef | null;
  comparisonProducts: AiProductRef[];
}

export interface AiReviews {
  product_id: string;
  name: string;
  average_rating?: number | null;
  total_reviews: number;
  reviews: { rating?: number | null; title?: string | null; comment?: string | null; created_at?: string | null }[];
}

export interface AiComparison {
  products: { product_id: string; name: string; image_url?: string | null }[];
  rows: { attribute: string; values: string[] }[];
}

export interface AiOrderItem {
  product_id: string;
  name: string;
  quantity: number;
  unit_price: number;
}

export interface AiOrder {
  order_id: string;
  order_number: string;
  status: string;
  placed_at?: string;
  total?: number;
  currency?: string;
  total_items?: number;
  items?: AiOrderItem[];
}

export interface AiTracking {
  order_id: string;
  order_number: string;
  order_status: string;
  message?: string;
  shipment: null | {
    shipment_number: string;
    tracking_number: string;
    courier: string;
    status: string;
    estimated_delivery?: string | null;
    delivered_at?: string | null;
    events: { status: string; time: string; location?: string | null; description?: string | null }[];
  };
}

export interface AiCart {
  items: { product_id: string; name: string; quantity: number; unit_price: number; line_total: number }[];
  total_items: number;
  total_amount: number;
  empty: boolean;
}

export interface AiPayment {
  payment_id: string;
  reference: string;
  order_id: string;
  amount: number;
  status: string;
  method: string;
  paid_at?: string | null;
}

export interface AiRefundGroup {
  payment_id: string;
  payment_status: string;
  paid_amount: number;
  refunded_amount: number;
  refunds: { refund_id: string; amount: number; status: string; requested_at?: string; completed_at?: string | null }[];
}

export interface AiEligibility {
  eligible: boolean;
  reason: string;
  message: string;
  order_number?: string;
  product_name?: string;
  max_returnable_quantity?: number;
  return_deadline?: string;
}

export interface AiAction {
  type: 'confirm';
  tool: string;
  label: string;
  summary: string;
  confirmationToken: string;
  expiresAt: string;
}

export interface AiSource {
  source: string;
  title?: string | null;
  page?: number | null;
  documentType?: string | null;
  chunkId: string;
  snippet?: string | null;
}

export interface AiResponse {
  conversationId: string;
  status: AiStatus;
  intent: string;
  tier: number;
  message: string;
  messageSource: 'ai' | 'deterministic';
  products: AiProduct[];
  comparison: AiComparison | null;
  orders: AiOrder[];
  tracking: AiTracking | null;
  cart: AiCart | null;
  payments: AiPayment[];
  refunds: AiRefundGroup[];
  eligibility: AiEligibility | null;
  actionResult: Record<string, unknown> | null;
  actions: AiAction[];
  sources: AiSource[];
  requiresConfirmation: boolean;
  confirmationToken: string | null;
  reviews?: AiReviews | null;
  /** Support: an investigation (verified / possible / not verifiable), a case card, or a list of cases. Case numbers come from the backend. */
  support?: AiSupport | null;
  type?: AiResponseType;
  recommendations?: AiRecommendations | null;
  selection?: AiSelection | null;
  suggestions?: AiSuggestion[];
  context?: AiProductContext;
  disclaimers: string[];
  errorCode: string | null;
  meta: {
    requestId?: string;
    latencyMs: number;
    llmCalls: number;
    /** What the assistant understood from a product search (merged across refinements). */
    slots?: Record<string, string | number | boolean | null>;
    tools?: { tool: string; status: string }[];
  };
}

/** One entry in the transcript. */
export type ChatEntry =
  | { id: string; role: 'user'; text: string }
  | { id: string; role: 'assistant'; response: AiResponse }
  | { id: string; role: 'error'; text: string; unavailable: boolean };
