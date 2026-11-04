package com.dsports.payment.application.payment.port;

import com.dsports.payment.domain.payment.model.Money;
import com.dsports.payment.domain.payment.model.PaymentReference;
import reactor.core.publisher.Mono;

public interface PaymentGateway {
    Mono<GatewayResult> initiatePayment(InitiatePaymentRequest request);
    /** Idempotent: capturing an already captured authorization returns the original capture. */
    Mono<GatewayResult> capturePayment(String gatewayReference);
    Mono<GatewayResult> cancelPayment(String gatewayReference);
    /** Idempotent on {@code idempotencyKey}: repeating a refund request never pays out twice. */
    Mono<GatewayResult> refundPayment(String gatewayReference, Money amount, String idempotencyKey);
    Mono<GatewayStatus> getPaymentStatus(String gatewayReference);

    record InitiatePaymentRequest(
        PaymentReference paymentReference,
        Money amount,
        String description
    ) {}

    record GatewayResult(
        boolean success,
        String transactionId,
        String gatewayReference,
        String message
    ) {}

    record GatewayStatus(
        String status,
        String transactionId,
        String gatewayReference
    ) {}
}
