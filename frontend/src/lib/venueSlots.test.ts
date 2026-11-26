import { describe, expect, it } from 'vitest';
import { clock, localToday, mmss, nextSelection, refundSentence, secondsLeft, selectionSummary, when } from './venueSlots';
import type { CancelResult, Slot } from '@/types/venues';

/** Six hourly slots starting 06:00 IST (00:30Z); the fourth (index 3) is booked. */
const slots: Slot[] = Array.from({ length: 6 }, (_, i) => {
  const start = new Date(Date.UTC(2026, 10, 21, 0, 30) + i * 3600_000);
  const end = new Date(start.getTime() + 3600_000);
  return { start: start.toISOString(), end: end.toISOString(), price: 1000 + i * 100, available: i !== 3, unavailableReason: i === 3 ? 'BOOKED' : null };
});

describe('slot selection', () => {
  it('starts a run with the first click and ignores unavailable slots', () => {
    expect(nextSelection(slots, [], 1)).toEqual([1]);
    expect(nextSelection(slots, [1], 3)).toEqual([1]);
  });

  it('extends the run with a neighbouring slot, on either side, in order', () => {
    expect(nextSelection(slots, [1], 2)).toEqual([1, 2]);
    expect(nextSelection(slots, [1, 2], 0)).toEqual([0, 1, 2]);
  });

  it('starts a new run when the click is not next to the current one', () => {
    expect(nextSelection(slots, [0], 2)).toEqual([2]);
    expect(nextSelection(slots, [1, 2], 5)).toEqual([5]);
  });

  it('does not run across a booked slot', () => {
    expect(nextSelection(slots, [2], 4)).toEqual([4]);
  });

  it('shrinks from an end of the run, and restarts when an inner slot is clicked', () => {
    expect(nextSelection(slots, [0, 1, 2], 2)).toEqual([0, 1]);
    expect(nextSelection(slots, [0, 1, 2], 0)).toEqual([1, 2]);
    expect(nextSelection(slots, [0, 1, 2], 1)).toEqual([1]);
    expect(nextSelection(slots, [1], 1)).toEqual([]);
  });

  it('never grows past the maximum run', () => {
    expect(nextSelection(slots, [0, 1, 2], 3, 3)).toEqual([0, 1, 2]);
    expect(nextSelection(slots, [4], 5, 1)).toEqual([5]);
  });

  it('summarises a run as its first start, last end and the sum of its prices', () => {
    expect(selectionSummary(slots, [])).toBeUndefined();
    expect(selectionSummary(slots, [2, 1])).toEqual({ start: slots[1].start, end: slots[2].end, price: 1100 + 1200, count: 2 });
  });
});

describe('time and money text', () => {
  it('shows times in the ground\'s own zone, not the browser\'s', () => {
    expect(clock('2026-11-21T00:30:00Z', 'Asia/Kolkata')).toBe('06:00');
    expect(clock('2026-11-21T00:30:00Z', 'Europe/London')).toBe('00:30');
    expect(when('2026-11-21T11:30:00Z', 'Asia/Kolkata')).toContain('17:00');
  });

  it('counts the seconds left and never goes negative', () => {
    expect(secondsLeft('2026-11-21T00:10:00Z', Date.parse('2026-11-21T00:07:30Z'))).toBe(150);
    expect(secondsLeft('2026-11-21T00:10:00Z', Date.parse('2026-11-21T00:11:00Z'))).toBe(0);
    expect(mmss(150)).toBe('2:30');
    expect(mmss(5)).toBe('0:05');
  });

  it('says in plain words what a cancellation did about money', () => {
    const booking = { currency: 'INR', paymentId: 'p-1' } as CancelResult['booking'];
    expect(refundSentence({ booking, refundPercent: 50, refundAmount: 1500, refund: 'REQUESTED' })).toMatch(/50% refund of .*1,500.*requested.*awaiting approval/);
    expect(refundSentence({ booking, refundPercent: 100, refundAmount: 3000, refund: 'STARTED' })).toMatch(/full refund .*3,000.*automatically/);
    expect(refundSentence({ booking, refundPercent: 0, refundAmount: 0, refund: 'NONE' })).toMatch(/No refund applies/);
    expect(refundSentence({ booking: { ...booking, paymentId: null }, refundPercent: 0, refundAmount: 0, refund: 'NONE' })).toMatch(/Nothing was charged/);
  });
});

describe('local date', () => {
  it('is today in the browser time zone as YYYY-MM-DD', () => {
    const d = new Date();
    expect(localToday()).toBe(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`);
    expect(localToday()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
