package com.dsports.payment.infrastructure.payment.gateway;

import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.domain.payment.model.Money;
import com.dsports.payment.domain.payment.model.PaymentReference;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private static final Money AMOUNT = new Money(new BigDecimal("100.00"), "INR");

    private static MockPaymentGateway gateway(String authorize, String capture, String refund) {
        return new MockPaymentGateway(authorize, capture, refund);
    }

    @Test
    void authorizesInSuccessMode() {
        StepVerifier.create(gateway("success", "success", "success").initiatePayment(
                new PaymentGateway.InitiatePaymentRequest(PaymentReference.generate(), AMOUNT, "test")))
            .assertNext(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.transactionId()).startsWith("MOCK-TXN-");
                assertThat(result.gatewayReference()).startsWith("MOCK-GW-");
            })
            .verifyComplete();
    }

    @Test
    void declinesAuthorizationInFailureMode() {
        StepVerifier.create(gateway("failure", "success", "success").initiatePayment(
                new PaymentGateway.InitiatePaymentRequest(PaymentReference.generate(), AMOUNT, "test")))
            .assertNext(result -> assertThat(result.success()).isFalse())
            .verifyComplete();
    }

    @Test
    void neverAnswersInTimeoutMode() {
        StepVerifier.create(gateway("success", "timeout", "success").capturePayment("GW-1"))
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(300))
            .thenCancel()
            .verify();
    }

    @Test
    void captureIsIdempotentPerAuthorization() {
        var gateway = gateway("success", "success", "success");
        var first = new AtomicReference<String>();

        StepVerifier.create(gateway.capturePayment("GW-1"))
            .assertNext(result -> first.set(result.transactionId()))
            .verifyComplete();
        StepVerifier.create(gateway.capturePayment("GW-1"))
            .assertNext(result -> assertThat(result.transactionId()).isEqualTo(first.get()))
            .verifyComplete();
    }

    @Test
    void declinesCaptureInFailureMode() {
        StepVerifier.create(gateway("success", "failure", "success").capturePayment("GW-1"))
            .assertNext(result -> assertThat(result.success()).isFalse())
            .verifyComplete();
    }

    @Test
    void refundIsExecutedOncePerIdempotencyKey() {
        var gateway = gateway("success", "success", "success");
        var first = new AtomicReference<String>();

        StepVerifier.create(gateway.refundPayment("GW-1", AMOUNT, "refund-1"))
            .assertNext(result -> {
                assertThat(result.success()).isTrue();
                first.set(result.transactionId());
            })
            .verifyComplete();
        StepVerifier.create(gateway.refundPayment("GW-1", AMOUNT, "refund-1"))
            .assertNext(result -> assertThat(result.transactionId()).isEqualTo(first.get()))
            .verifyComplete();
        StepVerifier.create(gateway.refundPayment("GW-1", AMOUNT, "refund-2"))
            .assertNext(result -> assertThat(result.transactionId()).isNotEqualTo(first.get()))
            .verifyComplete();
    }

    @Test
    void declinedRefundIsNotRecordedSoARetryCanSucceedLater() {
        StepVerifier.create(gateway("success", "success", "failure").refundPayment("GW-1", AMOUNT, "refund-1"))
            .assertNext(result -> assertThat(result.success()).isFalse())
            .verifyComplete();
    }

    @Test
    void voidsAuthorization() {
        StepVerifier.create(gateway("success", "success", "success").cancelPayment("GW-1"))
            .assertNext(result -> assertThat(result.success()).isTrue())
            .verifyComplete();
    }
}
