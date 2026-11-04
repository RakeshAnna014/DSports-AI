package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.port.StockReservationRepository;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.InventoryItem;
import com.dsports.inventory.domain.model.ProductId;
import com.dsports.inventory.domain.model.Quantity;
import com.dsports.inventory.domain.model.StockReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds stock for every line of an order. Idempotent per (order, product): repeating the call, for example after a
 * client timeout, returns without reserving twice. Callers run it inside the order-placement transaction so a
 * failure on any line leaves no partial reservation behind.
 */
public class ReserveStockForOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReserveStockForOrderUseCase.class);

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository reservationRepository;

    public ReserveStockForOrderUseCase(InventoryRepository inventoryRepository,
                                       StockReservationRepository reservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
    }

    public record Line(UUID productId, int quantity) {}

    public Mono<Void> execute(UUID orderId, List<Line> lines) {
        return Flux.fromIterable(lines)
                .concatMap(line -> reserveLine(orderId, ProductId.fromUUID(line.productId()), Quantity.from(line.quantity())))
                .then();
    }

    private Mono<Void> reserveLine(UUID orderId, ProductId productId, Quantity quantity) {
        // Branch on presence explicitly: an "already reserved" outcome completes empty, so switchIfEmpty would
        // wrongly reserve a second time.
        return reservationRepository.findByOrderIdAndProductId(orderId, productId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> {
                    if (existing.isEmpty()) {
                        return Mono.defer(() -> allocate(orderId, productId, quantity))
                                .retryWhen(ReservationRetry.onOptimisticConflict());
                    }
                    if (existing.get().getQuantity().equals(quantity)) {
                        log.debug("Reservation for order {} product {} already exists", orderId, productId.value());
                        return Mono.<Void>empty();
                    }
                    return Mono.<Void>error(new InventoryDomainException(InventoryErrorCode.RESERVATION_CONFLICT,
                            "Order " + orderId + " already holds " + existing.get().getQuantity().value()
                                    + " units of product " + productId.value()));
                });
    }

    private Mono<Void> allocate(UUID orderId, ProductId productId, Quantity quantity) {
        return inventoryRepository.findByProductId(productId)
                .collectList()
                .flatMap(rows -> {
                    var candidate = rows.stream()
                            .filter(row -> row.getSellableQuantity() >= quantity.value())
                            .max(Comparator.comparingInt(InventoryItem::getSellableQuantity));
                    if (candidate.isEmpty()) {
                        var sellable = rows.stream().mapToInt(InventoryItem::getSellableQuantity).sum();
                        return Mono.error(new InventoryDomainException(InventoryErrorCode.INSUFFICIENT_STOCK,
                                "Insufficient stock for product " + productId.value()
                                        + ". Requested: " + quantity.value() + ", available: " + Math.max(sellable, 0)));
                    }
                    var item = candidate.get();
                    item.reserve(quantity);
                    var reservation = StockReservation.create(orderId, productId, item.getId(), quantity);
                    return inventoryRepository.save(item)
                            .then(reservationRepository.save(reservation))
                            .doOnSuccess(v -> log.info("Reserved {} x product {} for order {} from inventory {}",
                                    quantity.value(), productId.value(), orderId, item.getId().value()));
                });
    }
}
