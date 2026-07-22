import apiClient from './client';

export interface CartItemResponse {
  id: string;
  productId: string;
  productName: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
  createdAt: string;
  updatedAt: string;
}

export interface CartResponse {
  id: string;
  userId: string;
  status: string;
  totalItems: number;
  totalAmount: number;
  version: number;
  items: CartItemResponse[];
  createdAt: string;
  updatedAt: string;
}

export interface AddItemPayload {
  productId: string;
  quantity: number;
}

export interface UpdateQuantityPayload {
  quantity: number;
}

export const cartApi = {
  getCart: () =>
    apiClient.get<CartResponse>('/v1/cart').then((r) => r.data),

  addItem: (productId: string, quantity: number) =>
    apiClient.post<CartResponse>('/v1/cart/items', { productId, quantity } as AddItemPayload).then((r) => r.data),

  updateQuantity: (itemId: string, quantity: number) =>
    apiClient.put<CartResponse>(`/v1/cart/items/${itemId}`, { quantity } as UpdateQuantityPayload).then((r) => r.data),

  removeItem: (itemId: string) =>
    apiClient.delete<CartResponse>(`/v1/cart/items/${itemId}`).then((r) => r.data),

  clearCart: () =>
    apiClient.delete<CartResponse>('/v1/cart').then((r) => r.data),
};
