import axios from 'axios';
import { installAuth } from '@/api/client';
import type { AiRecommendations, AiResponse, RecoOptions } from '@/types/ai';
import type { OfferRecommendations } from '@/types/checkout';

export interface CaseConversation {
  caseRef: string;
  conversationId: string | null;
  transcript: { role: 'user' | 'assistant'; text: string; at: string; kind?: string }[];
}

/** The AI assistant is a separate service (proxied at /ai-api). It uses the SAME bearer token and refresh flow as the shop API. */
const aiClient = installAuth(
  axios.create({
    baseURL: '/ai-api',
    headers: { 'Content-Type': 'application/json' },
    timeout: 120_000,
  }),
);

export const aiApi = {
  /** `context.selectedProductId` is only an id: the service re-reads the product from DSports; name and price are never sent. */
  chat: async (
    message: string,
    conversationId?: string | null,
    context?: { selectedProductId?: string | null; clearProductContext?: boolean; choiceId?: string | null; caseRef?: string | null },
  ): Promise<AiResponse> => {
    const { data } = await aiClient.post<AiResponse>('/v1/chat', {
      message,
      conversationId: conversationId ?? null,
      selectedProductId: context?.selectedProductId ?? null,
      clearProductContext: context?.clearProductContext ?? false,
      choiceId: context?.choiceId ?? null,
      ...(context?.caseRef ? { caseRef: context.caseRef } : {}),   // only a case-linked conversation carries it
    });
    return data;
  },
  /** The customer's latest assistant conversation about one of THEIR cases, to resume it (someone else's case is 404). */
  caseConversation: async (caseRef: string): Promise<CaseConversation> => {
    const { data } = await aiClient.get<CaseConversation>('/v1/support/conversation', { params: { caseRef } });
    return data;
  },
  /** Explainable recommendations from real DSports data (no model call). Guests get non-personalised ones; reasons come with each item. */
  recommendations: async (productIds: string[] = [], limit = 4): Promise<AiRecommendations> => {
    const { data } = await aiClient.get<AiRecommendations>('/v1/recommendations', { params: { productIds: productIds.join(','), limit } });
    return data;
  },
  /** Scenario-based recommendations: complementary / bought together / similar / premium / personalised, with an optional explicit budget. The customer is the token's subject. */
  scenarioRecommendations: async (options: RecoOptions, limit = 4): Promise<AiRecommendations> => {
    const { data } = await aiClient.get<AiRecommendations>('/v1/recommendations', { params: { ...options, limit } });
    return data;
  },
  /** The backend's offers for this checkout, ordered and explained for the signed-in customer (no model call; amounts come from DSports). */
  recommendOffers: async (checkoutId: string): Promise<OfferRecommendations> => {
    const { data } = await aiClient.get<OfferRecommendations>('/v1/offers/recommend', { params: { checkoutId } });
    return data;
  },
  confirm: async (confirmationToken: string, conversationId?: string | null): Promise<AiResponse> => {
    const { data } = await aiClient.post<AiResponse>('/v1/confirm', { confirmationToken, conversationId: conversationId ?? null });
    return data;
  },
};

/** True when the assistant itself could not be reached (down, proxy error, timeout): the rest of the shop is unaffected. */
export const isAiUnreachable = (err: unknown): boolean => {
  if (!axios.isAxiosError(err)) return false;
  if (!err.response) return true;
  return err.response.status >= 502 && err.response.status <= 504;
};

export const getAiErrorMessage = (err: unknown): string => {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as { code?: string; message?: string } | undefined;
    if (err.response?.status === 429) return 'You are sending messages too quickly. Please wait a moment.';
    if (body?.message) return body.message;
  }
  return 'The assistant could not complete that request.';
};
