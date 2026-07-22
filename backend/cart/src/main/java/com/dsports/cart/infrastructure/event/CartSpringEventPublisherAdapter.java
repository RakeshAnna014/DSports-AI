package com.dsports.cart.infrastructure.event;

import com.dsports.cart.application.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

public class CartSpringEventPublisherAdapter implements EventPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;

    public CartSpringEventPublisherAdapter(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        events.forEach(applicationEventPublisher::publishEvent);
    }
}
