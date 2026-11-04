import apiClient from './client';
import type {
  PaymentResponse,
  PaymentSummaryResponse,
  CreatePaymentPayload,
  RefundRequestPayload,
  RefundResponse,
} from '@/types/payments';

export const paymentsApi = {
  /** Authorizes the order total. Returns the open attempt if one already exists for the order. */
  createPayment: (payload: CreatePaymentPayload) =>
    apiClient.post<PaymentResponse>('/v1/payments', payload).then((r) => r.data),

  /** Captures an authorized payment and confirms the order. Safe to retry. */
  capturePayment: (id: string) =>
    apiClient.post<PaymentResponse>(`/v1/payments/${id}/capture`).then((r) => r.data),

  getPayment: (id: string) =>
    apiClient.get<PaymentResponse>(`/v1/payments/${id}`).then((r) => r.data),

  getPaymentHistory: () =>
    apiClient.get<PaymentSummaryResponse[]>('/v1/payments/history').then((r) => r.data),

  cancelPayment: (id: string) =>
    apiClient.post<PaymentResponse>(`/v1/payments/${id}/cancel`).then((r) => r.data),

  /** Records a refund request for review; the same idempotency key must be reused when retrying. */
  requestRefund: (paymentId: string, payload: RefundRequestPayload, idempotencyKey: string) =>
    apiClient
      .post<RefundResponse>(`/v1/payments/${paymentId}/refund-requests`, payload, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .then((r) => r.data),

  getRefunds: (paymentId: string) =>
    apiClient.get<RefundResponse[]>(`/v1/payments/${paymentId}/refunds`).then((r) => r.data),
};
