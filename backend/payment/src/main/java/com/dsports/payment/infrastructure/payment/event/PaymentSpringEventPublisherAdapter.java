package com.dsports.payment.infrastructure.payment.event;

import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.shared.domain.kernel.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class PaymentSpringEventPublisherAdapter implements EventPublisher {
    private final ApplicationEventPublisher springEventPublisher;

    public PaymentSpringEventPublisherAdapter(ApplicationEventPublisher springEventPublisher) {
        this.springEventPublisher = springEventPublisher;
    }

    @Override
    public void publish(DomainEvent event) {
        springEventPublisher.publishEvent(event);
    }
}
