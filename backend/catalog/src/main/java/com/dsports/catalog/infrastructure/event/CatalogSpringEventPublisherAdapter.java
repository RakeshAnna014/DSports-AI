package com.dsports.catalog.infrastructure.event;

import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

public class CatalogSpringEventPublisherAdapter implements EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public CatalogSpringEventPublisherAdapter(ApplicationEventPublisher applicationEventPublisher) {
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
