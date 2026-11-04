import apiClient from './client';
import type { WishlistResponse, WishlistSummaryResponse } from '@/types/wishlist';

export interface AddWishlistItemPayload {
  productId: string;
}

export const wishlistApi = {
  getWishlist: () =>
    apiClient.get<WishlistResponse>('/v1/wishlist').then((r) => r.data),

  getWishlistCount: () =>
    apiClient.get<WishlistSummaryResponse>('/v1/wishlist/count').then((r) => r.data),

  addItem: (productId: string) =>
    apiClient.post<WishlistResponse>('/v1/wishlist/items', { productId } as AddWishlistItemPayload).then((r) => r.data),

  removeItem: (itemId: string) =>
    apiClient.delete<WishlistResponse>(`/v1/wishlist/items/${itemId}`).then((r) => r.data),

  moveToCart: (itemId: string) =>
    apiClient.post<WishlistResponse>(`/v1/wishlist/items/${itemId}/move-to-cart`).then((r) => r.data),

  clearWishlist: () =>
    apiClient.delete<WishlistResponse>('/v1/wishlist').then((r) => r.data),
};
