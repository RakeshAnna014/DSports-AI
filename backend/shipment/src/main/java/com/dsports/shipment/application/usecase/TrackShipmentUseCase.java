package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.query.TrackShipmentQuery;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.TrackingNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class TrackShipmentUseCase {
    private static final Logger log = LoggerFactory.getLogger(TrackShipmentUseCase.class);

    private final ShipmentRepository repository;

    public TrackShipmentUseCase(ShipmentRepository repository) {
        this.repository = repository;
    }

    public Mono<ShipmentResult> execute(TrackShipmentQuery query) {
        var trackingNumber = new TrackingNumber(query.trackingNumber());

        return repository.findByTrackingNumber(trackingNumber)
            .switchIfEmpty(Mono.error(new ShipmentDomainException(
                ShipmentErrorCode.SHIPMENT_NOT_FOUND,
                "Shipment not found for tracking number: " + trackingNumber.value()
            )))
            .filter(shipment -> query.isAdmin() || shipment.getUserId().equals(query.userId()))
            .switchIfEmpty(Mono.error(new ShipmentDomainException(
                ShipmentErrorCode.SHIPMENT_NOT_FOUND,
                "Shipment not found for tracking number: " + trackingNumber.value()
            )))
            .map(ShipmentResultMapper::toResult);
    }
}
