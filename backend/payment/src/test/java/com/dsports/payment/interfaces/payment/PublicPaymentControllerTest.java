package com.dsports.payment.interfaces.payment;

import com.dsports.payment.application.payment.command.CancelPaymentCommand;
import com.dsports.payment.application.payment.command.CapturePaymentCommand;
import com.dsports.payment.application.payment.command.CreatePaymentCommand;
import com.dsports.payment.application.payment.command.RefundPaymentCommand;
import com.dsports.payment.application.payment.query.GetPaymentHistoryQuery;
import com.dsports.payment.application.payment.query.GetPaymentQuery;
import com.dsports.payment.application.payment.result.PaymentResult;
import com.dsports.payment.application.payment.result.PaymentSummaryResult;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.application.payment.usecase.CapturePaymentUseCase;
import com.dsports.payment.application.payment.usecase.CreatePaymentUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentHistoryUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentUseCase;
import com.dsports.payment.application.payment.usecase.RefundPaymentUseCase;
import com.dsports.payment.interfaces.payment.dto.CapturePaymentRequest;
import com.dsports.payment.interfaces.payment.dto.CreatePaymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicPaymentControllerTest {

    @Mock
    private CreatePaymentUseCase createPaymentUseCase;
    @Mock
    private CapturePaymentUseCase capturePaymentUseCase;
    @Mock
    private GetPaymentUseCase getPaymentUseCase;
    @Mock
    private GetPaymentHistoryUseCase getPaymentHistoryUseCase;
    @Mock
    private CancelPaymentUseCase cancelPaymentUseCase;
    @Mock
    private RefundPaymentUseCase refundPaymentUseCase;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private PublicPaymentController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final BigDecimal amount = BigDecimal.valueOf(500.00);

    private PaymentResult sampleResult() {
        return new PaymentResult(
            paymentId, "PAY-REF-001", orderId, userId,
            amount, "INR", "UPI", "MOCK",
            "TXN-001", "GW-001", "SUCCESS", null,
            Instant.now(), 0, Instant.now(), Instant.now()
        );
    }

    private PaymentSummaryResult sampleSummary() {
        return new PaymentSummaryResult(
            paymentId, "PAY-REF-001", orderId,
            amount, "INR", "UPI", "SUCCESS",
            Instant.now(), Instant.now()
        );
    }

    @Test
    void shouldCreatePayment() {
        when(authentication.getPrincipal()).thenReturn(userId.toString());
        when(createPaymentUseCase.execute(any(CreatePaymentCommand.class)))
            .thenReturn(Mono.just(sampleResult()));

        var request = new CreatePaymentRequest(orderId, amount, "INR", "UPI", "MOCK");

        StepVerifier.create(controller.createPayment(authentication, request))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(paymentId);
                assertThat(response.status()).isEqualTo("SUCCESS");
                assertThat(response.paymentReference()).isEqualTo("PAY-REF-001");
            })
            .verifyComplete();
    }

    @Test
    void shouldGetPayment() {
        when(authentication.getPrincipal()).thenReturn(userId.toString());
        when(getPaymentUseCase.execute(any(GetPaymentQuery.class)))
            .thenReturn(Mono.just(sampleResult()));

        StepVerifier.create(controller.getPayment(paymentId, authentication))
            .assertNext(response -> {
                assertThat(response.id()).isEqualTo(paymentId);
                assertThat(response.status()).isEqualTo("SUCCESS");
            })
            .verifyComplete();
    }

    @Test
    void shouldGetPaymentHistory() {
        when(authentication.getPrincipal()).thenReturn(userId.toString());
        when(getPaymentHistoryUseCase.execute(any(GetPaymentHistoryQuery.class)))
            .thenReturn(Flux.just(sampleSummary()));

        StepVerifier.create(controller.getPaymentHistory(authentication))
            .assertNext(response -> {
                assertThat(response.paymentReference()).isEqualTo("PAY-REF-001");
                assertThat(response.status()).isEqualTo("SUCCESS");
            })
            .verifyComplete();
    }

    @Test
    void shouldCancelPayment() {
        when(authentication.getPrincipal()).thenReturn(userId.toString());
        var cancelledResult = new PaymentResult(
            paymentId, "PAY-REF-001", orderId, userId,
            amount, "INR", "UPI", "MOCK",
            null, null, "CANCELLED", null,
            null, 0, Instant.now(), Instant.now()
        );
        when(cancelPaymentUseCase.execute(any(CancelPaymentCommand.class)))
            .thenReturn(Mono.just(cancelledResult));

        StepVerifier.create(controller.cancelPayment(paymentId, authentication))
            .assertNext(response -> {
                assertThat(response.status()).isEqualTo("CANCELLED");
            })
            .verifyComplete();
    }

    @Test
    void shouldRefundPayment() {
        when(authentication.getPrincipal()).thenReturn(userId.toString());
        var refundedResult = new PaymentResult(
            paymentId, "PAY-REF-001", orderId, userId,
            amount, "INR", "UPI", "MOCK",
            "TXN-001", "GW-001", "REFUNDED", null,
            Instant.now(), 0, Instant.now(), Instant.now()
        );
        when(refundPaymentUseCase.execute(any(RefundPaymentCommand.class)))
            .thenReturn(Mono.just(refundedResult));

        StepVerifier.create(controller.refundPayment(paymentId, authentication))
            .assertNext(response -> {
                assertThat(response.status()).isEqualTo("REFUNDED");
            })
            .verifyComplete();
    }

    @Test
    void shouldCapturePayment() {
        when(authentication.getPrincipal()).thenReturn(userId.toString());
        when(capturePaymentUseCase.execute(any(CapturePaymentCommand.class)))
            .thenReturn(Mono.just(sampleResult()));

        var request = new CapturePaymentRequest("TXN-001", "GW-001");

        StepVerifier.create(controller.capturePayment(paymentId, authentication, request))
            .assertNext(response -> {
                assertThat(response.status()).isEqualTo("SUCCESS");
                assertThat(response.transactionId()).isEqualTo("TXN-001");
            })
            .verifyComplete();
    }
}
