package com.dsports.notification.application.port;

import com.dsports.shared.domain.kernel.DomainEvent;

public interface EventPublisher {
    void publish(DomainEvent event);
}
