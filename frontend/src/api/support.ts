import axios from 'axios';
import apiClient from '@/api/client';
import type { ApiCase, ApiSummary, StaffCase, StaffPage, SupportAnalytics } from '@/types/support';

/** Customer routes: the customer is always the authenticated user. Staff routes need SUPPORT_EXECUTIVE or ADMIN; the BACKEND enforces it on every call. */
const P = '/v1/support';

export interface StaffFilters {
  status?: string; category?: string; priority?: string; escalation?: string; unassigned?: boolean; assignedTo?: string; q?: string;
  createdFrom?: string; createdTo?: string; page?: number; size?: number;
}

export interface CreateTicketPayload { category: string; subject: string; description: string; orderId?: string }

/** The customer-facing ticket categories (the same values the backend stores). */
export const TICKET_CATEGORIES: { value: string; label: string }[] = [
  { value: 'ORDER_ISSUE', label: 'Order' },
  { value: 'PAYMENT_ISSUE', label: 'Payment' },
  { value: 'DELAYED_DELIVERY', label: 'Delivery / shipment' },
  { value: 'PRODUCT_COMPLAINT', label: 'Product' },
  { value: 'RETURN_ISSUE', label: 'Return / refund' },
  { value: 'TECHNICAL_ISSUE', label: 'Website / technical' },
  { value: 'GENERAL', label: 'Other' },
];
const clean = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== '' && v !== false));

export const supportApi = {
  mine: async (unresolved = false) => (await apiClient.get<ApiSummary[]>(`${P}/cases`, { params: { unresolved } })).data,
  get: async (ref: string) => (await apiClient.get<ApiCase>(`${P}/cases/${ref}`)).data,
  /** One Idempotency-Key per form submission, so a double click or a retry cannot open two tickets. */
  create: async (payload: CreateTicketPayload, idempotencyKey: string) =>
    (await apiClient.post<{ outcome: string; supportCase: ApiCase }>(`${P}/cases`, payload, { headers: { 'Idempotency-Key': idempotencyKey } })).data,
  message: async (ref: string, body: string) => (await apiClient.post<ApiCase>(`${P}/cases/${ref}/messages`, { body })).data,
  escalate: async (ref: string, reason: string) => (await apiClient.post<ApiCase>(`${P}/cases/${ref}/escalate`, { reason })).data,
  reopen: async (ref: string, reason: string) => (await apiClient.post<ApiCase>(`${P}/cases/${ref}/reopen`, { reason })).data,
  staff: {
    list: async (f: StaffFilters) => (await apiClient.get<StaffPage>(`${P}/staff/cases`, { params: clean({ ...f }) })).data,
    get: async (ref: string) => (await apiClient.get<StaffCase>(`${P}/staff/cases/${ref}`)).data,
    assign: async (ref: string, body: { takeIt: boolean; queue?: string; expectedVersion?: number }) => (await apiClient.post<StaffCase>(`${P}/staff/cases/${ref}/assign`, body)).data,
    priority: async (ref: string, priority: string, reason: string, expectedVersion?: number) =>
      (await apiClient.post<StaffCase>(`${P}/staff/cases/${ref}/priority`, { priority, reason, expectedVersion })).data,
    note: async (ref: string, body: string, expectedVersion?: number) => (await apiClient.post<StaffCase>(`${P}/staff/cases/${ref}/notes`, { body, expectedVersion })).data,
    reply: async (ref: string, body: string, expectedVersion?: number) => (await apiClient.post<StaffCase>(`${P}/staff/cases/${ref}/replies`, { body, expectedVersion })).data,
    status: async (ref: string, status: string, note: string | undefined, expectedVersion?: number) =>
      (await apiClient.post<StaffCase>(`${P}/staff/cases/${ref}/status`, { status, note, expectedVersion })).data,
    resolve: async (ref: string, outcome: string, note: string, evidenceRefs: string[], expectedVersion?: number) =>
      (await apiClient.post<StaffCase>(`${P}/staff/cases/${ref}/resolve`, { outcome, note, evidenceRefs, expectedVersion })).data,
    analytics: async () => (await apiClient.get<SupportAnalytics>(`${P}/staff/analytics`)).data,
  },
};

export const supportError = (err: unknown): { code: string; message: string; status?: number } => {
  if (axios.isAxiosError(err)) {
    const body = err.response?.data as { code?: string; message?: string } | undefined;
    if (!err.response) return { code: 'UNREACHABLE', message: 'The support service could not be reached. The rest of the shop is not affected.' };
    if (err.response.status === 403) return { code: 'FORBIDDEN', message: 'This area is restricted to DSports support staff.', status: 403 };
    if (err.response.status === 404) return { code: 'NOT_FOUND', message: 'We could not find that case on your account.', status: 404 };
    if (body?.message) return { code: body.code ?? 'ERROR', message: body.message, status: err.response.status };
  }
  return { code: 'ERROR', message: 'That could not be completed.' };
};
