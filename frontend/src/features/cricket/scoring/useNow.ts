import { useEffect, useState } from 'react';

/** The current time in milliseconds, refreshed on an interval, so a countdown on screen keeps ticking without a reload. */
export const useNow = (everyMs = 15000): number => {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(timer);
  }, [everyMs]);
  return now;
};

/** Whole minutes left until a moment, rounded up so the last minute reads "1 min" and never "0 min" before the deadline; null when there is none. */
export const minutesUntil = (until: string | null, now: number): number | null => {
  if (!until) {
    return null;
  }
  return Math.max(0, Math.ceil((new Date(until).getTime() - now) / 60000));
};

export const formatLeft = (minutes: number): string => (minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`);

/** The last hour is when the scorer is warned that the correction window is about to close. */
export const WARN_MINUTES = 60;
