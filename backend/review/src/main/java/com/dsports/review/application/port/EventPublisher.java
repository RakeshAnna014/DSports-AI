package com.dsports.review.application.port;

import com.dsports.shared.domain.kernel.DomainEvent;

@FunctionalInterface
public interface EventPublisher {
    void publish(DomainEvent event);
}
