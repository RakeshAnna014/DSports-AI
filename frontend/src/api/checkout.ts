import apiClient from './client';
import type { CheckoutResponse, CreateCheckoutResponse, SelectAddressPayload, SelectDeliveryMethodPayload } from '@/types/checkout';

export const checkoutApi = {
  create: () =>
    apiClient.post<CreateCheckoutResponse>('/v1/checkout').then((r) => r.data),

  getById: (id: string) =>
    apiClient.get<CheckoutResponse>(`/v1/checkout/${id}`).then((r) => r.data),

  getActive: () =>
    apiClient.get<CheckoutResponse>('/v1/checkout/active').then((r) => r.data),

  selectAddress: (checkoutId: string, payload: SelectAddressPayload) =>
    apiClient.post<CheckoutResponse>(`/v1/checkout/${checkoutId}/address`, payload).then((r) => r.data),

  selectDeliveryMethod: (checkoutId: string, payload: SelectDeliveryMethodPayload) =>
    apiClient.post<CheckoutResponse>(`/v1/checkout/${checkoutId}/delivery`, payload).then((r) => r.data),

  validate: (checkoutId: string) =>
    apiClient.post<CheckoutResponse>(`/v1/checkout/${checkoutId}/validate`).then((r) => r.data),

  cancel: (checkoutId: string) =>
    apiClient.post<CheckoutResponse>(`/v1/checkout/${checkoutId}/cancel`).then((r) => r.data),
};
