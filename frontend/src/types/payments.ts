export interface PaymentResponse {
  id: string;
  paymentReference: string;
  orderId: string;
  amount: number;
  currency: string;
  paymentMethod: string | null;
  paymentProvider: string | null;
  transactionId: string | null;
  status: string;
  failureReason: string | null;
  paidAt: string | null;
  refundedAmount: number;
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
  | 'PROCESSING'
  | 'AUTHORIZED'
  | 'SUCCESS'
  | 'PARTIALLY_REFUNDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'REFUNDED';

export type PaymentMethod = 'CARD' | 'UPI' | 'NET_BANKING' | 'WALLET' | 'COD';
export type PaymentProvider = 'MOCK' | 'STRIPE' | 'RAZORPAY' | 'PAYPAL';

/** SUCCESS means the payment has been captured. */
export const CAPTURED_STATUSES: readonly string[] = ['SUCCESS', 'PARTIALLY_REFUNDED', 'REFUNDED'];

export type RefundStatus = 'REQUESTED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'REJECTED';

export interface RefundResponse {
  id: string;
  paymentId: string;
  orderId: string;
  amount: number;
  currency: string;
  reason: string | null;
  status: RefundStatus;
  failureReason: string | null;
  attempts: number;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface RefundRequestPayload {
  amount?: number;
  reason?: string;
}
