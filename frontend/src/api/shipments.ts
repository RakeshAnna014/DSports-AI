import apiClient from './client';

export interface ShipmentResult {
  id: string;
  shipmentNumber: string;
  trackingNumber: string | null;
  orderId: string;
  userId: string;
  courierProvider: string | null;
  status: string;
  street: string;
  city: string;
  state: string;
  zipCode: string;
  country: string;
  estimatedDeliveryDate: string | null;
  actualDeliveryDate: string | null;
  shippedAt: string | null;
  deliveredAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** Admin only. The delivery address is always taken from the order's snapshot, never from the caller. */
export interface CreateShipmentRequest {
  orderId: string;
}

export interface AssignCourierRequest {
  courierProvider: string;
}

export interface UpdateStatusRequest {
  newStatus: string;
  reason?: string;
}

export const shipmentsApi = {
  createShipment: (data: CreateShipmentRequest) =>
    apiClient.post<ShipmentResult>('/v1/shipments', data).then((r) => r.data),

  getById: (id: string) =>
    apiClient.get<ShipmentResult>(`/v1/shipments/${id}`).then((r) => r.data),

  trackByNumber: (trackingNumber: string) =>
    apiClient.get<ShipmentResult>(`/v1/shipments/track/${trackingNumber}`).then((r) => r.data),

  assignCourier: (id: string, data: AssignCourierRequest) =>
    apiClient.post<ShipmentResult>(`/v1/shipments/${id}/courier`, data).then((r) => r.data),

  updateStatus: (id: string, data: UpdateStatusRequest) =>
    apiClient.put<ShipmentResult>(`/v1/shipments/admin/${id}/status`, data).then((r) => r.data),
};
