import apiClient from './client';

export interface NotificationResult {
  id: string;
  userId: string;
  type: string;
  channel: string;
  message: string;
  subject: string;
  templateName: string | null;
  status: string;
  referenceId: string | null;
  referenceType: string | null;
  failureReason: string | null;
  retryCount: number;
  maxRetries: number;
  sentAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface NotificationHistoryResult {
  notifications: NotificationResult[];
  totalCount: number;
  page: number;
  size: number;
}

export const notificationsApi = {
  getHistory: (page = 0, size = 20) =>
    apiClient
      .get<NotificationHistoryResult>('/v1/notifications', { params: { page, size } })
      .then((r) => r.data),

  getById: (id: string) =>
    apiClient.get<NotificationResult>(`/v1/notifications/${id}`).then((r) => r.data),

  retry: (id: string) =>
    apiClient.post<NotificationResult>(`/v1/notifications/${id}/retry`).then((r) => r.data),
};
