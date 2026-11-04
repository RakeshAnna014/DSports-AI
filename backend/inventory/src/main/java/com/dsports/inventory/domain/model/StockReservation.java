package com.dsports.inventory.domain.model;

import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Units of one inventory row held for one order line until the order is paid, cancelled or expires. */
public final class StockReservation {

    private final UUID id;
    private final UUID orderId;
    private final ProductId productId;
    private final InventoryId inventoryId;
    private final Quantity quantity;
    private ReservationStatus status;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private StockReservation(UUID id, UUID orderId, ProductId productId, InventoryId inventoryId,
                             Quantity quantity, ReservationStatus status, int version,
                             Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.orderId = Objects.requireNonNull(orderId);
        this.productId = Objects.requireNonNull(productId);
        this.inventoryId = Objects.requireNonNull(inventoryId);
        this.quantity = Objects.requireNonNull(quantity);
        this.status = Objects.requireNonNull(status);
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static StockReservation create(UUID orderId, ProductId productId, InventoryId inventoryId, Quantity quantity) {
        if (quantity.value() <= 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Reservation quantity must be positive");
        }
        var now = Instant.now();
        return new StockReservation(UUID.randomUUID(), orderId, productId, inventoryId, quantity,
                ReservationStatus.RESERVED, 0, now, now);
    }

    public static StockReservation reconstitute(UUID id, UUID orderId, ProductId productId, InventoryId inventoryId,
                                                Quantity quantity, ReservationStatus status, int version,
                                                Instant createdAt, Instant updatedAt) {
        return new StockReservation(id, orderId, productId, inventoryId, quantity, status, version, createdAt, updatedAt);
    }

    public void fulfill() {
        transitionTo(ReservationStatus.FULFILLED);
    }

    public void release() {
        transitionTo(ReservationStatus.RELEASED);
    }

    public boolean isActive() {
        return status == ReservationStatus.RESERVED;
    }

    private void transitionTo(ReservationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_RESERVATION_TRANSITION,
                    "Cannot move reservation " + id + " from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public ProductId getProductId() { return productId; }
    public InventoryId getInventoryId() { return inventoryId; }
    public Quantity getQuantity() { return quantity; }
    public ReservationStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
