import type { Price, InventoryRecord } from '@/types/catalog';

export const getProductImageUrl = (name: string, images?: { url: string; primary?: boolean }[]): string => {
  if (images && images.length > 0) {
    const primary = images.find((img) => img.primary);
    return primary?.url ?? images[0].url;
  }
  const palette = [
    { bg: 'E8F5E9', fg: '1B5E20' },
    { bg: 'E3F2FD', fg: '0D47A1' },
    { bg: 'FCE4EC', fg: '880E4F' },
    { bg: 'FFF3E0', fg: 'E65100' },
    { bg: 'EDE7F6', fg: '4527A0' },
    { bg: 'FFF8E1', fg: 'FF8F00' },
  ];
  const c = palette[name.length % palette.length];
  return `https://placehold.co/400x400/${c.bg}/${c.fg}?text=${encodeURIComponent(name)}`;
};

export const computeDiscount = (mrp: number, sellingPrice: number): number =>
  Math.round(((mrp - sellingPrice) / mrp) * 100);

export const formatPrice = (amount: number, currency = 'USD'): string =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount);

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
