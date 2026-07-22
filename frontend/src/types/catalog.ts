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
  altText: string | null;
  isPrimary: boolean;
  sortOrder: number;
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
