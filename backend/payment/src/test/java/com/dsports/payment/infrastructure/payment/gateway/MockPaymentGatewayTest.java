package com.dsports.payment.infrastructure.payment.gateway;

import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.domain.payment.model.Money;
import com.dsports.payment.domain.payment.model.PaymentReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private PaymentReference reference;
    private Money amount;
    private PaymentGateway.InitiatePaymentRequest request;

    @BeforeEach
    void setUp() {
        reference = PaymentReference.generate();
        amount = new Money(BigDecimal.valueOf(1000), "INR");
        request = new PaymentGateway.InitiatePaymentRequest(reference, amount, "Test payment");
    }

    @Test
    void shouldInitiatePaymentSuccessfully() {
        var gateway = new MockPaymentGateway("success");

        StepVerifier.create(gateway.initiatePayment(request))
            .assertNext(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.transactionId()).isNotNull();
                assertThat(result.gatewayReference()).isNotNull();
                assertThat(result.message()).contains("successfully");
            })
            .verifyComplete();
    }

    @Test
    void shouldFailPaymentWhenMockModeIsFailure() {
        var gateway = new MockPaymentGateway("failure");

        StepVerifier.create(gateway.initiatePayment(request))
            .assertNext(result -> {
                assertThat(result.success()).isFalse();
                assertThat(result.message()).contains("declined");
            })
            .verifyComplete();
    }

    @Test
    void shouldFailPaymentWhenMockModeIsTimeout() {
        var gateway = new MockPaymentGateway("timeout");

        StepVerifier.create(gateway.initiatePayment(request))
            .assertNext(result -> {
                assertThat(result.success()).isFalse();
                assertThat(result.message()).contains("timeout");
            })
            .verifyComplete();
    }

    @Test
    void shouldCapturePaymentSuccessfully() {
        var gateway = new MockPaymentGateway("success");

        StepVerifier.create(gateway.capturePayment("GW-001"))
            .assertNext(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.transactionId()).isNotNull();
                assertThat(result.gatewayReference()).isEqualTo("GW-001");
            })
            .verifyComplete();
    }

    @Test
    void shouldCancelPaymentSuccessfully() {
        var gateway = new MockPaymentGateway("success");

        StepVerifier.create(gateway.cancelPayment("GW-001"))
            .assertNext(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.message()).contains("cancelled");
            })
            .verifyComplete();
    }

    @Test
    void shouldRefundPaymentSuccessfully() {
        var gateway = new MockPaymentGateway("success");
        var refundAmount = new Money(BigDecimal.valueOf(500), "INR");

        StepVerifier.create(gateway.refundPayment("GW-001", refundAmount))
            .assertNext(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.transactionId()).isNotNull();
                assertThat(result.message()).contains("Refund");
            })
            .verifyComplete();
    }

    @Test
    void shouldGetPaymentStatus() {
        var gateway = new MockPaymentGateway("success");

        StepVerifier.create(gateway.getPaymentStatus("GW-001"))
            .assertNext(status -> {
                assertThat(status.status()).isEqualTo("SUCCESS");
            })
            .verifyComplete();
    }
}
