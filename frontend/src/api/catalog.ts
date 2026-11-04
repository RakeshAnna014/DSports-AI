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

  /** Every active product. The API serves at most 100 per page, so this walks the pages. */
  getAllProducts: async (): Promise<ProductSummary[]> => {
    const all: ProductSummary[] = [];
    for (let page = 0; page < 50; page++) {
      const rows = await apiClient.get<ProductSummary[]>('/catalog/products', { params: { page, size: 100 } }).then((r) => r.data);
      all.push(...rows);
      if (rows.length < 100) break;
    }
    return all;
  },

  /** Structured facts (skill level, kit role, features ...) of every product, keyed by product id. One request. */
  getAllAttributes: () =>
    apiClient.get<Record<string, Record<string, string>>>('/catalog/product-attributes').then((r) => r.data),

  getProduct: (id: string) =>
    apiClient.get<ProductDetail>(`/catalog/products/${id}`).then((r) => r.data),

  getSports: () =>
    apiClient.get<Sport[]>('/catalog/sports').then((r) => r.data),

  getCategories: () =>
    apiClient.get<Category[]>('/catalog/categories').then((r) => r.data),

  getBrands: () =>
    apiClient.get<Brand[]>('/catalog/brands').then((r) => r.data),
};
