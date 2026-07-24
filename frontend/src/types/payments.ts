export interface PaymentResponse {
  id: string;
  paymentReference: string;
  orderId: string;
  amount: number;
  currency: string;
  paymentMethod: string | null;
  paymentProvider: string | null;
  transactionId: string | null;
  gatewayReference: string | null;
  status: string;
  failureReason: string | null;
  paidAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentSummaryResponse {
  id: string;
  paymentReference: string;
  orderId: string;
  amount: number;
  currency: string;
  paymentMethod: string | null;
  status: string;
  paidAt: string | null;
  createdAt: string;
}

export interface CreatePaymentPayload {
  orderId: string;
  amount: number;
  currency: string;
  paymentMethod: string;
  paymentProvider: string;
}

export type PaymentStatus =
  | 'CREATED'
  | 'PENDING'
  | 'AUTHORIZED'
  | 'SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'REFUNDED';

export type PaymentMethod = 'CARD' | 'UPI' | 'NET_BANKING' | 'WALLET' | 'COD';
export type PaymentProvider = 'MOCK' | 'STRIPE' | 'RAZORPAY' | 'PAYPAL';
