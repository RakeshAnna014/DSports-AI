/** Mirrors the venue-booking API (backend/ground). Money is in rupees as numbers, times are ISO instants; wall-clock fields are "HH:mm:ss" in the venue's time zone. */

/** A catalogue sport. A bookable one can be offered on a court. */
export interface Sport {
  id: string;
  name: string;
  slug: string;
  description?: string | null;
  status?: string;
  bookable: boolean;
}

export type SportRef = Pick<Sport, 'id' | 'name' | 'slug' | 'bookable'>;

export interface Venue {
  id: string;
  name: string;
  address: string;
  city: string;
  latitude: number | null;
  longitude: number | null;
  facilities: string[];
  photoUrls: string[];
  opensAt: string;
  closesAt: string;
  timeZone: string;
  currency: string;
  active: boolean;
}

export interface Court {
  id: string;
  venueId: string;
  name: string;
  surface: string | null;
  sports: SportRef[];
  slotMinutes: number;
  basePrice: number;
  weekendPrice: number | null;
  peakPrice: number | null;
  peakFrom: string | null;
  peakTo: string | null;
  active: boolean;
  version: number;
}

export interface CourtAvailability {
  courtId: string;
  name: string;
  availableSlots: number | null;
  lowestAvailablePrice: number | null;
}

/** A search hit: the venue and its matching courts; the free-slot figures are null unless the search named a date. */
export interface VenueSummary {
  venue: Venue;
  courts: CourtAvailability[];
  availableSlots: number | null;
  lowestAvailablePrice: number | null;
}

/** A venue page: its active courts; {@code acceptingBookings} is false while its owner no longer holds the ground-owner role. */
export interface VenueDetail {
  venue: Venue;
  courts: Court[];
  acceptingBookings: boolean;
}

/** A venue as its owner or an administrator sees it. */
export interface ManagedVenue {
  venue: Venue;
  ownerId: string;
  ownerEligible: boolean;
  version: number;
  courts: Court[];
}

export type UnavailableReason = 'BOOKED' | 'BLACKED_OUT' | 'TOO_SOON' | 'TOO_FAR' | 'NOT_ACCEPTING';

export interface Slot {
  start: string;
  end: string;
  price: number;
  available: boolean;
  unavailableReason: UnavailableReason | null;
}

export type BookingStatus = 'HELD' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED' | 'COMPLETED' | 'NO_SHOW';

export interface Booking {
  id: string;
  bookingNumber: string;
  venueId: string;
  venueName: string | null;
  venueCity: string | null;
  courtId: string;
  courtName: string | null;
  sportId: string;
  sportName: string | null;
  startAt: string;
  endAt: string;
  price: number;
  currency: string;
  status: BookingStatus;
  heldUntil: string;
  paymentId: string | null;
  cancelledAt: string | null;
  cancelledBy: 'CUSTOMER' | 'OWNER' | null;
  createdAt: string;
}

/** What a cancellation did about money: NONE (nothing to refund), REQUESTED (awaiting admin approval) or STARTED (automatic, being paid out). */
export interface CancelResult {
  booking: Booking;
  refundPercent: number;
  refundAmount: number;
  refund: 'NONE' | 'REQUESTED' | 'STARTED';
}

export interface Blackout {
  id: string;
  venueId: string;
  courtId: string | null;
  startAt: string;
  endAt: string;
  reason: string;
  createdBy: string;
  createdAt: string;
}

/** The editable details of a venue (what the form sends). */
export interface VenueInput {
  name: string;
  address: string;
  city: string;
  latitude: number | null;
  longitude: number | null;
  facilities: string[];
  photoUrls: string[];
  opensAt: string;
  closesAt: string;
  timeZone: string;
}

/** The editable details of a court. */
export interface CourtInput {
  name: string;
  surface: string | null;
  sportIds: string[];
  slotMinutes: number;
  basePrice: number;
  weekendPrice: number | null;
  peakPrice: number | null;
  peakFrom: string | null;
  peakTo: string | null;
}

/** Someone who currently holds the ground-owner role. */
export interface GroundOwner {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  grantedAt: string | null;
}

export interface GroundOwnerAudit {
  id: string;
  actorId: string;
  targetId: string;
  targetEmail: string;
  action: 'GRANTED' | 'REVOKED';
  occurredAt: string;
}
