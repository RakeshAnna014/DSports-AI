package com.dsports.review.infrastructure.event;

import com.dsports.review.application.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

public class ReviewSpringEventPublisherAdapter implements EventPublisher {
    private final ApplicationEventPublisher springPublisher;

    public ReviewSpringEventPublisherAdapter(ApplicationEventPublisher springPublisher) {
        this.springPublisher = springPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        springPublisher.publishEvent(event);
    }
}
