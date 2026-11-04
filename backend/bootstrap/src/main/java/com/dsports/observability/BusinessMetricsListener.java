package com.dsports.observability;

import com.dsports.order.domain.order.event.OrderCancelledEvent;
import com.dsports.order.domain.order.event.OrderPlacedEvent;
import com.dsports.payment.domain.payment.event.PaymentFailedEvent;
import com.dsports.payment.domain.payment.event.PaymentRefundedEvent;
import com.dsports.payment.domain.payment.event.PaymentSucceededEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Counts business outcomes from domain events, which use cases publish only after their transaction committed.
 * Exposed at {@code /actuator/metrics/dsports.*}. Failures that surface as API errors (stock reservation,
 * checkout validation, gateway errors) are counted by code in {@code dsports.api.errors}.
 */
@Component
public class BusinessMetricsListener {

    private final MeterRegistry registry;

    public BusinessMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        registry.counter("dsports.orders.placed", "currency", event.getCurrency()).increment();
    }

    @EventListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        registry.counter("dsports.orders.cancelled").increment();
    }

    @EventListener
    public void onPaymentCaptured(PaymentSucceededEvent event) {
        registry.counter("dsports.payments.captured").increment();
    }

    @EventListener
    public void onPaymentFailed(PaymentFailedEvent event) {
        registry.counter("dsports.payments.failed").increment();
    }

    @EventListener
    public void onRefundCompleted(PaymentRefundedEvent event) {
        registry.counter("dsports.refunds.completed").increment();
    }
}
