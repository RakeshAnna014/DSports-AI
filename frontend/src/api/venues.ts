import apiClient from './client';
import type {
  Blackout, Booking, CancelResult, Court, CourtInput, GroundOwner, GroundOwnerAudit, ManagedVenue, Slot, Sport, VenueDetail, VenueInput, VenueSummary,
} from '@/types/venues';

const clean = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== ''));

/** The management calls are the same for an owner (their own venues) and an administrator (any venue); only the path prefix differs. The backend enforces the role and the ownership on every call. */
const manage = (prefix: '/v1/owner' | '/v1/admin') => ({
  venues: () => apiClient.get<ManagedVenue[]>(`${prefix}/venues`).then((r) => r.data),
  create: (venue: VenueInput, ownerId?: string) => apiClient.post<ManagedVenue>(`${prefix}/venues`, { venue, ownerId }).then((r) => r.data),
  update: (id: string, venue: VenueInput, active: boolean, expectedVersion: number, ownerId?: string) =>
    apiClient.put<ManagedVenue>(`${prefix}/venues/${id}`, { venue, active, expectedVersion, ownerId }).then((r) => r.data),
  addCourt: (venueId: string, court: CourtInput) => apiClient.post<Court>(`${prefix}/venues/${venueId}/courts`, court).then((r) => r.data),
  updateCourt: (venueId: string, courtId: string, court: CourtInput, active: boolean, expectedVersion: number) =>
    apiClient.put<Court>(`${prefix}/venues/${venueId}/courts/${courtId}`, { court, active, expectedVersion }).then((r) => r.data),
  blackouts: (venueId: string) => apiClient.get<Blackout[]>(`${prefix}/venues/${venueId}/blackouts`).then((r) => r.data),
  addBlackout: (venueId: string, payload: { courtId: string | null; startAt: string; endAt: string; reason: string }) =>
    apiClient.post<Blackout>(`${prefix}/venues/${venueId}/blackouts`, payload).then((r) => r.data),
});

/** Browsing is public; everything else needs a sign-in. Owner routes need the GROUND_OWNER role and admin routes ADMIN: the backend enforces both on every call. */
export const venuesApi = {
  /** The sports that can be booked (the catalogue's `bookable` ones). */
  sports: () => apiClient.get<Sport[]>('/catalog/sports', { params: { bookable: true } }).then((r) => r.data),
  search: (params: { sport?: string; city?: string; date?: string; from?: string; to?: string }) =>
    apiClient.get<VenueSummary[]>('/v1/venues', { params: clean(params) }).then((r) => r.data),
  get: (id: string) => apiClient.get<VenueDetail>(`/v1/venues/${id}`).then((r) => r.data),
  slots: (venueId: string, courtId: string, date: string) =>
    apiClient.get<Slot[]>(`/v1/venues/${venueId}/courts/${courtId}/slots`, { params: { date } }).then((r) => r.data),

  /** One Idempotency-Key per attempt: a double click or a retry returns the same booking instead of holding two slots. */
  hold: (payload: { courtId: string; sportId: string; startAt: string; endAt: string }, idempotencyKey: string) =>
    apiClient.post<Booking>('/v1/bookings', payload, { headers: { 'Idempotency-Key': idempotencyKey } }).then((r) => r.data),
  myBookings: () => apiClient.get<Booking[]>('/v1/me/bookings').then((r) => r.data),
  getBooking: (id: string) => apiClient.get<Booking>(`/v1/bookings/${id}`).then((r) => r.data),
  cancel: (id: string) => apiClient.post<CancelResult>(`/v1/bookings/${id}/cancel`).then((r) => r.data),

  owner: {
    ...manage('/v1/owner'),
    bookings: (venueId: string, params: { courtId?: string; date?: string }) =>
      apiClient.get<Booking[]>(`/v1/owner/venues/${venueId}/bookings`, { params: clean(params) }).then((r) => r.data),
    cancel: (bookingId: string) => apiClient.post<CancelResult>(`/v1/owner/bookings/${bookingId}/cancel`).then((r) => r.data),
    noShow: (bookingId: string) => apiClient.post<Booking>(`/v1/owner/bookings/${bookingId}/no-show`).then((r) => r.data),
  },
  admin: manage('/v1/admin'),
};

/** Administrators: who is a ground owner, and the sports that can be booked. */
export const adminApi = {
  owners: () => apiClient.get<GroundOwner[]>('/v1/admin/ground-owners').then((r) => r.data),
  grantOwner: (email: string) => apiClient.post<{ id: string; email: string; changed: boolean }>('/v1/admin/ground-owners', { email }).then((r) => r.data),
  removeOwner: (userId: string) => apiClient.delete<void>(`/v1/admin/ground-owners/${userId}`).then(() => undefined),
  ownerAudit: () => apiClient.get<GroundOwnerAudit[]>('/v1/admin/ground-owners/audit', { params: { limit: 50 } }).then((r) => r.data),
  sports: () => apiClient.get<Sport[]>('/admin/catalog/sports').then((r) => r.data),
  addSport: (input: { name: string; slug: string; description?: string; bookable: boolean }) =>
    apiClient.post<Sport>('/admin/catalog/sports', input).then((r) => r.data),
  setSportBookable: (sport: Sport, bookable: boolean) =>
    apiClient.put<Sport>(`/admin/catalog/sports/${sport.id}`, { name: sport.name, slug: sport.slug, description: sport.description ?? undefined, bookable }).then((r) => r.data),
};

/** Roles come from the signed token. These only decide which links to show; the server is the control. */
export const isGroundOwner = (roles?: string[]) => !!roles?.some((r) => r === 'GROUND_OWNER' || r === 'ROLE_GROUND_OWNER');
export const isAdmin = (roles?: string[]) => !!roles?.some((r) => r === 'ADMIN' || r === 'ROLE_ADMIN');
