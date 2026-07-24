package com.dsports.payment.application.payment.port;

import com.dsports.payment.domain.payment.model.Money;
import com.dsports.payment.domain.payment.model.PaymentReference;
import reactor.core.publisher.Mono;

public interface PaymentGateway {
    Mono<GatewayResult> createPayment(CreatePaymentRequest request);
    Mono<GatewayResult> capturePayment(String gatewayReference);
    Mono<GatewayResult> cancelPayment(String gatewayReference);
    Mono<GatewayResult> refundPayment(String gatewayReference, Money amount);
    Mono<GatewayStatus> getPaymentStatus(String gatewayReference);

    record CreatePaymentRequest(
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
