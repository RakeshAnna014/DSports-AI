import { create } from 'zustand';
import { notificationsApi, type NotificationResult } from '@/api/notifications';

interface NotificationState {
  notifications: NotificationResult[];
  totalCount: number;
  unreadCount: number;
  loading: boolean;
  fetchHistory: (page?: number, size?: number) => Promise<void>;
  retryNotification: (id: string) => Promise<NotificationResult>;
}

export const useNotificationStore = create<NotificationState>((set) => ({
  notifications: [],
  totalCount: 0,
  unreadCount: 0,
  loading: false,

  fetchHistory: async (page = 0, size = 20) => {
    set({ loading: true });
    try {
      const result = await notificationsApi.getHistory(page, size);
      set({
        notifications: result.notifications,
        totalCount: result.totalCount,
        unreadCount: result.notifications.filter((n) => n.status === 'SENT').length,
        loading: false,
      });
    } catch {
      set({ loading: false });
    }
  },

  retryNotification: async (id: string) => {
    const updated = await notificationsApi.retry(id);
    set((state) => ({
      notifications: state.notifications.map((n) => (n.id === id ? updated : n)),
    }));
    return updated;
  },
}));
