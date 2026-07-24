package com.dsports.payment.infrastructure.payment.gateway;

import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.domain.payment.model.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class MockPaymentGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private final String mockMode;

    public MockPaymentGateway(@Value("${payment.mock.mode:success}") String mockMode) {
        this.mockMode = mockMode;
    }

    @Override
    public Mono<GatewayResult> createPayment(CreatePaymentRequest request) {
        log.info("MockGateway: Creating payment for reference {} with amount {} {}",
            request.paymentReference().value(), request.amount().amount(), request.amount().currency());

        return simulateProcessing(request.paymentReference().value())
            .map(delay -> {
                if ("success".equalsIgnoreCase(mockMode)) {
                    var txnId = "MOCK-TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                    var gwRef = "MOCK-GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                    log.info("MockGateway: Payment created successfully. TXN: {}, GW Ref: {}", txnId, gwRef);
                    return new GatewayResult(true, txnId, gwRef, "Payment initiated successfully");
                }
                if ("failure".equalsIgnoreCase(mockMode)) {
                    log.warn("MockGateway: Payment creation failed (mock mode: {})", mockMode);
                    return new GatewayResult(false, null, null, "Mock payment declined");
                }
                if ("timeout".equalsIgnoreCase(mockMode)) {
                    log.warn("MockGateway: Payment creation timeout (mock mode: {})", mockMode);
                    return new GatewayResult(false, null, null, "Mock payment gateway timeout");
                }
                log.warn("MockGateway: Payment cancelled by mock (mode: {})", mockMode);
                return new GatewayResult(false, null, null, "Mock payment cancelled by simulation");
            });
    }

    @Override
    public Mono<GatewayResult> capturePayment(String gatewayReference) {
        log.info("MockGateway: Capturing payment for gateway reference {}", gatewayReference);
        return simulateProcessing(gatewayReference)
            .map(delay -> {
                var txnId = "MOCK-TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                return new GatewayResult(true, txnId, gatewayReference, "Payment captured successfully");
            });
    }

    @Override
    public Mono<GatewayResult> cancelPayment(String gatewayReference) {
        log.info("MockGateway: Cancelling payment for gateway reference {}", gatewayReference);
        return simulateProcessing(gatewayReference)
            .map(delay -> new GatewayResult(true, null, gatewayReference, "Payment cancelled successfully"));
    }

    @Override
    public Mono<GatewayResult> refundPayment(String gatewayReference, Money amount) {
        log.info("MockGateway: Refunding {} {} for gateway reference {}",
            amount.amount(), amount.currency(), gatewayReference);
        return simulateProcessing(gatewayReference)
            .map(delay -> {
                var txnId = "MOCK-REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                return new GatewayResult(true, txnId, gatewayReference, "Refund initiated successfully");
            });
    }

    @Override
    public Mono<GatewayStatus> getPaymentStatus(String gatewayReference) {
        log.info("MockGateway: Getting status for gateway reference {}", gatewayReference);
        return simulateProcessing(gatewayReference)
            .map(delay -> new GatewayStatus("SUCCESS", null, gatewayReference));
    }

    private Mono<Long> simulateProcessing(String id) {
        return Mono.delay(java.time.Duration.ofMillis(100));
    }
}
