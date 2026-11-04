import axios from 'axios';
import { installAuth } from '@/api/client';
import type {
  ClearanceCandidate, InvAssessment, InvCopilotResponse, InvDataQuality, InvEvent, InvEventImpact, InvForecast, InvModelInfo, InvOverview, InvRegions, InvScenario,
} from '@/types/inventoryAi';

/** The inventory service shares the AI service (proxied at /ai-api) and the SAME bearer token. The backend enforces the ADMIN role on every route. */
const client = installAuth(axios.create({ baseURL: '/ai-api/v1/inventory', headers: { 'Content-Type': 'application/json' }, timeout: 120_000 }));

type Selection = { sport?: string; category?: string };
const q = (params: Record<string, string | number | undefined>) => ({ params: Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== '')) });

export const inventoryAiApi = {
  overview: async () => (await client.get<InvOverview>('/overview')).data,
  taxonomy: async () => (await client.get<{ sports: Record<string, string[]> }>('/taxonomy')).data.sports,
  forecast: async (s: Selection) => (await client.get<InvForecast>('/forecast', q(s))).data,
  productForecast: async (id: string) => (await client.get<InvForecast>(`/forecast/${id}`)).data,
  products: async (s: Selection & { q?: string }) => (await client.get<{ products: { product_id: string; name: string; sport: string; category: string }[] }>('/products', q(s))).data.products,
  risk: async (s: Selection & { level?: string; scenario?: string; limit?: number }) => (await client.get<{ risks: InvAssessment[] }>('/risk', q(s))).data.risks,
  replenishment: async (s: Selection & { scenario?: string }) =>
    (await client.get<{ recommendations: InvAssessment[]; recommendation_status: string }>('/replenishment', q(s))).data,
  overstock: async (s: Selection & { older_than_days?: number }) => (await client.get<{ products: InvAssessment[] }>('/overstock', q(s))).data.products,
  slowMoving: async (s: Selection) => (await client.get<{ products: InvAssessment[] }>('/slow-moving', q(s))).data.products,
  aging: async (s: Selection) => (await client.get<{ buckets: { bucket: string; skus: number; units: number; value: number }[]; total_skus: number }>('/aging', q(s))).data,
  clearance: async (s: Selection) =>
    (await client.get<{ candidates: ClearanceCandidate[]; products: number; total_excess_units: number; total_excess_value: number; recommendation_status: string }>('/clearance', q(s))).data,
  events: async () => (await client.get<{ events: InvEvent[] }>('/events')).data.events,
  eventImpact: async (id: string) => (await client.get<InvEventImpact>(`/events/${id}`)).data,
  regions: async (s: Selection & { event_id?: string; product_id?: string }) => (await client.get<InvRegions>('/regions', q(s))).data,
  quality: async () => (await client.get<{ model: InvModelInfo; data_quality: InvDataQuality }>('/quality')).data,
  scenario: async (body: { sport?: string; category?: string; discounts: number[]; days: number; slowMovingOnly: boolean }) =>
    (await client.post<InvScenario>('/scenario', body)).data,
  train: async () => (await client.post<{ run_id: string; model: string; duration_ms: number }>('/models/train')).data,
  copilot: async (message: string, conversationId?: string | null) =>
    (await client.post<InvCopilotResponse>('/copilot', { message, conversationId: conversationId ?? null })).data,
};

export const getInventoryError = (err: unknown): { code: string; message: string } => {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as { code?: string; message?: string } | undefined;
    if (!err.response) return { code: 'UNREACHABLE', message: 'The inventory service could not be reached. The storefront is not affected.' };
    if (err.response.status === 403) return { code: 'FORBIDDEN', message: 'Inventory intelligence is restricted to authorised business users.' };
    if (err.response.status === 404 && body?.code === 'INSUFFICIENT_DATA') return { code: 'INSUFFICIENT_DATA', message: body.message ?? 'Insufficient data to produce a reliable forecast.' };
    if (body?.message) return { code: body.code ?? 'ERROR', message: body.message };
  }
  return { code: 'ERROR', message: 'That request could not be completed.' };
};
