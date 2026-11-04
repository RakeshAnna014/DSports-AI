package com.dsports.payment.application.payment.port;

import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PaymentRepository {
    Mono<Payment> findById(PaymentId id);
    Mono<Payment> findByPaymentReference(PaymentReference paymentReference);
    /** All payment attempts for an order, newest first. */
    Flux<Payment> findByOrderId(UUID orderId);
    Flux<Payment> findByUserId(UUID userId);
    Mono<Void> save(Payment payment);
}
