package com.dsports.notification.infrastructure.event;

import com.dsports.notification.application.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

public class NotificationSpringEventPublisherAdapter implements EventPublisher {
    private final ApplicationEventPublisher applicationEventPublisher;

    public NotificationSpringEventPublisherAdapter(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
