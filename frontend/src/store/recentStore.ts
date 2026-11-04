import { create } from 'zustand';

const KEY = 'dsports_recently_viewed';
const MAX = 8;

const load = (): string[] => {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed.filter((x) => typeof x === 'string').slice(0, MAX) : [];
  } catch {
    return [];
  }
};

interface RecentState {
  ids: string[];
  /** Records a product page view on this device only (a convenience, never sent to a server). */
  view: (productId: string) => void;
}

export const useRecentStore = create<RecentState>((set, get) => ({
  ids: load(),
  view: (productId) => {
    const ids = [productId, ...get().ids.filter((i) => i !== productId)].slice(0, MAX);
    try {
      localStorage.setItem(KEY, JSON.stringify(ids));
    } catch {
      // storage can be unavailable (private mode); the list simply stays in memory
    }
    set({ ids });
  },
}));
