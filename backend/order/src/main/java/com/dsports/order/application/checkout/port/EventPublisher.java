package com.dsports.order.application.checkout.port;

import com.dsports.shared.domain.kernel.DomainEvent;

public interface EventPublisher {
    void publish(DomainEvent event);
}
