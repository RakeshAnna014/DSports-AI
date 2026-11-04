import type { InventoryRecord } from '@/types/catalog';

/** Shown when a product has no image of its own: a DSports visual, never the product name painted on a coloured block. */
export const FALLBACK_IMAGE = '/products/placeholder.svg';

export const getProductImageUrl = (_name: string, images?: { url: string; primary?: boolean }[]): string => {
  if (images && images.length > 0) {
    const primary = images.find((img) => img.primary);
    return primary?.url ?? images[0].url;
  }
  return FALLBACK_IMAGE;
};

export const computeDiscount = (mrp: number, sellingPrice: number): number =>
  Math.round(((mrp - sellingPrice) / mrp) * 100);

export const formatPrice = (amount: number, currency = 'INR'): string =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(amount);

export const formatDate = (iso: string): string =>
  new Date(iso).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

export type StockLevel = 'in_stock' | 'low_stock' | 'out_of_stock';

export const computeStockLevel = (inventory?: InventoryRecord[]): { level: StockLevel; label: string } => {
  if (!inventory || inventory.length === 0) return { level: 'out_of_stock', label: 'Out of Stock' };
  const total = inventory.reduce((sum, r) => sum + r.availableQuantity, 0);
  if (total === 0) return { level: 'out_of_stock', label: 'Out of Stock' };
  if (total <= 10) return { level: 'low_stock', label: `Only ${total} Left` };
  return { level: 'in_stock', label: 'In Stock' };
};
