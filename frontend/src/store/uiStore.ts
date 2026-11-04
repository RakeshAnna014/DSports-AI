import { create } from 'zustand';

interface UiState {
  cartOpen: boolean;
  setCartOpen: (open: boolean) => void;
  /** The product just added to the cart (drives the "Added to cart" confirmation and "Complete your setup"). */
  lastAdded: { productId: string; name: string } | null;
  noteAdded: (item: { productId: string; name: string } | null) => void;
}

export const useUiStore = create<UiState>((set) => ({
  cartOpen: false,
  setCartOpen: (cartOpen) => set({ cartOpen }),
  lastAdded: null,
  noteAdded: (lastAdded) => set({ lastAdded }),
}));
