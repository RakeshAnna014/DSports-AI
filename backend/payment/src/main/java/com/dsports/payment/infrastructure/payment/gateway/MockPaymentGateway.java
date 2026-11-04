package com.dsports.payment.infrastructure.payment.gateway;

import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.domain.payment.model.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process stand-in for a payment provider. Like a real provider it is idempotent: capturing an authorisation twice
 * returns the first capture, and a refund request is executed once per idempotency key.
 * <p>
 * Each operation's outcome is configurable ({@code success}, {@code failure} = declined, {@code timeout} = never
 * answers) so failure handling can be exercised end to end:
 * {@code payment.mock.mode}, {@code payment.mock.capture-mode}, {@code payment.mock.refund-mode}.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);
    private static final Duration LATENCY = Duration.ofMillis(100);

    private final String authorizeMode;
    private final String captureMode;
    private final String refundMode;
    private final Map<String, GatewayResult> captures = new ConcurrentHashMap<>();
    private final Map<String, GatewayResult> refunds = new ConcurrentHashMap<>();

    public MockPaymentGateway(@Value("${payment.mock.mode:success}") String authorizeMode,
                              @Value("${payment.mock.capture-mode:success}") String captureMode,
                              @Value("${payment.mock.refund-mode:success}") String refundMode) {
        this.authorizeMode = authorizeMode;
        this.captureMode = captureMode;
        this.refundMode = refundMode;
    }

    @Override
    public Mono<GatewayResult> initiatePayment(InitiatePaymentRequest request) {
        log.info("MockGateway: authorizing {} {} for {}", request.amount().amount(), request.amount().currency(),
            request.paymentReference().value());
        return respond(authorizeMode, () -> new GatewayResult(true, newId("MOCK-TXN-"), newId("MOCK-GW-"),
            "Payment authorized"), "Mock payment declined");
    }

    @Override
    public Mono<GatewayResult> capturePayment(String gatewayReference) {
        var previous = captures.get(gatewayReference);
        if (previous != null) {
            log.info("MockGateway: {} already captured; returning original capture", gatewayReference);
            return Mono.just(previous).delayElement(LATENCY);
        }
        return respond(captureMode, () -> captures.computeIfAbsent(gatewayReference,
            ref -> new GatewayResult(true, newId("MOCK-TXN-"), ref, "Payment captured")), "Mock capture declined");
    }

    @Override
    public Mono<GatewayResult> cancelPayment(String gatewayReference) {
        log.info("MockGateway: voiding authorization {}", gatewayReference);
        return Mono.just(new GatewayResult(true, null, gatewayReference, "Authorization voided")).delayElement(LATENCY);
    }

    @Override
    public Mono<GatewayResult> refundPayment(String gatewayReference, Money amount, String idempotencyKey) {
        var previous = refunds.get(idempotencyKey);
        if (previous != null) {
            log.info("MockGateway: refund {} already executed; returning original result", idempotencyKey);
            return Mono.just(previous).delayElement(LATENCY);
        }
        log.info("MockGateway: refunding {} {} of {}", amount.amount(), amount.currency(), gatewayReference);
        return respond(refundMode, () -> refunds.computeIfAbsent(idempotencyKey,
            key -> new GatewayResult(true, newId("MOCK-REF-"), gatewayReference, "Refund completed")), "Mock refund declined");
    }

    @Override
    public Mono<GatewayStatus> getPaymentStatus(String gatewayReference) {
        var status = captures.containsKey(gatewayReference) ? "CAPTURED" : "AUTHORIZED";
        return Mono.just(new GatewayStatus(status, null, gatewayReference)).delayElement(LATENCY);
    }

    private Mono<GatewayResult> respond(String mode, java.util.function.Supplier<GatewayResult> success, String declineMessage) {
        return switch (mode.toLowerCase()) {
            case "success" -> Mono.fromSupplier(success).delayElement(LATENCY);
            case "timeout" -> Mono.never();
            default -> Mono.just(new GatewayResult(false, null, null, declineMessage)).delayElement(LATENCY);
        };
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
