import apiClient from './client';
import type {
  OrderResponse,
  OrderSummaryResponse,
  PlaceOrderPayload,
  PlaceOrderResponse,
  UpdateOrderStatusPayload,
} from '@/types/orders';

export const ordersApi = {
  placeOrder: (payload: PlaceOrderPayload) =>
    apiClient.post<PlaceOrderResponse>('/v1/orders', payload).then((r) => r.data),

  getOrders: () =>
    apiClient.get<OrderSummaryResponse[]>('/v1/orders').then((r) => r.data),

  getOrder: (id: string) =>
    apiClient.get<OrderResponse>(`/v1/orders/${id}`).then((r) => r.data),

  cancelOrder: (id: string) =>
    apiClient.put<OrderResponse>(`/v1/orders/${id}/cancel`).then((r) => r.data),

  // Admin
  adminGetOrder: (id: string) =>
    apiClient.get<OrderResponse>(`/v1/admin/orders/${id}`).then((r) => r.data),

  adminUpdateStatus: (id: string, payload: UpdateOrderStatusPayload) =>
    apiClient.put<OrderResponse>(`/v1/admin/orders/${id}/status`, payload).then((r) => r.data),
};
