/** Mirrors the ground-booking API (backend/ground). Money is in rupees as numbers, times are ISO instants; wall-clock fields are "HH:mm:ss" in the ground's time zone. */
export type PitchType = 'TURF' | 'MATTING' | 'CONCRETE' | 'NATURAL_GRASS' | 'INDOOR';

export interface Ground {
  id: string;
  name: string;
  address: string;
  city: string;
  latitude: number | null;
  longitude: number | null;
  pitchType: PitchType;
  facilities: string[];
  photoUrls: string[];
  opensAt: string;
  closesAt: string;
  timeZone: string;
  slotMinutes: number;
  basePrice: number;
  weekendPrice: number | null;
  peakPrice: number | null;
  peakFrom: string | null;
  peakTo: string | null;
  currency: string;
  active: boolean;
}

export interface GroundSummary {
  ground: Ground;
  availableSlots: number | null;
  lowestAvailablePrice: number | null;
}

export type UnavailableReason = 'BOOKED' | 'BLACKED_OUT' | 'TOO_SOON' | 'TOO_FAR';

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
  groundId: string;
  groundName: string | null;
  groundCity: string | null;
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

export interface OwnerGround {
  ground: Ground;
  version: number;
}

export interface Blackout {
  id: string;
  groundId: string;
  startAt: string;
  endAt: string;
  reason: string;
  createdBy: string;
  createdAt: string;
}

/** The editable details of a ground (what the owner form sends). */
export interface GroundInput {
  name: string;
  address: string;
  city: string;
  latitude: number | null;
  longitude: number | null;
  pitchType: PitchType;
  facilities: string[];
  photoUrls: string[];
  opensAt: string;
  closesAt: string;
  timeZone: string;
  slotMinutes: number;
  basePrice: number;
  weekendPrice: number | null;
  peakPrice: number | null;
  peakFrom: string | null;
  peakTo: string | null;
}
