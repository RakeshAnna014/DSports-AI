package com.dsports.order.infrastructure.checkout.config;

import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class CheckoutSpringEventPublisherAdapter implements EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(CheckoutSpringEventPublisherAdapter.class);

    private final ApplicationEventPublisher springEventPublisher;

    public CheckoutSpringEventPublisherAdapter(ApplicationEventPublisher springEventPublisher) {
        this.springEventPublisher = springEventPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        log.debug("Publishing domain event: {}", event.getClass().getSimpleName());
        springEventPublisher.publishEvent(event);
    }
}
