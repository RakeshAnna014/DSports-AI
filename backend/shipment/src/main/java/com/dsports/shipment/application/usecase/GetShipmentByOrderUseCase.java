package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Looks up the shipment of an order together with its tracking history. Only the order's owner (or an admin) may see
 * it; the shipment's own user ID is the ownership check, exactly as for lookup by shipment ID.
 */
public class GetShipmentByOrderUseCase {

    private final ShipmentRepository repository;

    public GetShipmentByOrderUseCase(ShipmentRepository repository) {
        this.repository = repository;
    }

    public record TrackingEvent(String status, String location, String description, Instant eventTime) {}

    public record ShipmentTrackingResult(ShipmentResult shipment, List<TrackingEvent> events) {}

    public Mono<ShipmentTrackingResult> execute(UUID orderId, UUID userId, boolean isAdmin) {
        return repository.findByOrderId(orderId)
            .switchIfEmpty(Mono.error(new ShipmentDomainException(ShipmentErrorCode.SHIPMENT_NOT_FOUND,
                "No shipment has been created for order " + orderId + " yet")))
            .flatMap(shipment -> {
                if (!isAdmin && !shipment.getUserId().equals(userId)) {
                    return Mono.error(new ShipmentDomainException(ShipmentErrorCode.SHIPMENT_NOT_OWNED_BY_USER,
                        "Shipment does not belong to the current user"));
                }
                return repository.findTrackingByShipmentId(shipment.getId())
                    .map(t -> new TrackingEvent(t.getStatus().name(), t.getLocation(), t.getDescription(), t.getEventTime()))
                    .collectList()
                    .map(events -> new ShipmentTrackingResult(ShipmentResultMapper.toResult(shipment), events));
            });
    }
}
