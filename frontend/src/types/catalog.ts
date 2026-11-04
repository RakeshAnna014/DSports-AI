export interface ProductSummary {
  id: string;
  sku: string;
  name: string;
  slug: string;
  brandId: string;
  categoryId: string;
  sportId: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  /** The primary image URL (or the first), and all image URLs in display order. */
  imageUrl?: string | null;
  images?: string[];
}

export interface ProductDetail {
  id: string;
  sku: string;
  name: string;
  slug: string;
  description: string;
  brandId: string;
  categoryId: string;
  sportId: string;
  weight: number | null;
  weightUnit: string | null;
  length: number | null;
  width: number | null;
  height: number | null;
  dimensionUnit: string | null;
  status: string;
  images: ProductImage[];
  createdAt: string;
  updatedAt: string;
}

export interface ProductImage {
  id: string;
  url: string;
  displayOrder: number;
  primary: boolean;
}

export interface Price {
  id: string;
  productId: string;
  mrp: number;
  sellingPrice: number;
  currency: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: string;
}

/** Storefront stock summary: availableQuantity is what can still be ordered (on hand minus reserved). */
export interface InventoryRecord {
  productId: string;
  availableQuantity: number;
  status: 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK';
}

export interface ProductsQuery {
  brandId?: string;
  categoryId?: string;
  sportId?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDir?: string;
}

export interface Sport {
  id: string;
  name: string;
  slug: string;
}

export interface Category {
  id: string;
  name: string;
  slug: string;
}

export interface Brand {
  id: string;
  name: string;
  slug: string;
}
