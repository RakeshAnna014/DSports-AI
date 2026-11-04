import apiClient from './client';

/** One order with its payment and shipment state; `nextShipmentStatuses` are the transitions the backend state machine allows right now. */
export interface FulfilmentRow {
  orderId: string;
  orderNumber: string;
  orderStatus: string;
  grandTotal: number;
  currency: string;
  placedAt: string | null;
  items: number;
  customer: string | null;
  paymentStatus: string | null;
  paid: boolean;
  shipmentId: string | null;
  shipmentNumber: string | null;
  trackingNumber: string | null;
  shipmentStatus: string | null;
  courierProvider: string | null;
  canCreateShipment: boolean;
  nextShipmentStatuses: string[];
}

export const fulfilmentApi = {
  board: (statuses: string[]) =>
    apiClient.get<FulfilmentRow[]>('/v1/admin/fulfilment/orders', { params: { status: statuses.join(',') } }).then((r) => r.data),

  /** Normally done automatically after payment capture; idempotent (returns the existing shipment). */
  createShipment: (orderId: string) => apiClient.post('/v1/shipments', { orderId }).then((r) => r.data),

  /** The order status follows the shipment on the backend (picked up -> shipped, delivered -> delivered). */
  updateShipment: (shipmentId: string, newStatus: string, reason?: string) =>
    apiClient.put(`/v1/shipments/admin/${shipmentId}/status`, { newStatus, reason }).then((r) => r.data),
};
