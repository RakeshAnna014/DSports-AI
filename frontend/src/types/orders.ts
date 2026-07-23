export interface AddressSnapshot {
  line1: string;
  line2: string | null;
  city: string;
  state: string;
  country: string;
  postalCode: string;
  fullName: string;
  phone: string;
}

export interface OrderItemResponse {
  id: string;
  productId: string;
  productName: string;
  sku: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  productImage: string | null;
}

export interface OrderResponse {
  id: string;
  orderNumber: string;
  userId: string;
  checkoutId: string;
  status: string;
  shippingAddress: AddressSnapshot;
  billingAddress: AddressSnapshot;
  items: OrderItemResponse[];
  subtotal: number;
  shippingCharge: number;
  taxAmount: number;
  discountAmount: number;
  grandTotal: number;
  currency: string;
  placedAt: string;
  cancelledAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface OrderSummaryResponse {
  id: string;
  orderNumber: string;
  status: string;
  totalItems: number;
  grandTotal: number;
  currency: string;
  placedAt: string;
}

export interface PlaceOrderPayload {
  checkoutId: string;
}

export interface PlaceOrderResponse {
  id: string;
  orderNumber: string;
  status: string;
  grandTotal: number;
  currency: string;
  placedAt: string;
}

export interface UpdateOrderStatusPayload {
  status: string;
}

export type OrderStatus =
  | 'CREATED'
  | 'PENDING_PAYMENT'
  | 'CONFIRMED'
  | 'PROCESSING'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'REFUNDED';
