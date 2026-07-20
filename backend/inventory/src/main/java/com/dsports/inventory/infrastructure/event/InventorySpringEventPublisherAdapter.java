package com.dsports.inventory.infrastructure.event;

import com.dsports.inventory.application.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

public class InventorySpringEventPublisherAdapter implements EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public InventorySpringEventPublisherAdapter(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        events.forEach(this::publish);
    }
}
