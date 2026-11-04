export interface WishlistItemResponse {
  id: string;
  productId: string;
  productName: string;
  productImage: string;
  currentPrice: number;
  addedAt: string;
}

export interface WishlistResponse {
  id: string;
  userId: string;
  totalItems: number;
  version: number;
  items: WishlistItemResponse[];
  createdAt: string;
  updatedAt: string;
}

export interface WishlistSummaryResponse {
  id: string;
  totalItems: number;
}
