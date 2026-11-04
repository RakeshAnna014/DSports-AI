package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.command.CreateShipmentCommand;
import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shipment.application.port.CourierGateway.CourierGatewayResult;
import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shipment.application.port.OrderQueryService;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.CourierProvider;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShippingAddress;
import com.dsports.shipment.domain.model.TrackingNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Creates the shipment for a paid order, addressed to the order's immutable delivery-address snapshot.
 * Idempotent: an order has at most one shipment (also enforced by a unique index); asking again returns it.
 */
public class CreateShipmentUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateShipmentUseCase.class);
    private static final Duration COURIER_TIMEOUT = Duration.ofSeconds(10);

    private final ShipmentRepository repository;
    private final OrderQueryService orderQueryService;
    private final CourierGateway courierGateway;
    private final EventPublisher eventPublisher;

    public CreateShipmentUseCase(ShipmentRepository repository,
                                  OrderQueryService orderQueryService,
                                  CourierGateway courierGateway,
                                  EventPublisher eventPublisher) {
        this.repository = repository;
        this.orderQueryService = orderQueryService;
        this.courierGateway = courierGateway;
        this.eventPublisher = eventPublisher;
    }

    public Mono<ShipmentResult> execute(CreateShipmentCommand command) {
        return repository.findByOrderId(command.orderId())
            .map(ShipmentResultMapper::toResult)
            .switchIfEmpty(Mono.defer(() -> createForOrder(command)));
    }

    private Mono<ShipmentResult> createForOrder(CreateShipmentCommand command) {
        return orderQueryService.findOrder(command.orderId())
            .switchIfEmpty(Mono.error(new ShipmentDomainException(ShipmentErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + command.orderId())))
            .flatMap(order -> {
                if (!order.paid()) {
                    return Mono.error(new ShipmentDomainException(ShipmentErrorCode.ORDER_NOT_CONFIRMED,
                        "Order " + command.orderId() + " is not paid"));
                }
                var address = new ShippingAddress(order.street(), order.city(), order.state(),
                    order.zipCode(), order.country());
                var shipment = Shipment.create(ShipmentId.generate(), order.orderId(), order.userId(), address);
                return repository.save(shipment)
                    .then(Mono.defer(() -> assignCourier(shipment)))
                    .then(Mono.fromCallable(() -> {
                        shipment.getDomainEvents().forEach(eventPublisher::publish);
                        shipment.clearDomainEvents();
                        log.info("Shipment {} created for order {}", shipment.getShipmentNumber().value(), order.orderId());
                        return ShipmentResultMapper.toResult(shipment);
                    }))
                    .onErrorResume(ShipmentDomainException.class, e -> e.getErrorCode() == ShipmentErrorCode.ORDER_ALREADY_SHIPPED
                        ? repository.findByOrderId(command.orderId()).map(ShipmentResultMapper::toResult)
                        : Mono.error(e));
            });
    }

    private Mono<Void> assignCourier(Shipment shipment) {
        return courierGateway.createShipment(shipment)
            .timeout(COURIER_TIMEOUT)
            .flatMap(result -> applyCourierResult(result, shipment))
            .onErrorResume(e -> !(e instanceof ShipmentDomainException), e -> {
                log.error("Courier booking failed for shipment {}: {}", shipment.getId(), e.toString());
                shipment.markFailed("Courier unavailable: " + e.getMessage());
                return repository.save(shipment);
            });
    }

    private Mono<Void> applyCourierResult(CourierGatewayResult result, Shipment shipment) {
        if (result.success()) {
            String trackingStr = result.providerReference();
            if (trackingStr == null || trackingStr.isBlank()) {
                trackingStr = TrackingNumber.generate().value();
            }
            shipment.assignCourier(CourierProvider.MOCK, new TrackingNumber(trackingStr));
        } else {
            shipment.markFailed(result.message());
        }
        return repository.save(shipment);
    }
}
