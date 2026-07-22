package com.dsports.cart.application.port;

import com.dsports.shared.domain.kernel.DomainEvent;
import java.util.List;

public interface EventPublisher {
    void publish(DomainEvent event);
    void publishAll(List<DomainEvent> events);
}
