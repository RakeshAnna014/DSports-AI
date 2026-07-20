package com.dsports.inventory.domain.model;

import com.dsports.inventory.domain.event.*;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class InventoryItem {

    private final InventoryId id;
    private final ProductId productId;
    private final WarehouseId warehouseId;
    private Quantity availableQuantity;
    private ReservedQuantity reservedQuantity;
    private ReorderLevel reorderLevel;
    private InventoryStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private InventoryItem(InventoryId id, ProductId productId, WarehouseId warehouseId,
                          Quantity availableQuantity, ReservedQuantity reservedQuantity,
                          ReorderLevel reorderLevel, InventoryStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId must not be null");
        this.availableQuantity = Objects.requireNonNull(availableQuantity, "availableQuantity must not be null");
        this.reservedQuantity = Objects.requireNonNull(reservedQuantity, "reservedQuantity must not be null");
        this.reorderLevel = Objects.requireNonNull(reorderLevel, "reorderLevel must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.version = 0;
    }

    public static InventoryItem create(ProductId productId, WarehouseId warehouseId,
                                        Quantity availableQuantity, ReorderLevel reorderLevel) {
        if (availableQuantity.value() < 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Initial quantity must not be negative");
        }
        var status = InventoryStatus.fromAvailability(availableQuantity.value(), reorderLevel.value());
        var item = new InventoryItem(InventoryId.generate(), productId, warehouseId,
                availableQuantity, ReservedQuantity.zero(), reorderLevel, status);
        item.recordEvent(new InventoryCreatedEvent(item.id));
        return item;
    }

    public static InventoryItem reconstitute(InventoryId id, ProductId productId, WarehouseId warehouseId,
                                              Quantity availableQuantity, ReservedQuantity reservedQuantity,
                                              ReorderLevel reorderLevel, InventoryStatus status,
                                              Instant createdAt, Instant updatedAt, int version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(warehouseId, "warehouseId must not be null");
        var item = new InventoryItem(id, productId, warehouseId, availableQuantity, reservedQuantity, reorderLevel, status);
        item.createdAt = createdAt;
        item.updatedAt = updatedAt;
        item.version = version;
        return item;
    }

    public void stockIn(Quantity quantity) {
        if (quantity.value() <= 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Stock-in quantity must be positive");
        }
        this.availableQuantity = this.availableQuantity.add(quantity);
        this.updatedAt = Instant.now();
        updateStatus();
        recordEvent(new StockAddedEvent(this.id, quantity, this.status));
        if (this.status == InventoryStatus.LOW_STOCK) {
            recordEvent(new LowStockEvent(this.id, this.availableQuantity.value(), this.reorderLevel.value()));
        }
    }

    public void stockOut(Quantity quantity) {
        if (quantity.value() <= 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Stock-out quantity must be positive");
        }
        var availableAfter = this.availableQuantity.value() - quantity.value();
        if (availableAfter < 0) {
            throw new InventoryDomainException(InventoryErrorCode.STOCK_OUT_EXCEEDS_AVAILABLE,
                    "Cannot stock out " + quantity.value() + " units; only " + this.availableQuantity.value() + " available");
        }
        var reserved = this.reservedQuantity.value();
        var availableAfterReserved = availableAfter - reserved;
        if (availableAfterReserved < 0) {
            throw new InventoryDomainException(InventoryErrorCode.CANNOT_STOCK_OUT_RESERVED_QUANTITY,
                    "Cannot stock out " + quantity.value() + " units; " + reserved + " units are reserved");
        }
        this.availableQuantity = this.availableQuantity.subtract(quantity);
        this.updatedAt = Instant.now();
        updateStatus();
        recordEvent(new StockRemovedEvent(this.id, quantity, this.status));
        if (this.status == InventoryStatus.OUT_OF_STOCK) {
            recordEvent(new OutOfStockEvent(this.id));
        } else if (this.status == InventoryStatus.LOW_STOCK) {
            recordEvent(new LowStockEvent(this.id, this.availableQuantity.value(), this.reorderLevel.value()));
        }
    }

    public void reserve(Quantity quantity) {
        if (quantity.value() <= 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Reserve quantity must be positive");
        }
        var availableAfterReservation = this.availableQuantity.value() - quantity.value();
        if (availableAfterReservation < 0) {
            throw new InventoryDomainException(InventoryErrorCode.RESERVATION_EXCEEDS_AVAILABLE,
                    "Cannot reserve " + quantity.value() + " units; only " + this.availableQuantity.value() + " available");
        }
        this.reservedQuantity = this.reservedQuantity.add(quantity);
        this.updatedAt = Instant.now();
        updateStatus();
        recordEvent(new StockReservedEvent(this.id, quantity, this.status));
        if (this.status == InventoryStatus.LOW_STOCK) {
            recordEvent(new LowStockEvent(this.id, this.availableQuantity.value(), this.reorderLevel.value()));
        }
    }

    public void releaseReservation(Quantity quantity) {
        if (quantity.value() <= 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Release quantity must be positive");
        }
        if (quantity.value() > this.reservedQuantity.value()) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_RESERVED_QUANTITY,
                    "Cannot release " + quantity.value() + " reserved units; only " + this.reservedQuantity.value() + " reserved");
        }
        this.reservedQuantity = this.reservedQuantity.subtract(quantity);
        this.updatedAt = Instant.now();
        updateStatus();
        recordEvent(new ReservationReleasedEvent(this.id, quantity, this.status));
    }

    public void adjust(Quantity newAvailableQuantity) {
        if (newAvailableQuantity.value() < 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Adjusted quantity must not be negative");
        }
        var previousAvailable = this.availableQuantity;
        this.availableQuantity = newAvailableQuantity;
        if (this.reservedQuantity.value() > this.availableQuantity.value()) {
            this.reservedQuantity = ReservedQuantity.from(this.availableQuantity.value());
        }
        this.updatedAt = Instant.now();
        updateStatus();
        recordEvent(new InventoryAdjustedEvent(this.id, previousAvailable, this.availableQuantity, this.status));
    }

    public void changeReorderLevel(ReorderLevel newReorderLevel) {
        this.reorderLevel = newReorderLevel;
        this.updatedAt = Instant.now();
        updateStatus();
    }

    private void updateStatus() {
        var newStatus = InventoryStatus.fromAvailability(
                this.availableQuantity.value(), this.reorderLevel.value());
        if (newStatus != this.status) {
            this.status = newStatus;
        }
    }

    private void recordEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    public InventoryId getId() { return id; }
    public ProductId getProductId() { return productId; }
    public WarehouseId getWarehouseId() { return warehouseId; }
    public Quantity getAvailableQuantity() { return availableQuantity; }
    public ReservedQuantity getReservedQuantity() { return reservedQuantity; }
    public ReorderLevel getReorderLevel() { return reorderLevel; }
    public InventoryStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }

    public List<DomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryItem that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
