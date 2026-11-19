import { useCallback, useEffect, useRef, useState } from 'react';
import { newId } from './newId';

/**
 * One scorer tab per match per browser. The unsent ball lives in a single saved slot shared by every tab of the browser, so two tabs scoring the same match
 * could overwrite each other's ball. The tab that holds the lease may score; any other tab of the same match is read-only until the lease is free.
 *
 * It uses the browser's local storage (a lease record, refreshed every couple of seconds, plus the {@code storage} event so a tab notices another one at
 * once). Unlike the Web Locks API this works over plain HTTP, which is how a phone reaches the app on a local network. It guards against a second tab in the
 * same browser only: a second scorer on another device is handled by the server (the conflict sheet).
 */
const HEARTBEAT_MS = 2000;
const STALE_MS = 10000;
const SETTLE_MS = 150;

/** Each loaded copy of the page (each tab) has its own id. */
const TAB_ID = newId();

interface Lease {
  tab: string;
  at: number;
}

const keyOf = (matchId: string) => `dsports.scoring.tab.${matchId}`;

const read = (key: string): Lease | null => {
  try {
    const raw = localStorage.getItem(key);
    const parsed = raw ? (JSON.parse(raw) as Lease) : null;
    return parsed && typeof parsed.tab === 'string' && typeof parsed.at === 'number' ? parsed : null;
  } catch {
    return null;
  }
};

const write = (key: string): void => {
  try {
    localStorage.setItem(key, JSON.stringify({ tab: TAB_ID, at: Date.now() } satisfies Lease));
  } catch {
    // Storage blocked: the page cannot tell other tabs apart and simply scores.
  }
};

const heldByAnother = (lease: Lease | null): boolean => lease !== null && lease.tab !== TAB_ID && Date.now() - lease.at < STALE_MS;

/** Takes the lease if nobody else holds it. */
const claim = (key: string): boolean => {
  if (heldByAnother(read(key))) {
    return false;
  }
  write(key);
  return true;
};

/**
 * {@code owner} says whether this tab may score. {@code recheck} reads the lease again right now and returns whether this tab still holds it; it is called
 * before anything is sent, because a background tab on a phone can be frozen for minutes (its timers and storage events do not run) while another tab takes over.
 */
export const useTabLease = (matchId: string): { owner: boolean; recheck: () => boolean } => {
  const key = keyOf(matchId);
  const [owner, setOwner] = useState(() => claim(key));
  const ownerRef = useRef(owner);
  useEffect(() => {
    ownerRef.current = owner;
  });

  const recheck = useCallback((): boolean => {
    if (ownerRef.current) {
      if (heldByAnother(read(key))) {
        ownerRef.current = false;
        setOwner(false);
        return false;
      }
      write(key);
      return true;
    }
    if (claim(key)) {
      ownerRef.current = true;
      setOwner(true);
      return true;
    }
    return false;
  }, [key]);

  useEffect(() => {
    // Two tabs opened in the same instant both claimed: after a moment the one whose write survived keeps it, and the other steps aside.
    const settle = setTimeout(() => {
      if (ownerRef.current && read(key)?.tab !== TAB_ID) {
        setOwner(false);
      }
    }, SETTLE_MS);
    const check = () => {
      recheck();
    };
    const heartbeat = setInterval(check, HEARTBEAT_MS);
    // A tab that comes back to the foreground looks at the lease before anything else happens.
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        recheck();
      }
    };
    const onStorage = (e: StorageEvent) => {
      if (e.key === key || e.key === null) {
        check();
      }
    };
    const release = () => {
      if (read(key)?.tab === TAB_ID) {
        try {
          localStorage.removeItem(key);
        } catch {
          // nothing to do
        }
      }
    };
    window.addEventListener('storage', onStorage);
    window.addEventListener('pagehide', release);
    window.addEventListener('focus', onVisible);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearTimeout(settle);
      clearInterval(heartbeat);
      window.removeEventListener('storage', onStorage);
      window.removeEventListener('pagehide', release);
      window.removeEventListener('focus', onVisible);
      document.removeEventListener('visibilitychange', onVisible);
      release();
    };
  }, [key, recheck]);

  return { owner, recheck };
};
