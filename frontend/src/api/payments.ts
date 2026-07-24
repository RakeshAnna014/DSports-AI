import apiClient from './client';
import type {
  PaymentResponse,
  PaymentSummaryResponse,
  CreatePaymentPayload,
} from '@/types/payments';

export const paymentsApi = {
  createPayment: (payload: CreatePaymentPayload) =>
    apiClient.post<PaymentResponse>('/v1/payments', payload).then((r) => r.data),

  getPayment: (id: string) =>
    apiClient.get<PaymentResponse>(`/v1/payments/${id}`).then((r) => r.data),

  getPaymentHistory: () =>
    apiClient.get<PaymentSummaryResponse[]>('/v1/payments/history').then((r) => r.data),

  cancelPayment: (id: string) =>
    apiClient.post<PaymentResponse>(`/v1/payments/${id}/cancel`).then((r) => r.data),

  refundPayment: (id: string) =>
    apiClient.post<PaymentResponse>(`/v1/payments/${id}/refund`).then((r) => r.data),
};
