export interface CheckoutItemResponse {
  id: string;
  productId: string;
  productName: string;
  sku: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  imageUrl: string | null;
}

export interface CreateCheckoutResponse {
  id: string;
  customerId: string;
  cartId: string;
  status: string;
  subtotal: number;
  taxAmount: number;
  deliveryCharge: number;
  discountAmount: number;
  totalAmount: number;
  currency: string;
  expiresAt: string;
  items: CheckoutItemResponse[];
}

export interface CheckoutResponse {
  id: string;
  customerId: string;
  cartId: string;
  status: string;
  shippingAddressId: string | null;
  deliveryMethodCode: string | null;
  deliveryMethodName: string | null;
  deliveryCharge: number;
  discountAmount: number;
  subtotal: number;
  taxAmount: number;
  totalAmount: number;
  currency: string;
  notes: string | null;
  expiresAt: string;
  validatedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  items: CheckoutItemResponse[];
}

export interface SelectAddressPayload {
  addressId: string;
}

export interface SelectDeliveryMethodPayload {
  deliveryMethodCode: 'STANDARD' | 'EXPRESS' | 'NEXT_DAY';
}
