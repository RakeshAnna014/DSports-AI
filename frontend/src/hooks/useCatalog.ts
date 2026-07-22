import { useQuery } from '@tanstack/react-query';
import { catalogApi } from '@/api/catalog';
import { pricingApi } from '@/api/pricing';
import { inventoryApi } from '@/api/inventory';
import type { ProductsQuery } from '@/types/catalog';

export const useProducts = (params?: ProductsQuery) =>
  useQuery({
    queryKey: ['products', params],
    queryFn: () => catalogApi.getProducts(params),
  });

export const useProductDetail = (id: string) =>
  useQuery({
    queryKey: ['product', id],
    queryFn: () => catalogApi.getProduct(id),
    enabled: !!id,
  });

export const usePrices = (productId?: string) =>
  useQuery({
    queryKey: ['prices', productId],
    queryFn: () => pricingApi.getPrices(productId),
  });

export const useInventory = (productId?: string) =>
  useQuery({
    queryKey: ['inventory', productId],
    queryFn: () => inventoryApi.getInventory(productId),
    retry: (failureCount, error) => {
      if (error && 'response' in error && (error as { response: { status: number } }).response.status === 401)
        return false;
      return failureCount < 2;
    },
  });

export const useSports = () =>
  useQuery({
    queryKey: ['sports'],
    queryFn: () => catalogApi.getSports(),
    staleTime: 300_000,
  });

export const useCategories = () =>
  useQuery({
    queryKey: ['categories'],
    queryFn: () => catalogApi.getCategories(),
    staleTime: 300_000,
  });

export const useBrands = () =>
  useQuery({
    queryKey: ['brands'],
    queryFn: () => catalogApi.getBrands(),
    staleTime: 300_000,
  });
