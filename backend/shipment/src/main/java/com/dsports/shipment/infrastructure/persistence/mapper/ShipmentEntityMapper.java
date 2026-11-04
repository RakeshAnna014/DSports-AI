package com.dsports.shipment.infrastructure.persistence.mapper;

import com.dsports.shipment.domain.model.CourierProvider;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentNumber;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.domain.model.ShipmentTracking;
import com.dsports.shipment.domain.model.ShippingAddress;
import com.dsports.shipment.domain.model.TrackingNumber;
import com.dsports.shipment.infrastructure.persistence.entity.ShipmentEntity;
import com.dsports.shipment.infrastructure.persistence.entity.ShipmentTrackingEntity;

import java.util.UUID;

public final class ShipmentEntityMapper {
    private ShipmentEntityMapper() {}

    public static ShipmentEntity toEntity(Shipment shipment) {
        var entity = new ShipmentEntity();
        entity.setId(shipment.getId().value());
        entity.setShipmentNumber(shipment.getShipmentNumber().value());
        entity.setTrackingNumber(shipment.getTrackingNumber() != null ? shipment.getTrackingNumber().value() : null);
        entity.setOrderId(shipment.getOrderId());
        entity.setUserId(shipment.getUserId());
        entity.setCourierProvider(shipment.getCourierProvider() != null ? shipment.getCourierProvider().name() : null);
        entity.setStatus(shipment.getStatus().name());
        entity.setStreet(shipment.getShippingAddress().street());
        entity.setCity(shipment.getShippingAddress().city());
        entity.setState(shipment.getShippingAddress().state());
        entity.setZipCode(shipment.getShippingAddress().zipCode());
        entity.setCountry(shipment.getShippingAddress().country());
        entity.setEstimatedDeliveryDate(shipment.getEstimatedDeliveryDate());
        entity.setActualDeliveryDate(shipment.getActualDeliveryDate());
        entity.setShippedAt(shipment.getShippedAt());
        entity.setDeliveredAt(shipment.getDeliveredAt());
        entity.setCreatedAt(shipment.getCreatedAt());
        entity.setUpdatedAt(shipment.getUpdatedAt());
        entity.setVersion(shipment.getVersion());
        return entity;
    }

    public static Shipment toDomain(ShipmentEntity entity) {
        return Shipment.reconstitute(
                ShipmentId.fromUUID(entity.getId()),
                new ShipmentNumber(entity.getShipmentNumber()),
                entity.getTrackingNumber() != null ? new TrackingNumber(entity.getTrackingNumber()) : null,
                entity.getOrderId(),
                entity.getUserId(),
                entity.getCourierProvider() != null ? CourierProvider.valueOf(entity.getCourierProvider()) : null,
                ShipmentStatus.valueOf(entity.getStatus()),
                new ShippingAddress(entity.getStreet(), entity.getCity(), entity.getState(), entity.getZipCode(), entity.getCountry()),
                entity.getEstimatedDeliveryDate(),
                entity.getActualDeliveryDate(),
                entity.getShippedAt(),
                entity.getDeliveredAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }

    public static ShipmentTrackingEntity toTrackingEntity(ShipmentTracking tracking, UUID shipmentId) {
        var entity = new ShipmentTrackingEntity();
        entity.setId(tracking.getTrackingId());
        entity.setShipmentId(shipmentId);
        entity.setStatus(tracking.getStatus().name());
        entity.setLocation(tracking.getLocation());
        entity.setDescription(tracking.getDescription());
        entity.setEventTime(tracking.getEventTime());
        entity.setNew(true);
        return entity;
    }

    public static ShipmentTracking toTrackingDomain(ShipmentTrackingEntity entity) {
        return new ShipmentTracking(
                entity.getId(),
                ShipmentId.fromUUID(entity.getShipmentId()),
                ShipmentStatus.valueOf(entity.getStatus()),
                entity.getLocation(),
                entity.getDescription(),
                entity.getEventTime()
        );
    }
}
