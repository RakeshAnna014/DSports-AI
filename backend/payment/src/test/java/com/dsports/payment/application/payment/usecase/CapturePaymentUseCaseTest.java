package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.CapturePaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentCompletionPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentMethod;
import com.dsports.payment.domain.payment.model.PaymentProvider;
import com.dsports.payment.domain.payment.model.PaymentReference;
import com.dsports.payment.domain.payment.model.PaymentStatus;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CapturePaymentUseCaseTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private OrderPaymentPort orderPaymentPort;
    @Mock private PaymentCompletionPort paymentCompletionPort;
    @Mock private EventPublisher eventPublisher;
    @Mock private OutboxWriter outboxWriter;

    private static final TransactionRunner PASS_THROUGH = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };

    private CapturePaymentUseCase useCase;
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private Payment payment;

    @BeforeEach
    void setUp() {
        useCase = new CapturePaymentUseCase(paymentRepository, paymentGateway, orderPaymentPort, paymentCompletionPort,
            eventPublisher, PASS_THROUGH, outboxWriter, Duration.ofMillis(200));
        payment = Payment.create(PaymentId.generate(), PaymentReference.generate(), orderId, userId,
            new BigDecimal("100.00"), "INR");
        payment.initiatePayment(PaymentMethod.UPI, PaymentProvider.MOCK);
        payment.markProcessing();
        payment.authorize("TXN-AUTH", "GW-1");
        payment.clearDomainEvents();

        when(paymentRepository.findById(payment.getId())).thenReturn(Mono.just(payment));
        when(paymentRepository.save(any())).thenReturn(Mono.empty());
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(order("PENDING_PAYMENT")));
        when(paymentCompletionPort.completeOrder(orderId)).thenReturn(Mono.empty());
        when(outboxWriter.enqueue(any())).thenReturn(Mono.empty());
        when(paymentGateway.capturePayment("GW-1")).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(true, "TXN-CAP", "GW-1", "captured")));
    }

    @Test
    void capturesPaymentAndCompletesOrderInTheSameUnitOfWork() {
        StepVerifier.create(useCase.execute(command()))
            .assertNext(result -> assertThat(result.status()).isEqualTo("SUCCESS"))
            .verifyComplete();

        verify(paymentCompletionPort).completeOrder(orderId);
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxWriter).enqueue(message.capture());
        assertThat(message.getValue().type()).isEqualTo(OutboxMessageTypes.NOTIFY_PAYMENT_SUCCEEDED);
    }

    @Test
    void duplicateCaptureReturnsCapturedPaymentWithoutCallingGatewayAgain() {
        payment.beginCapture();
        payment.markSuccess("TXN-CAP", "GW-1");

        StepVerifier.create(useCase.execute(command()))
            .assertNext(result -> assertThat(result.status()).isEqualTo("SUCCESS"))
            .verifyComplete();
        verify(paymentGateway, never()).capturePayment(any());
        verify(paymentCompletionPort, never()).completeOrder(any());
    }

    @Test
    void declinedCaptureFailsPaymentAndLeavesOrderUntouched() {
        when(paymentGateway.capturePayment("GW-1")).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(false, null, null, "Insufficient funds")));

        StepVerifier.create(useCase.execute(command()))
            .assertNext(result -> {
                assertThat(result.status()).isEqualTo("FAILED");
                assertThat(result.failureReason()).isEqualTo("Insufficient funds");
            })
            .verifyComplete();
        verify(paymentCompletionPort, never()).completeOrder(any());
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxWriter).enqueue(message.capture());
        assertThat(message.getValue().type()).isEqualTo(OutboxMessageTypes.NOTIFY_PAYMENT_FAILED);
    }

    @Test
    void gatewayTimeoutLeavesPaymentClaimedForSafeRetry() {
        when(paymentGateway.capturePayment("GW-1")).thenReturn(Mono.never());

        StepVerifier.create(useCase.execute(command()))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.GATEWAY_TIMEOUT))
            .verify();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(paymentCompletionPort, never()).completeOrder(any());
    }

    @Test
    void retryAfterTimeoutCapturesAndCompletes() {
        payment.beginCapture();

        StepVerifier.create(useCase.execute(command()))
            .assertNext(result -> assertThat(result.status()).isEqualTo("SUCCESS"))
            .verifyComplete();
        verify(paymentCompletionPort).completeOrder(orderId);
    }

    @Test
    void refusesCaptureWhenOrderIsNoLongerAwaitingPayment() {
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(order("CANCELLED")));

        StepVerifier.create(useCase.execute(command()))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.ORDER_NOT_PAYABLE))
            .verify();
        verify(paymentGateway, never()).capturePayment(any());
    }

    @Test
    void failedOrderCompletionKeepsPaymentRetryable() {
        when(paymentCompletionPort.completeOrder(orderId)).thenReturn(Mono.error(new IllegalStateException("db")));

        StepVerifier.create(useCase.execute(command()))
            .expectError(IllegalStateException.class)
            .verify();
        verify(paymentRepository, atLeastOnce()).save(any());
    }

    @Test
    void rejectsCaptureOfAnotherUsersPayment() {
        StepVerifier.create(useCase.execute(new CapturePaymentCommand(payment.getId().value(), UUID.randomUUID())))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER))
            .verify();
        verify(paymentGateway, never()).capturePayment(any());
    }

    @Test
    void rejectsCaptureOfFailedPayment() {
        payment.markFailed("declined");

        StepVerifier.create(useCase.execute(command()))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.INVALID_STATUS_TRANSITION))
            .verify();
    }

    private CapturePaymentCommand command() {
        return new CapturePaymentCommand(payment.getId().value(), userId);
    }

    private OrderPaymentPort.OrderData order(String status) {
        return new OrderPaymentPort.OrderData(orderId, userId, "ORD-1", new BigDecimal("100.00"), "INR", status);
    }

    private static boolean isCode(Throwable e, PaymentErrorCode code) {
        return e instanceof PaymentDomainException pde && pde.getErrorCode() == code;
    }
}
