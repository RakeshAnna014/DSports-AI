import { create } from 'zustand';
import { useAuthStore } from '@/store/authStore';
import type { WishlistItemResponse } from '@/types/wishlist';
import { wishlistApi } from '@/api/wishlist';
import { getProductImageUrl } from '@/lib/productUtils';

export interface WishlistItem {
  itemId: string;
  productId: string;
  name: string;
  price: number;
  imageUrl: string;
  addedAt: string;
}

interface WishlistState {
  items: WishlistItem[];
  totalItems: number;
  wishlistId: string | null;
  initialized: boolean;
  loading: boolean;
  refreshWishlist: () => Promise<void>;
  addItem: (productId: string) => Promise<boolean>;
  removeItem: (itemId: string) => Promise<void>;
  moveToCart: (itemId: string) => Promise<void>;
  clearWishlist: () => Promise<void>;
}

const toWishlistItem = (item: WishlistItemResponse): WishlistItem => ({
  itemId: item.id,
  productId: item.productId,
  name: item.productName,
  price: item.currentPrice,
  imageUrl: item.productImage || getProductImageUrl(item.productName),
  addedAt: item.addedAt,
});

export const useWishlistStore = create<WishlistState>((set, get) => ({
  items: [],
  totalItems: 0,
  wishlistId: null,
  initialized: false,
  loading: false,

  refreshWishlist: async () => {
    const { isAuthenticated } = useAuthStore.getState();
    if (!isAuthenticated) {
      set({ items: [], totalItems: 0, initialized: true, loading: false });
      return;
    }
    try {
      set({ loading: true });
      const wishlist = await wishlistApi.getWishlist();
      set({
        items: wishlist.items.map(toWishlistItem),
        totalItems: wishlist.totalItems,
        wishlistId: wishlist.id,
        initialized: true,
        loading: false,
      });
    } catch {
      set({ items: [], totalItems: 0, wishlistId: null, initialized: true, loading: false });
    }
  },

  addItem: async (productId) => {
    try {
      const wishlist = await wishlistApi.addItem(productId);
      set({
        items: wishlist.items.map(toWishlistItem),
        totalItems: wishlist.totalItems,
        wishlistId: wishlist.id,
      });
      return true;
    } catch {
      await get().refreshWishlist();
      return false;
    }
  },

  removeItem: async (itemId) => {
    try {
      const wishlist = await wishlistApi.removeItem(itemId);
      set({
        items: wishlist.items.map(toWishlistItem),
        totalItems: wishlist.totalItems,
        wishlistId: wishlist.id,
      });
    } catch {
      await get().refreshWishlist();
    }
  },

  moveToCart: async (itemId) => {
    try {
      const wishlist = await wishlistApi.moveToCart(itemId);
      set({
        items: wishlist.items.map(toWishlistItem),
        totalItems: wishlist.totalItems,
        wishlistId: wishlist.id,
      });
    } catch {
      await get().refreshWishlist();
    }
  },

  clearWishlist: async () => {
    try {
      const wishlist = await wishlistApi.clearWishlist();
      set({
        items: wishlist.items.map(toWishlistItem),
        totalItems: wishlist.totalItems,
        wishlistId: wishlist.id,
      });
    } catch {
      await get().refreshWishlist();
    }
  },
}));
