package com.dsports.shipment.domain.model;

public enum ShipmentStatus {
    CREATED,
    READY_FOR_PICKUP,
    PICKED_UP,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    FAILED,
    RETURNED;

    public boolean canTransitionTo(ShipmentStatus target) {
        return switch (this) {
            case CREATED -> target == READY_FOR_PICKUP || target == FAILED;
            case READY_FOR_PICKUP -> target == PICKED_UP || target == FAILED;
            case PICKED_UP -> target == IN_TRANSIT || target == FAILED || target == RETURNED;
            case IN_TRANSIT -> target == OUT_FOR_DELIVERY || target == FAILED || target == RETURNED;
            case OUT_FOR_DELIVERY -> target == DELIVERED || target == FAILED;
            case DELIVERED -> false;
            case FAILED -> target == READY_FOR_PICKUP;
            case RETURNED -> false;
        };
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == RETURNED;
    }
}
