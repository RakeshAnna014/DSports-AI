import apiClient from './client';
import type { InventoryRecord } from '@/types/catalog';

export const inventoryApi = {
  getInventory: (productId?: string) => {
    const url = productId ? `/inventory/${productId}` : '/inventory';
    return apiClient.get<InventoryRecord[]>(url).then((r) => r.data);
  },
};
