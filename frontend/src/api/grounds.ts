import apiClient from './client';
import type { Blackout, Booking, CancelResult, Ground, GroundInput, GroundSummary, OwnerGround, Slot } from '@/types/grounds';

const clean = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== ''));

/** Browsing is public; everything else needs a sign-in, and the owner routes need the GROUND_OWNER role. The backend enforces both on every call. */
export const groundsApi = {
  search: (params: { city?: string; date?: string; from?: string; to?: string }) =>
    apiClient.get<GroundSummary[]>('/v1/grounds', { params: clean(params) }).then((r) => r.data),
  get: (id: string) => apiClient.get<Ground>(`/v1/grounds/${id}`).then((r) => r.data),
  slots: (id: string, date: string) => apiClient.get<Slot[]>(`/v1/grounds/${id}/slots`, { params: { date } }).then((r) => r.data),

  /** One Idempotency-Key per attempt: a double click or a retry returns the same booking instead of holding two slots. */
  hold: (payload: { groundId: string; startAt: string; endAt: string }, idempotencyKey: string) =>
    apiClient.post<Booking>('/v1/bookings', payload, { headers: { 'Idempotency-Key': idempotencyKey } }).then((r) => r.data),
  myBookings: () => apiClient.get<Booking[]>('/v1/me/bookings').then((r) => r.data),
  getBooking: (id: string) => apiClient.get<Booking>(`/v1/bookings/${id}`).then((r) => r.data),
  cancel: (id: string) => apiClient.post<CancelResult>(`/v1/bookings/${id}/cancel`).then((r) => r.data),

  owner: {
    grounds: () => apiClient.get<OwnerGround[]>('/v1/owner/grounds').then((r) => r.data),
    create: (input: GroundInput) => apiClient.post<OwnerGround>('/v1/owner/grounds', input).then((r) => r.data),
    update: (id: string, ground: GroundInput, active: boolean, expectedVersion: number) =>
      apiClient.put<OwnerGround>(`/v1/owner/grounds/${id}`, { ground, active, expectedVersion }).then((r) => r.data),
    blackouts: (id: string) => apiClient.get<Blackout[]>(`/v1/owner/grounds/${id}/blackouts`).then((r) => r.data),
    addBlackout: (id: string, payload: { startAt: string; endAt: string; reason: string }) =>
      apiClient.post<Blackout>(`/v1/owner/grounds/${id}/blackouts`, payload).then((r) => r.data),
    bookings: (groundId: string, date?: string) => apiClient.get<Booking[]>('/v1/owner/bookings', { params: clean({ groundId, date }) }).then((r) => r.data),
    cancel: (bookingId: string) => apiClient.post<CancelResult>(`/v1/owner/bookings/${bookingId}/cancel`).then((r) => r.data),
    noShow: (bookingId: string) => apiClient.post<Booking>(`/v1/owner/bookings/${bookingId}/no-show`).then((r) => r.data),
  },
};

/** Roles come from the signed token. This only decides whether to show the owner link; the server is the control. */
export const isGroundOwner = (roles?: string[]) => !!roles?.some((r) => r === 'GROUND_OWNER' || r === 'ROLE_GROUND_OWNER');
