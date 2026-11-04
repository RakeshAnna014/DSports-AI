import { create } from 'zustand';
import { useAuthStore } from '@/store/authStore';
import { cartApi, type CartItemResponse, type CartResponse } from '@/api/cart';
import { getProductImageUrl } from '@/lib/productUtils';
import { useUiStore } from '@/store/uiStore';

export interface CartItem {
  itemId?: string;
  productId: string;
  name: string;
  price: number;
  quantity: number;
  lineTotal?: number;
  imageUrl: string;
}

interface CartState {
  items: CartItem[];
  totalItems: number;
  cartId: string | null;
  initialized: boolean;
  loading: boolean;
  refreshCart: () => Promise<void>;
  /** Adds the quantity in one request; rejects with the API error so callers can show it. */
  addItem: (item: Omit<CartItem, 'quantity' | 'itemId' | 'lineTotal'>, quantity?: number) => Promise<void>;
  removeItem: (productId: string) => Promise<void>;
  updateQuantity: (productId: string, quantity: number) => Promise<void>;
  clearCart: () => Promise<void>;
}

const CART_KEY = 'dsports_cart';

const loadLocalCart = (): CartItem[] => {
  try {
    const raw = localStorage.getItem(CART_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
};

const saveLocalCart = (items: CartItem[]) => {
  localStorage.setItem(CART_KEY, JSON.stringify(items));
};

const toCartItem = (item: CartItemResponse): CartItem => ({
  itemId: item.id,
  productId: item.productId,
  name: item.productName,
  price: item.unitPrice,
  quantity: item.quantity,
  lineTotal: item.lineTotal,
  imageUrl: getProductImageUrl(item.productName),
});

const fromCartResponse = (cart: CartResponse): { items: CartItem[]; totalItems: number } => ({
  items: cart.items.map(toCartItem),
  totalItems: cart.totalItems,
});

export const useCartStore = create<CartState>((set, get) => ({
  items: [],
  totalItems: 0,
  cartId: null,
  initialized: false,
  loading: false,

  refreshCart: async () => {
    const { isAuthenticated } = useAuthStore.getState();
    if (!isAuthenticated) {
      const local = loadLocalCart();
      set({
        items: local,
        totalItems: local.reduce((s, i) => s + i.quantity, 0),
        initialized: true,
        loading: false,
      });
      return;
    }
    try {
      set({ loading: true });
      const cart = await cartApi.getCart();
      set({
        ...fromCartResponse(cart),
        cartId: cart.id,
        initialized: true,
        loading: false,
      });
    } catch {
      set({ items: [], totalItems: 0, cartId: null, initialized: true, loading: false });
    }
  },

  addItem: async (item, quantity = 1) => {
    const { isAuthenticated } = useAuthStore.getState();
    if (!isAuthenticated) {
      set((state) => {
        const existing = state.items.find((i) => i.productId === item.productId);
        const updated = existing
          ? state.items.map((i) =>
              i.productId === item.productId ? { ...i, quantity: i.quantity + quantity } : i,
            )
          : [...state.items, { ...item, quantity }];
        saveLocalCart(updated);
        return { items: updated, totalItems: updated.reduce((s, i) => s + i.quantity, 0) };
      });
      useUiStore.getState().noteAdded({ productId: item.productId, name: item.name });
      return;
    }
    try {
      const cart = await cartApi.addItem(item.productId, quantity);
      set({ ...fromCartResponse(cart), cartId: cart.id });
      useUiStore.getState().noteAdded({ productId: item.productId, name: item.name });   // drives the "Added to cart" confirmation
    } catch (err) {
      await get().refreshCart();
      throw err;
    }
  },

  removeItem: async (productId) => {
    const { isAuthenticated } = useAuthStore.getState();
    if (!isAuthenticated) {
      set((state) => {
        const updated = state.items.filter((i) => i.productId !== productId);
        saveLocalCart(updated);
        return { items: updated, totalItems: updated.reduce((s, i) => s + i.quantity, 0) };
      });
      return;
    }
    const item = get().items.find((i) => i.productId === productId);
    if (!item?.itemId) {
      await get().refreshCart();
      return;
    }
    try {
      const cart = await cartApi.removeItem(item.itemId);
      set({ ...fromCartResponse(cart), cartId: cart.id });
    } catch {
      await get().refreshCart();
    }
  },

  updateQuantity: async (productId, quantity) => {
    const { isAuthenticated } = useAuthStore.getState();
    if (!isAuthenticated) {
      set((state) => {
        const updated =
          quantity <= 0
            ? state.items.filter((i) => i.productId !== productId)
            : state.items.map((i) => (i.productId === productId ? { ...i, quantity } : i));
        saveLocalCart(updated);
        return { items: updated, totalItems: updated.reduce((s, i) => s + i.quantity, 0) };
      });
      return;
    }
    const item = get().items.find((i) => i.productId === productId);
    if (!item?.itemId) {
      await get().refreshCart();
      return;
    }
    if (quantity <= 0) {
      try {
        const cart = await cartApi.removeItem(item.itemId);
        set({ ...fromCartResponse(cart), cartId: cart.id });
      } catch {
        await get().refreshCart();
      }
      return;
    }
    try {
      const cart = await cartApi.updateQuantity(item.itemId, quantity);
      set({ ...fromCartResponse(cart), cartId: cart.id });
    } catch {
      await get().refreshCart();
    }
  },

  clearCart: async () => {
    const { isAuthenticated } = useAuthStore.getState();
    if (!isAuthenticated) {
      localStorage.removeItem(CART_KEY);
      set({ items: [], totalItems: 0 });
      return;
    }
    try {
      const cart = await cartApi.clearCart();
      set({ ...fromCartResponse(cart), cartId: cart.id });
    } catch {
      await get().refreshCart();
    }
  },
}));
