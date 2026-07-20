package com.dsports.inventory.domain.model;

import com.dsports.inventory.domain.event.*;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InventoryItemTest {

    private static final ProductId PRODUCT_ID = ProductId.generate();
    private static final WarehouseId WAREHOUSE_ID = WarehouseId.generate();
    private static final ReorderLevel REORDER_LEVEL = ReorderLevel.from(10);

    @Test
    void shouldCreateInventoryItem() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(100), REORDER_LEVEL);

        assertThat(item.getId()).isNotNull();
        assertThat(item.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(item.getWarehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(100));
        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.zero());
        assertThat(item.getReorderLevel()).isEqualTo(REORDER_LEVEL);
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.IN_STOCK);
        assertThat(item.getVersion()).isZero();
        assertThat(item.getCreatedAt()).isNotNull();
        assertThat(item.getUpdatedAt()).isNotNull();
        assertThat(item.getDomainEvents()).hasSize(1);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(InventoryCreatedEvent.class);
    }

    @Test
    void shouldStockIn() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(5), REORDER_LEVEL);
        item.clearDomainEvents();

        item.stockIn(Quantity.from(10));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(15));
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.IN_STOCK);
        assertThat(item.getDomainEvents()).hasSize(1);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(StockAddedEvent.class);
    }

    @Test
    void shouldStockInAndFireLowStockEventWhenRemainingLow() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(2), REORDER_LEVEL);
        item.clearDomainEvents();

        item.stockIn(Quantity.from(3));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(5));
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.LOW_STOCK);
        assertThat(item.getDomainEvents()).hasSize(2);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(StockAddedEvent.class);
        assertThat(item.getDomainEvents().get(1)).isInstanceOf(LowStockEvent.class);
    }

    @Test
    void shouldStockOut() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(20), REORDER_LEVEL);
        item.clearDomainEvents();

        item.stockOut(Quantity.from(5));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(15));
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.IN_STOCK);
        assertThat(item.getDomainEvents()).hasSize(1);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(StockRemovedEvent.class);
    }

    @Test
    void shouldStockOutAndFireLowStockEventWhenStockBecomesLow() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(12), REORDER_LEVEL);
        item.clearDomainEvents();

        item.stockOut(Quantity.from(7));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(5));
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.LOW_STOCK);
        assertThat(item.getDomainEvents()).hasSize(2);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(StockRemovedEvent.class);
        assertThat(item.getDomainEvents().get(1)).isInstanceOf(LowStockEvent.class);
    }

    @Test
    void shouldStockOutAndFireOutOfStockEventWhenStockDepleted() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(5), REORDER_LEVEL);
        item.clearDomainEvents();

        item.stockOut(Quantity.from(5));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(0));
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.OUT_OF_STOCK);
        assertThat(item.getDomainEvents()).hasSize(2);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(StockRemovedEvent.class);
        assertThat(item.getDomainEvents().get(1)).isInstanceOf(OutOfStockEvent.class);
    }

    @Test
    void shouldThrowWhenStockOutExceedsAvailable() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(5), REORDER_LEVEL);

        assertThatThrownBy(() -> item.stockOut(Quantity.from(10)))
                .isInstanceOf(InventoryDomainException.class)
                .hasMessageContaining("Cannot stock out")
                .satisfies(e -> assertThat(((InventoryDomainException) e).getErrorCode())
                        .isEqualTo(InventoryErrorCode.STOCK_OUT_EXCEEDS_AVAILABLE));
    }

    @Test
    void shouldThrowWhenStockOutExceedsAvailableMinusReserved() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(10), REORDER_LEVEL);
        item.reserve(Quantity.from(8));
        item.clearDomainEvents();

        assertThatThrownBy(() -> item.stockOut(Quantity.from(5)))
                .isInstanceOf(InventoryDomainException.class)
                .hasMessageContaining("reserved")
                .satisfies(e -> assertThat(((InventoryDomainException) e).getErrorCode())
                        .isEqualTo(InventoryErrorCode.CANNOT_STOCK_OUT_RESERVED_QUANTITY));
    }

    @Test
    void shouldReserveStock() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(20), REORDER_LEVEL);
        item.clearDomainEvents();

        item.reserve(Quantity.from(5));

        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(5));
        assertThat(item.getDomainEvents()).hasSize(1);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(StockReservedEvent.class);
    }

    @Test
    void shouldThrowWhenReserveExceedsAvailable() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(5), REORDER_LEVEL);

        assertThatThrownBy(() -> item.reserve(Quantity.from(10)))
                .isInstanceOf(InventoryDomainException.class)
                .hasMessageContaining("Cannot reserve")
                .satisfies(e -> assertThat(((InventoryDomainException) e).getErrorCode())
                        .isEqualTo(InventoryErrorCode.RESERVATION_EXCEEDS_AVAILABLE));
    }

    @Test
    void shouldThrowWhenReserveOnOutOfStock() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(0), REORDER_LEVEL);

        assertThatThrownBy(() -> item.reserve(Quantity.from(1)))
                .isInstanceOf(InventoryDomainException.class)
                .hasMessageContaining("Cannot reserve")
                .satisfies(e -> assertThat(((InventoryDomainException) e).getErrorCode())
                        .isEqualTo(InventoryErrorCode.RESERVATION_EXCEEDS_AVAILABLE));
    }

    @Test
    void shouldReleaseReservation() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(20), REORDER_LEVEL);
        item.reserve(Quantity.from(5));
        item.clearDomainEvents();

        item.releaseReservation(Quantity.from(2));

        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(3));
        assertThat(item.getDomainEvents()).hasSize(1);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(ReservationReleasedEvent.class);
    }

    @Test
    void shouldAdjustQuantity() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(20), REORDER_LEVEL);
        item.clearDomainEvents();

        item.adjust(Quantity.from(15));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(15));
        assertThat(item.getDomainEvents()).hasSize(1);
        assertThat(item.getDomainEvents().get(0)).isInstanceOf(InventoryAdjustedEvent.class);
    }

    @Test
    void shouldClampReservedQuantityWhenAdjustedBelowReserved() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(20), REORDER_LEVEL);
        item.reserve(Quantity.from(15));
        item.clearDomainEvents();

        item.adjust(Quantity.from(10));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(10));
        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(10));
    }

    @Test
    void shouldChangeReorderLevel() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(20), REORDER_LEVEL);
        item.clearDomainEvents();

        item.changeReorderLevel(ReorderLevel.from(5));

        assertThat(item.getReorderLevel()).isEqualTo(ReorderLevel.from(5));
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.IN_STOCK);
    }

    @Test
    void shouldTransitionToLowStockWhenReorderLevelIncreased() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(3), ReorderLevel.from(1));
        assertThat(item.getStatus()).isEqualTo(InventoryStatus.IN_STOCK);

        item.changeReorderLevel(ReorderLevel.from(5));

        assertThat(item.getStatus()).isEqualTo(InventoryStatus.LOW_STOCK);
    }

    @Test
    void shouldBeInStockWhenQuantityExceedsReorderLevel() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(100), REORDER_LEVEL);

        assertThat(item.getStatus()).isEqualTo(InventoryStatus.IN_STOCK);
    }

    @Test
    void shouldBeLowStockWhenQuantityAtOrBelowReorderLevel() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(10), REORDER_LEVEL);

        assertThat(item.getStatus()).isEqualTo(InventoryStatus.LOW_STOCK);
    }

    @Test
    void shouldBeLowStockWhenQuantityBelowReorderLevel() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(5), REORDER_LEVEL);

        assertThat(item.getStatus()).isEqualTo(InventoryStatus.LOW_STOCK);
    }

    @Test
    void shouldBeOutOfStockWhenQuantityIsZero() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(0), REORDER_LEVEL);

        assertThat(item.getStatus()).isEqualTo(InventoryStatus.OUT_OF_STOCK);
    }

    @Test
    void shouldSucceedStockOutWithExactAvailableMinusReserved() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(10), REORDER_LEVEL);
        item.reserve(Quantity.from(4));
        item.clearDomainEvents();

        item.stockOut(Quantity.from(6));

        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(4));
        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(4));
    }

    @Test
    void shouldMaintainInvariantsAcrossMultipleOperations() {
        var item = InventoryItem.create(PRODUCT_ID, WAREHOUSE_ID, Quantity.from(50), REORDER_LEVEL);
        item.clearDomainEvents();

        item.stockIn(Quantity.from(10));
        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(60));

        item.stockOut(Quantity.from(5));
        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(55));

        item.reserve(Quantity.from(10));
        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(10));

        item.stockOut(Quantity.from(20));
        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(35));
        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(10));

        item.reserve(Quantity.from(5));
        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(15));

        item.releaseReservation(Quantity.from(5));
        assertThat(item.getReservedQuantity()).isEqualTo(ReservedQuantity.from(10));

        item.adjust(Quantity.from(30));
        assertThat(item.getAvailableQuantity()).isEqualTo(Quantity.from(30));

        assertThat(item.getStatus()).isEqualTo(InventoryStatus.IN_STOCK);
    }
}
