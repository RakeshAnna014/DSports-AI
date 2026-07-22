import { create } from 'zustand';

interface CartItem {
  productId: string;
  name: string;
  price: number;
  quantity: number;
  imageUrl: string;
}

interface CartState {
  items: CartItem[];
  totalItems: number;
  addItem: (item: Omit<CartItem, 'quantity'>) => void;
  removeItem: (productId: string) => void;
  updateQuantity: (productId: string, quantity: number) => void;
  clearCart: () => void;
}

const CART_KEY = 'dsports_cart';

const loadCart = (): CartItem[] => {
  try {
    const raw = localStorage.getItem(CART_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
};

const saveCart = (items: CartItem[]) => {
  localStorage.setItem(CART_KEY, JSON.stringify(items));
};

export const useCartStore = create<CartState>((set) => ({
  items: loadCart(),
  totalItems: loadCart().reduce((sum, item) => sum + item.quantity, 0),

  addItem: (item) =>
    set((state) => {
      const existing = state.items.find((i) => i.productId === item.productId);
      const updated = existing
        ? state.items.map((i) =>
            i.productId === item.productId ? { ...i, quantity: i.quantity + 1 } : i,
          )
        : [...state.items, { ...item, quantity: 1 }];
      saveCart(updated);
      return { items: updated, totalItems: updated.reduce((sum, i) => sum + i.quantity, 0) };
    }),

  removeItem: (productId) =>
    set((state) => {
      const updated = state.items.filter((i) => i.productId !== productId);
      saveCart(updated);
      return { items: updated, totalItems: updated.reduce((sum, i) => sum + i.quantity, 0) };
    }),

  updateQuantity: (productId, quantity) =>
    set((state) => {
      const updated =
        quantity <= 0
          ? state.items.filter((i) => i.productId !== productId)
          : state.items.map((i) => (i.productId === productId ? { ...i, quantity } : i));
      saveCart(updated);
      return { items: updated, totalItems: updated.reduce((sum, i) => sum + i.quantity, 0) };
    }),

  clearCart: () => {
    localStorage.removeItem(CART_KEY);
    return { items: [], totalItems: 0 };
  },
}));
