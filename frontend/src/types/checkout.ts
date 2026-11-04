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
  promotionCode: string | null;
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

/** A promotion as it applies to one checkout. discountAmount is calculated by the backend for this cart; demo marks synthetic offers. */
export interface PromotionOffer {
  code: string;
  title: string;
  description: string;
  discountType: 'PERCENT' | 'FIXED';
  discountValue: number;
  maxDiscountAmount: number | null;
  minOrderAmount: number;
  scope: 'ALL' | 'CATEGORY' | 'SPORT';
  scopeNames: string[];
  validUntil: string | null;
  eligible: boolean;
  discountAmount: number | string;
  eligibleSubtotal: number | string | null;
  ineligibleReason: string | null;
  applied: boolean;
  demo: boolean;
}

/** The AI service's explained ordering of the backend's offers. easons are only evidence that is true of this customer. */
export interface RecommendedOffer extends Omit<PromotionOffer, 'discountType' | 'discountValue' | 'maxDiscountAmount' | 'minOrderAmount'> {
  reasons: string[];
  personal: boolean;
  recommended?: boolean;
}

export interface OfferRecommendations {
  checkoutId: string;
  personalized: boolean;
  personalizationOff: boolean;
  basedOn: string[];
  message: string | null;
  offers: RecommendedOffer[];
  disclaimer: string;
}
