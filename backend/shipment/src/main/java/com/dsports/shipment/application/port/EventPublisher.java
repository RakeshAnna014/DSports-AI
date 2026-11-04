package com.dsports.shipment.application.port;

import com.dsports.shared.domain.kernel.DomainEvent;

@FunctionalInterface
public interface EventPublisher {
    void publish(DomainEvent event);
}
