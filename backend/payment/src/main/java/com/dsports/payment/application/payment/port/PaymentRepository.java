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
    Mono<Payment> findByOrderId(UUID orderId);
    Flux<Payment> findByUserId(UUID userId);
    Mono<Boolean> existsByOrderIdAndStatus(UUID orderId, String status);
    Mono<Void> save(Payment payment);
}
