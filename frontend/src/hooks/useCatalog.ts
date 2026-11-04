import { useMemo } from 'react';
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

/** The whole catalogue (the API pages at 100). */
export const useAllProducts = () =>
  useQuery({
    queryKey: ['products', 'all'],
    queryFn: () => catalogApi.getAllProducts(),
    staleTime: 60_000,
  });

/** Structured facts of every product, keyed by product id. */
export const useProductAttributes = () =>
  useQuery({
    queryKey: ['product-attributes'],
    queryFn: () => catalogApi.getAllAttributes(),
    staleTime: 5 * 60_000,
  });

/** productId -> primary image URL, so any screen that only knows a product id (cart, orders, AI cards) can show its picture. */
export const useProductImageMap = () => {
  const { data } = useAllProducts();
  return useMemo(() => new Map((data ?? []).filter((p) => p.imageUrl).map((p) => [p.id, p.imageUrl as string])), [data]);
};

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
