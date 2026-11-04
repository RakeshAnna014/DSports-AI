package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.command.AssignCourierCommand;
import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.domain.model.TrackingNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class AssignCourierUseCase {
    private static final Logger log = LoggerFactory.getLogger(AssignCourierUseCase.class);

    private final ShipmentRepository repository;
    private final CourierGateway courierGateway;
    private final EventPublisher eventPublisher;

    public AssignCourierUseCase(ShipmentRepository repository,
                                 CourierGateway courierGateway,
                                 EventPublisher eventPublisher) {
        this.repository = repository;
        this.courierGateway = courierGateway;
        this.eventPublisher = eventPublisher;
    }

    public Mono<ShipmentResult> execute(AssignCourierCommand command) {
        var shipmentId = ShipmentId.fromUUID(command.shipmentId());

        return repository.findById(shipmentId)
            .switchIfEmpty(Mono.error(new ShipmentDomainException(
                ShipmentErrorCode.SHIPMENT_NOT_FOUND,
                "Shipment not found: " + shipmentId
            )))
            .flatMap(shipment -> {
                if (shipment.getStatus() != ShipmentStatus.CREATED) {
                    return Mono.error(new ShipmentDomainException(
                        ShipmentErrorCode.INVALID_STATUS_TRANSITION,
                        "Shipment " + shipment.getId() + " already has a courier assigned"));
                }
                return courierGateway.createShipment(shipment)
                    .flatMap(result -> {
                        String trackingStr = result.providerReference();
                        if (trackingStr == null || trackingStr.isBlank()) {
                            trackingStr = TrackingNumber.generate().value();
                        }
                        shipment.assignCourier(command.courierProvider(), new TrackingNumber(trackingStr));
                        return repository.save(shipment);
                    })
                    .then(Mono.defer(() -> {
                        shipment.getDomainEvents().forEach(eventPublisher::publish);
                        shipment.clearDomainEvents();
                        log.info("Courier {} assigned to shipment {}",
                            command.courierProvider(), shipment.getId());
                        return Mono.just(ShipmentResultMapper.toResult(shipment));
                    }));
            });
    }
}
