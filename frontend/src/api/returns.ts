import apiClient from './client';

export interface ReturnRequestResponse {
  id: string;
  orderId: string;
  productId: string;
  productName: string;
  quantity: number;
  reason: string;
  status: string;
  decisionNote: string | null;
  createdAt: string;
  updatedAt: string;
}

export const returnsApi = {
  /** The signed-in customer's own return requests. */
  list: () => apiClient.get<ReturnRequestResponse[]>('/v1/returns').then((r) => r.data),
};
