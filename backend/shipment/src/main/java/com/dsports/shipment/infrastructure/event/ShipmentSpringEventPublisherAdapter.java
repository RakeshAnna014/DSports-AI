package com.dsports.shipment.infrastructure.event;

import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

public class ShipmentSpringEventPublisherAdapter implements EventPublisher {
    private final ApplicationEventPublisher springPublisher;

    public ShipmentSpringEventPublisherAdapter(ApplicationEventPublisher springPublisher) {
        this.springPublisher = springPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        springPublisher.publishEvent(event);
    }
}
