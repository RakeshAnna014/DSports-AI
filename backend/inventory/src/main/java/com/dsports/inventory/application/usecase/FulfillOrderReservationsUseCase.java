package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.port.StockReservationRepository;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.ReservationStatus;
import com.dsports.inventory.domain.model.StockReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Converts an order's reservations into shipped stock once payment is captured: the units leave both on-hand and
 * reserved stock. Idempotent: already fulfilled lines are skipped. An order without any reservation is an error,
 * because confirming it would sell stock that was never held.
 */
public class FulfillOrderReservationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(FulfillOrderReservationsUseCase.class);

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository reservationRepository;

    public FulfillOrderReservationsUseCase(InventoryRepository inventoryRepository,
                                           StockReservationRepository reservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
    }

    public Mono<Void> execute(UUID orderId) {
        return reservationRepository.findByOrderId(orderId)
                .collectList()
                .flatMap(reservations -> {
                    if (reservations.isEmpty()) {
                        return Mono.error(new InventoryDomainException(InventoryErrorCode.RESERVATION_NOT_FOUND,
                                "No stock reservation exists for order " + orderId));
                    }
                    var released = reservations.stream().anyMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
                    if (released) {
                        return Mono.error(new InventoryDomainException(InventoryErrorCode.INVALID_RESERVATION_TRANSITION,
                                "Stock for order " + orderId + " was already released"));
                    }
                    return Flux.fromIterable(reservations)
                            .filter(StockReservation::isActive)
                            .concatMap(this::fulfill)
                            .then();
                });
    }

    private Mono<Void> fulfill(StockReservation reservation) {
        return Mono.defer(() -> inventoryRepository.findById(reservation.getInventoryId())
                        .switchIfEmpty(Mono.error(new InventoryDomainException(InventoryErrorCode.INVENTORY_NOT_FOUND,
                                "Inventory " + reservation.getInventoryId().value() + " not found")))
                        .flatMap(item -> {
                            item.fulfillReservation(reservation.getQuantity());
                            return inventoryRepository.save(item);
                        }))
                .retryWhen(ReservationRetry.onOptimisticConflict())
                .then(Mono.defer(() -> {
                    reservation.fulfill();
                    return reservationRepository.save(reservation);
                }))
                .doOnSuccess(v -> log.info("Fulfilled {} x product {} for order {}",
                        reservation.getQuantity().value(), reservation.getProductId().value(), reservation.getOrderId()));
    }
}
