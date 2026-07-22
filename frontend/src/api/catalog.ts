import apiClient from './client';
import type { ProductDetail, ProductSummary, Sport, Category, Brand } from '@/types/catalog';

interface ProductsQuery {
  brandId?: string;
  categoryId?: string;
  sportId?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDir?: string;
}

export const catalogApi = {
  getProducts: (params?: ProductsQuery) =>
    apiClient.get<ProductSummary[]>('/catalog/products', { params }).then((r) => r.data),

  getProduct: (id: string) =>
    apiClient.get<ProductDetail>(`/catalog/products/${id}`).then((r) => r.data),

  getSports: () =>
    apiClient.get<Sport[]>('/catalog/sports').then((r) => r.data),

  getCategories: () =>
    apiClient.get<Category[]>('/catalog/categories').then((r) => r.data),

  getBrands: () =>
    apiClient.get<Brand[]>('/catalog/brands').then((r) => r.data),
};
