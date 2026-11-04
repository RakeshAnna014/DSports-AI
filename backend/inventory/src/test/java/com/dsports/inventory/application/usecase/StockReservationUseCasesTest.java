package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.port.StockReservationRepository;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.inventory.domain.model.InventoryItem;
import com.dsports.inventory.domain.model.InventoryStatus;
import com.dsports.inventory.domain.model.ProductId;
import com.dsports.inventory.domain.model.Quantity;
import com.dsports.inventory.domain.model.ReorderLevel;
import com.dsports.inventory.domain.model.ReservationStatus;
import com.dsports.inventory.domain.model.ReservedQuantity;
import com.dsports.inventory.domain.model.StockReservation;
import com.dsports.inventory.domain.model.WarehouseId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reservation lifecycle against in-memory repositories that enforce the same optimistic version check as the
 * database, so lost races are reproduced deterministically.
 */
class StockReservationUseCasesTest {

    private InMemoryInventory inventory;
    private InMemoryReservations reservations;
    private ReserveStockForOrderUseCase reserve;
    private FulfillOrderReservationsUseCase fulfill;
    private ReleaseOrderReservationsUseCase release;

    private final UUID product = UUID.randomUUID();
    private final UUID otherProduct = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inventory = new InMemoryInventory();
        reservations = new InMemoryReservations();
        reserve = new ReserveStockForOrderUseCase(inventory, reservations);
        fulfill = new FulfillOrderReservationsUseCase(inventory, reservations);
        release = new ReleaseOrderReservationsUseCase(inventory, reservations);
    }

    @Test
    void reservesStockForOneCustomer() {
        var row = inventory.add(product, 5, 0);
        var order = UUID.randomUUID();

        StepVerifier.create(reserve.execute(order, List.of(new ReserveStockForOrderUseCase.Line(product, 2)))).verifyComplete();

        assertThat(inventory.get(row).getReservedQuantity().value()).isEqualTo(2);
        assertThat(inventory.get(row).getSellableQuantity()).isEqualTo(3);
        assertThat(reservations.forOrder(order)).singleElement()
            .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED));
    }

    @Test
    void lastUnitCanOnlyBeReservedOnce() {
        inventory.add(product, 1, 0);

        StepVerifier.create(reserve.execute(UUID.randomUUID(), List.of(new ReserveStockForOrderUseCase.Line(product, 1))))
            .verifyComplete();
        StepVerifier.create(reserve.execute(UUID.randomUUID(), List.of(new ReserveStockForOrderUseCase.Line(product, 1))))
            .expectErrorMatches(e -> isCode(e, InventoryErrorCode.INSUFFICIENT_STOCK))
            .verify();
    }

    @Test
    void existingReservationsReduceWhatCanBeSold() {
        inventory.add(product, 5, 4);

        StepVerifier.create(reserve.execute(UUID.randomUUID(), List.of(new ReserveStockForOrderUseCase.Line(product, 2))))
            .expectErrorMatches(e -> isCode(e, InventoryErrorCode.INSUFFICIENT_STOCK)
                && e.getMessage().contains("available: 1"))
            .verify();
    }

    @Test
    void losingAConcurrentUpdateRetriesAgainstFreshStock() {
        var row = inventory.add(product, 3, 0);
        inventory.simulateConcurrentWriteOnNextSave(row, 2);

        StepVerifier.create(reserve.execute(UUID.randomUUID(), List.of(new ReserveStockForOrderUseCase.Line(product, 1))))
            .verifyComplete();

        assertThat(inventory.get(row).getReservedQuantity().value()).isEqualTo(3);
        assertThat(inventory.conflicts.get()).isEqualTo(1);
    }

    @Test
    void losingTheRaceForTheLastUnitFailsWithInsufficientStock() {
        var row = inventory.add(product, 1, 0);
        inventory.simulateConcurrentWriteOnNextSave(row, 1);

        StepVerifier.create(reserve.execute(UUID.randomUUID(), List.of(new ReserveStockForOrderUseCase.Line(product, 1))))
            .expectErrorMatches(e -> isCode(e, InventoryErrorCode.INSUFFICIENT_STOCK))
            .verify();
        assertThat(inventory.get(row).getReservedQuantity().value()).isEqualTo(1);
    }

    @Test
    void reservesMultipleLinesAndQuantities() {
        var first = inventory.add(product, 10, 0);
        var second = inventory.add(otherProduct, 3, 0);
        var order = UUID.randomUUID();

        StepVerifier.create(reserve.execute(order, List.of(
                new ReserveStockForOrderUseCase.Line(product, 4),
                new ReserveStockForOrderUseCase.Line(otherProduct, 3))))
            .verifyComplete();

        assertThat(inventory.get(first).getReservedQuantity().value()).isEqualTo(4);
        assertThat(inventory.get(second).getReservedQuantity().value()).isEqualTo(3);
        assertThat(reservations.forOrder(order)).hasSize(2);
    }

    @Test
    void duplicateReservationRequestIsIdempotent() {
        var row = inventory.add(product, 5, 0);
        var order = UUID.randomUUID();
        var lines = List.of(new ReserveStockForOrderUseCase.Line(product, 2));

        reserve.execute(order, lines).block();
        StepVerifier.create(reserve.execute(order, lines)).verifyComplete();

        assertThat(inventory.get(row).getReservedQuantity().value()).isEqualTo(2);
        assertThat(reservations.forOrder(order)).hasSize(1);
    }

    @Test
    void sameOrderReservingADifferentQuantityIsAConflict() {
        inventory.add(product, 5, 0);
        var order = UUID.randomUUID();
        reserve.execute(order, List.of(new ReserveStockForOrderUseCase.Line(product, 2))).block();

        StepVerifier.create(reserve.execute(order, List.of(new ReserveStockForOrderUseCase.Line(product, 3))))
            .expectErrorMatches(e -> isCode(e, InventoryErrorCode.RESERVATION_CONFLICT))
            .verify();
    }

    @Test
    void fulfilmentRemovesUnitsFromOnHandAndReservedStock() {
        var row = inventory.add(product, 5, 0);
        var order = UUID.randomUUID();
        reserve.execute(order, List.of(new ReserveStockForOrderUseCase.Line(product, 2))).block();

        StepVerifier.create(fulfill.execute(order)).verifyComplete();

        assertThat(inventory.get(row).getAvailableQuantity().value()).isEqualTo(3);
        assertThat(inventory.get(row).getReservedQuantity().value()).isZero();
        assertThat(reservations.forOrder(order)).allMatch(r -> r.getStatus() == ReservationStatus.FULFILLED);

        StepVerifier.create(fulfill.execute(order)).verifyComplete();
        assertThat(inventory.get(row).getAvailableQuantity().value()).isEqualTo(3);
    }

    @Test
    void fulfillingAnOrderWithoutReservationsIsRejected() {
        StepVerifier.create(fulfill.execute(UUID.randomUUID()))
            .expectErrorMatches(e -> isCode(e, InventoryErrorCode.RESERVATION_NOT_FOUND))
            .verify();
    }

    @Test
    void releaseReturnsUnitsToSellableStockOnceOnly() {
        var row = inventory.add(product, 5, 0);
        var order = UUID.randomUUID();
        reserve.execute(order, List.of(new ReserveStockForOrderUseCase.Line(product, 2))).block();

        StepVerifier.create(release.execute(order)).verifyComplete();
        StepVerifier.create(release.execute(order)).verifyComplete();

        assertThat(inventory.get(row).getReservedQuantity().value()).isZero();
        assertThat(inventory.get(row).getAvailableQuantity().value()).isEqualTo(5);
        assertThat(reservations.forOrder(order)).allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
    }

    @Test
    void releasedStockCannotBeFulfilledLater() {
        inventory.add(product, 5, 0);
        var order = UUID.randomUUID();
        reserve.execute(order, List.of(new ReserveStockForOrderUseCase.Line(product, 2))).block();
        release.execute(order).block();

        StepVerifier.create(fulfill.execute(order))
            .expectErrorMatches(e -> isCode(e, InventoryErrorCode.INVALID_RESERVATION_TRANSITION))
            .verify();
    }

    @Test
    void releaseNeverTouchesFulfilledReservations() {
        var row = inventory.add(product, 5, 0);
        var order = UUID.randomUUID();
        reserve.execute(order, List.of(new ReserveStockForOrderUseCase.Line(product, 2))).block();
        fulfill.execute(order).block();

        StepVerifier.create(release.execute(order)).verifyComplete();

        assertThat(inventory.get(row).getAvailableQuantity().value()).isEqualTo(3);
    }

    private static boolean isCode(Throwable e, InventoryErrorCode code) {
        return e instanceof InventoryDomainException ide && ide.getErrorCode() == code;
    }

    /** Stores snapshots and rejects saves whose version is stale, exactly like the {@code @Version} UPDATE. */
    private static final class InMemoryInventory implements InventoryRepository {
        private final Map<InventoryId, InventoryItem> rows = new ConcurrentHashMap<>();
        private final Map<InventoryId, Integer> pendingConcurrentReservation = new ConcurrentHashMap<>();
        final AtomicInteger conflicts = new AtomicInteger();

        InventoryId add(UUID productId, int available, int reserved) {
            var id = InventoryId.generate();
            rows.put(id, InventoryItem.reconstitute(id, ProductId.fromUUID(productId), WarehouseId.generate(),
                Quantity.from(available), ReservedQuantity.from(reserved), ReorderLevel.from(0),
                InventoryStatus.IN_STOCK, Instant.now(), Instant.now(), 1));
            return id;
        }

        /** Another request reserves {@code units} after we read the row but before we save it. */
        void simulateConcurrentWriteOnNextSave(InventoryId id, int units) {
            pendingConcurrentReservation.put(id, units);
        }

        InventoryItem get(InventoryId id) {
            return copy(rows.get(id));
        }

        @Override
        public Mono<InventoryItem> findById(InventoryId id) {
            return Mono.justOrEmpty(rows.get(id)).map(InMemoryInventory::copy);
        }

        @Override
        public Mono<InventoryItem> findByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId) {
            return findByProductId(productId).filter(i -> i.getWarehouseId().equals(warehouseId)).next();
        }

        @Override
        public Flux<InventoryItem> findByProductId(ProductId productId) {
            return Flux.fromIterable(rows.values()).filter(i -> i.getProductId().equals(productId)).map(InMemoryInventory::copy);
        }

        @Override
        public Flux<InventoryItem> findAll() {
            return Flux.fromIterable(rows.values()).map(InMemoryInventory::copy);
        }

        @Override
        public Mono<Boolean> existsByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId) {
            return findByProductIdAndWarehouseId(productId, warehouseId).hasElement();
        }

        @Override
        public Mono<Void> save(InventoryItem item) {
            var concurrent = pendingConcurrentReservation.remove(item.getId());
            if (concurrent != null) {
                var winner = copy(rows.get(item.getId()));
                winner.reserve(Quantity.from(concurrent));
                rows.put(item.getId(), withVersion(winner, winner.getVersion() + 1));
            }
            var current = rows.get(item.getId());
            if (current != null && current.getVersion() != item.getVersion()) {
                conflicts.incrementAndGet();
                return Mono.error(new InventoryDomainException(InventoryErrorCode.OPTIMISTIC_LOCKING_CONFLICT, "stale"));
            }
            rows.put(item.getId(), withVersion(item, item.getVersion() + 1));
            return Mono.empty();
        }

        private static InventoryItem copy(InventoryItem i) {
            return withVersion(i, i.getVersion());
        }

        private static InventoryItem withVersion(InventoryItem i, int version) {
            return InventoryItem.reconstitute(i.getId(), i.getProductId(), i.getWarehouseId(), i.getAvailableQuantity(),
                i.getReservedQuantity(), i.getReorderLevel(), i.getStatus(), i.getCreatedAt(), i.getUpdatedAt(), version);
        }
    }

    private static final class InMemoryReservations implements StockReservationRepository {
        private final Map<UUID, StockReservation> rows = new ConcurrentHashMap<>();

        List<StockReservation> forOrder(UUID orderId) {
            return rows.values().stream().filter(r -> r.getOrderId().equals(orderId)).toList();
        }

        @Override
        public Flux<StockReservation> findByOrderId(UUID orderId) {
            return Flux.fromIterable(forOrder(orderId));
        }

        @Override
        public Mono<StockReservation> findByOrderIdAndProductId(UUID orderId, ProductId productId) {
            return findByOrderId(orderId).filter(r -> r.getProductId().equals(productId)).next();
        }

        @Override
        public Mono<Void> save(StockReservation reservation) {
            rows.put(reservation.getId(), reservation);
            return Mono.empty();
        }
    }
}
