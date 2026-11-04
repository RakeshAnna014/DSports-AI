package com.dsports.payment.application.refund.port;

import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.refund.Refund;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface RefundRepository {

    Mono<Refund> findById(UUID refundId);

    Flux<Refund> findByPaymentId(PaymentId paymentId);

    Mono<Refund> findByPaymentIdAndIdempotencyKey(PaymentId paymentId, String idempotencyKey);

    Flux<Refund> findAll();

    Mono<Void> save(Refund refund);
}
