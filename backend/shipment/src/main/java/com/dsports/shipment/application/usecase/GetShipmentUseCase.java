package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.query.GetShipmentQuery;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.ShipmentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class GetShipmentUseCase {
    private static final Logger log = LoggerFactory.getLogger(GetShipmentUseCase.class);

    private final ShipmentRepository repository;

    public GetShipmentUseCase(ShipmentRepository repository) {
        this.repository = repository;
    }

    public Mono<ShipmentResult> execute(GetShipmentQuery query) {
        var shipmentId = ShipmentId.fromUUID(query.shipmentId());

        return repository.findById(shipmentId)
                .switchIfEmpty(Mono.error(new ShipmentDomainException(
                        ShipmentErrorCode.SHIPMENT_NOT_FOUND,
                        "Shipment not found: " + query.shipmentId())))
                .flatMap(shipment -> {
                    if (!query.isAdmin() && !shipment.getUserId().equals(query.userId())) {
                        return Mono.error(new ShipmentDomainException(
                                ShipmentErrorCode.SHIPMENT_NOT_OWNED_BY_USER,
                                "Shipment does not belong to user"));
                    }
                    return Mono.just(ShipmentResultMapper.toResult(shipment));
                });
    }
}
