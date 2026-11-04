package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.application.result.ShipmentTrackingResult;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentTracking;

public final class ShipmentResultMapper {
    private ShipmentResultMapper() {}

    public static ShipmentResult toResult(Shipment shipment) {
        return new ShipmentResult(
            shipment.getId().value(),
            shipment.getShipmentNumber().value(),
            shipment.getTrackingNumber() != null ? shipment.getTrackingNumber().value() : null,
            shipment.getOrderId(),
            shipment.getUserId(),
            shipment.getCourierProvider() != null ? shipment.getCourierProvider().name() : null,
            shipment.getStatus().name(),
            shipment.getShippingAddress().street(),
            shipment.getShippingAddress().city(),
            shipment.getShippingAddress().state(),
            shipment.getShippingAddress().zipCode(),
            shipment.getShippingAddress().country(),
            shipment.getEstimatedDeliveryDate(),
            shipment.getActualDeliveryDate(),
            shipment.getShippedAt(),
            shipment.getDeliveredAt(),
            shipment.getCreatedAt(),
            shipment.getUpdatedAt(),
            shipment.getVersion()
        );
    }

    public static ShipmentTrackingResult toTrackingResult(ShipmentTracking tracking) {
        return new ShipmentTrackingResult(
            tracking.getTrackingId(),
            tracking.getShipmentId().value(),
            tracking.getStatus().name(),
            tracking.getLocation(),
            tracking.getDescription(),
            tracking.getEventTime()
        );
    }
}
