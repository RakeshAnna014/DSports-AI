package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.application.port.StockReservationRepository;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.StockReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Returns an unpaid order's held units to sellable stock (order cancelled or payment window expired).
 * Idempotent: lines that are already released are skipped; fulfilled lines are never touched.
 */
public class ReleaseOrderReservationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrderReservationsUseCase.class);

    private final InventoryRepository inventoryRepository;
    private final StockReservationRepository reservationRepository;

    public ReleaseOrderReservationsUseCase(InventoryRepository inventoryRepository,
                                           StockReservationRepository reservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
    }

    public Mono<Void> execute(UUID orderId) {
        return reservationRepository.findByOrderId(orderId)
                .filter(StockReservation::isActive)
                .concatMap(this::release)
                .then();
    }

    private Mono<Void> release(StockReservation reservation) {
        return Mono.defer(() -> inventoryRepository.findById(reservation.getInventoryId())
                        .switchIfEmpty(Mono.error(new InventoryDomainException(InventoryErrorCode.INVENTORY_NOT_FOUND,
                                "Inventory " + reservation.getInventoryId().value() + " not found")))
                        .flatMap(item -> {
                            item.releaseReservation(reservation.getQuantity());
                            return inventoryRepository.save(item);
                        }))
                .retryWhen(ReservationRetry.onOptimisticConflict())
                .then(Mono.defer(() -> {
                    reservation.release();
                    return reservationRepository.save(reservation);
                }))
                .doOnSuccess(v -> log.info("Released {} x product {} for order {}",
                        reservation.getQuantity().value(), reservation.getProductId().value(), reservation.getOrderId()));
    }
}
