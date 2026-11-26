import type { CancelResult, Slot } from '@/types/venues';
import { formatPrice } from '@/lib/productUtils';

/** The longest run of consecutive slots offered by the UI. The server enforces its own (configurable) limit and is the authority. */
export const MAX_SLOTS = 4;

/**
 * The next selection after clicking slot {@code index}. A selection is always a run of consecutive, available slots: clicking a slot next to the
 * run extends it (up to {@code maxSlots}), clicking an end of the run shrinks it, and anything else starts a new run.
 */
export const nextSelection = (slots: Slot[], selected: number[], index: number, maxSlots = MAX_SLOTS): number[] => {
  const slot = slots[index];
  if (!slot?.available) return selected;
  if (selected.length === 0) return [index];
  const low = Math.min(...selected);
  const high = Math.max(...selected);
  if (selected.includes(index)) return index === low || index === high ? selected.filter((i) => i !== index) : [index];
  const touchesAbove = index === high + 1 && slots[high].end === slot.start;
  const touchesBelow = index === low - 1 && slot.end === slots[low].start;
  if ((touchesAbove || touchesBelow) && selected.length < maxSlots) return [...selected, index].sort((a, b) => a - b);
  return [index];
};

/** The start, end and total price of a selection (undefined when nothing is selected). */
export const selectionSummary = (slots: Slot[], selected: number[]) => {
  if (selected.length === 0) return undefined;
  const run = [...selected].sort((a, b) => a - b).map((i) => slots[i]);
  return { start: run[0].start, end: run[run.length - 1].end, price: run.reduce((sum, s) => sum + s.price, 0), count: run.length };
};

/** A time of day in the venue's own zone, e.g. "17:00". */
export const clock = (iso: string, timeZone: string) =>
  new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone }).format(new Date(iso));

/** A date and time in the venue's own zone, e.g. "Wed 18 Nov, 17:00". */
export const when = (iso: string, timeZone: string) =>
  new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone }).format(new Date(iso));

/** Whole seconds left until an ISO instant (never negative). */
export const secondsLeft = (iso: string, now = Date.now()) => Math.max(0, Math.floor((new Date(iso).getTime() - now) / 1000));

/** "m:ss" for a number of seconds. */
export const mmss = (seconds: number) => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;

/** What a cancellation did about money, in plain words. The numbers come from the server. */
export const refundSentence = (r: CancelResult) => {
  if (r.refund === 'REQUESTED') return `A ${r.refundPercent}% refund of ${formatPrice(r.refundAmount, r.booking.currency)} has been requested and is awaiting approval.`;
  if (r.refund === 'STARTED') return `A full refund of ${formatPrice(r.refundAmount, r.booking.currency)} has been started automatically.`;
  return r.booking.paymentId ? 'No refund applies under the cancellation policy.' : 'The slot has been released. Nothing was charged.';
};

/** Today's date as YYYY-MM-DD in the browser's own zone (not UTC, which is the previous day for Indian users until 05:30). */
export const localToday = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
