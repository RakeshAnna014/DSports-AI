import { AxiosError } from 'axios';
import type { Booking, Court, ManagedVenue, Slot, SportRef, Venue } from '@/types/venues';

export const CRICKET: SportRef = { id: 's-cricket', name: 'Cricket', slug: 'cricket', bookable: true };
export const FOOTBALL: SportRef = { id: 's-football', name: 'Football', slug: 'football', bookable: true };
export const BADMINTON: SportRef = { id: 's-badminton', name: 'Badminton', slug: 'badminton', bookable: true };

export const VENUE: Venue = {
  id: 'v-1', name: 'Green Park Sports Hub', address: '12 Park Road', city: 'Bengaluru', latitude: null, longitude: null, facilities: ['Floodlights'], photoUrls: [],
  opensAt: '06:00:00', closesAt: '22:00:00', timeZone: 'Asia/Kolkata', currency: 'INR', active: true,
};

export const COURT_1: Court = {
  id: 'c-1', venueId: 'v-1', name: 'Court 1', surface: 'turf', sports: [CRICKET, FOOTBALL], slotMinutes: 60, basePrice: 1000, weekendPrice: 1500, peakPrice: 2000,
  peakFrom: '18:00:00', peakTo: '21:00:00', active: true, version: 2,
};

export const COURT_2: Court = {
  id: 'c-2', venueId: 'v-1', name: 'Hall', surface: 'wood', sports: [BADMINTON], slotMinutes: 60, basePrice: 600, weekendPrice: null, peakPrice: null,
  peakFrom: null, peakTo: null, active: true, version: 1,
};

export const MANAGED: ManagedVenue = { venue: VENUE, ownerId: 'o-1', ownerEligible: true, version: 3, courts: [COURT_1, COURT_2] };

/** 06:00 IST onwards, hourly, 1000 each; the slot at index 2 (08:00) is booked. */
export const SLOTS: Slot[] = Array.from({ length: 5 }, (_, i) => {
  const start = new Date(Date.UTC(2026, 10, 21, 0, 30) + i * 3600_000);
  return { start: start.toISOString(), end: new Date(start.getTime() + 3600_000).toISOString(), price: 1000, available: i !== 2, unavailableReason: i === 2 ? 'BOOKED' : null };
});

export const BOOKING: Booking = {
  id: 'b-1', bookingNumber: 'GB-000001', venueId: 'v-1', venueName: 'Green Park Sports Hub', venueCity: 'Bengaluru', courtId: 'c-1', courtName: 'Court 1',
  sportId: 's-cricket', sportName: 'Cricket', startAt: '2099-11-21T11:30:00Z', endAt: '2099-11-21T12:30:00Z', price: 1000, currency: 'INR', status: 'CONFIRMED',
  heldUntil: '2099-11-16T05:00:00Z', paymentId: 'pay-1', cancelledAt: null, cancelledBy: null, createdAt: '2099-11-16T04:30:00Z',
};

export const apiError = (status: number, code: string, message: string) =>
  new AxiosError('failed', String(status), undefined, undefined, { status, data: { code, message, status } } as never);
