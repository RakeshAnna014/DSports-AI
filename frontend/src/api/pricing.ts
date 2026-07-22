import apiClient from './client';
import type { Price } from '@/types/catalog';

export const pricingApi = {
  getPrices: (productId?: string) =>
    apiClient.get<Price[]>('/prices', { params: productId ? { productId } : {} }).then((r) => r.data),
};
