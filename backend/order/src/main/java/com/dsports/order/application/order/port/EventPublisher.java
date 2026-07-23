package com.dsports.order.application.order.port;

import com.dsports.shared.domain.kernel.DomainEvent;

public interface EventPublisher {
    void publish(DomainEvent event);
}
