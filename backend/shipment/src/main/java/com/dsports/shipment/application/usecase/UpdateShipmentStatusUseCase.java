package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.command.UpdateShipmentStatusCommand;
import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shipment.application.port.OrderFulfilmentPort;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentTracking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public class UpdateShipmentStatusUseCase {
    private static final Logger log = LoggerFactory.getLogger(UpdateShipmentStatusUseCase.class);

    private final ShipmentRepository repository;
    private final CourierGateway courierGateway;
    private final EventPublisher eventPublisher;
    private final OrderFulfilmentPort orderFulfilmentPort;
    private final TransactionRunner transactionRunner;

    public UpdateShipmentStatusUseCase(ShipmentRepository repository,
                                        CourierGateway courierGateway,
                                        EventPublisher eventPublisher,
                                        OrderFulfilmentPort orderFulfilmentPort,
                                        TransactionRunner transactionRunner) {
        this.repository = repository;
        this.courierGateway = courierGateway;
        this.eventPublisher = eventPublisher;
        this.orderFulfilmentPort = orderFulfilmentPort;
        this.transactionRunner = transactionRunner;
    }

    public Mono<ShipmentResult> execute(UpdateShipmentStatusCommand command) {
        var shipmentId = ShipmentId.fromUUID(command.shipmentId());

        return transactionRunner.inTransaction(() -> repository.findById(shipmentId)
            .switchIfEmpty(Mono.error(new ShipmentDomainException(
                ShipmentErrorCode.SHIPMENT_NOT_FOUND,
                "Shipment not found: " + shipmentId
            )))
            .flatMap(shipment -> {
                applyTransition(shipment, command);

                var tracking = new ShipmentTracking(
                    UUID.randomUUID(),
                    shipment.getId(),
                    shipment.getStatus(),
                    null,
                    "Status updated to " + shipment.getStatus(),
                    Instant.now()
                );

                return repository.save(shipment)
                    .then(Mono.defer(() -> repository.saveTracking(tracking)))
                    .then(Mono.defer(() -> orderFulfilmentPort.shipmentProgressed(shipment.getOrderId(), shipment.getStatus())))
                    .thenReturn(shipment);
            }))
            .map(shipment -> {
                shipment.getDomainEvents().forEach(eventPublisher::publish);
                shipment.clearDomainEvents();
                log.info("Shipment {} status updated to {}", shipment.getId(), shipment.getStatus());
                return ShipmentResultMapper.toResult(shipment);
            });
    }

    private void applyTransition(Shipment shipment, UpdateShipmentStatusCommand command) {
        switch (command.newStatus()) {
            case PICKED_UP -> shipment.pickup();
            case IN_TRANSIT -> shipment.transit();
            case OUT_FOR_DELIVERY -> shipment.outForDelivery();
            case DELIVERED -> shipment.deliver();
            case FAILED -> shipment.markFailed(command.reason());
            case RETURNED -> shipment.returnShipment();
            default -> throw new ShipmentDomainException(
                ShipmentErrorCode.INVALID_STATUS_TRANSITION,
                "Unsupported status transition: " + command.newStatus()
            );
        }
    }
}
