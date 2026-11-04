package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.CreatePaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentMethod;
import com.dsports.payment.domain.payment.model.PaymentProvider;
import com.dsports.payment.domain.payment.model.PaymentReference;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatePaymentUseCaseTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private OrderPaymentPort orderPaymentPort;
    @Mock private PaymentGateway paymentGateway;
    @Mock private EventPublisher eventPublisher;
    @Mock private OutboxWriter outboxWriter;

    private static final TransactionRunner PASS_THROUGH = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };

    private CreatePaymentUseCase useCase;
    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final BigDecimal total = new BigDecimal("217.38");

    @BeforeEach
    void setUp() {
        useCase = new CreatePaymentUseCase(paymentRepository, orderPaymentPort, paymentGateway, eventPublisher,
            PASS_THROUGH, outboxWriter, Duration.ofMillis(200));
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(order("PENDING_PAYMENT")));
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Flux.empty());
        when(paymentRepository.save(any())).thenReturn(Mono.empty());
        when(outboxWriter.enqueue(any())).thenReturn(Mono.empty());
        when(paymentGateway.initiatePayment(any())).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(true, "TXN-1", "GW-1", "ok")));
    }

    @Test
    void authorizesPaymentForOrderAwaitingPayment() {
        StepVerifier.create(useCase.execute(command(total)))
            .assertNext(result -> {
                assertThat(result.status()).isEqualTo("AUTHORIZED");
                assertThat(result.amount()).isEqualByComparingTo(total);
            })
            .verifyComplete();
        verify(paymentRepository).save(any());
    }

    @Test
    void returnsOpenPaymentInsteadOfAuthorizingTwice() {
        var open = authorizedPayment();
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Flux.just(open));

        StepVerifier.create(useCase.execute(command(total)))
            .assertNext(result -> assertThat(result.id()).isEqualTo(open.getId().value()))
            .verifyComplete();
        verify(paymentGateway, never()).initiatePayment(any());
    }

    @Test
    void rejectsPaymentWhenOrderAlreadyCaptured() {
        var captured = authorizedPayment();
        captured.beginCapture();
        captured.markSuccess("TXN-1", "GW-1");
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Flux.just(captured));

        StepVerifier.create(useCase.execute(command(total)))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.ORDER_ALREADY_PAID))
            .verify();
    }

    @Test
    void rejectsPaymentForPaidOrder() {
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(order("CONFIRMED")));
        StepVerifier.create(useCase.execute(command(total)))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.ORDER_ALREADY_PAID))
            .verify();
    }

    @Test
    void rejectsPaymentForCancelledOrder() {
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(order("CANCELLED")));
        StepVerifier.create(useCase.execute(command(total)))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.ORDER_NOT_PAYABLE))
            .verify();
        verify(paymentGateway, never()).initiatePayment(any());
    }

    @Test
    void rejectsAmountThatDoesNotMatchOrderTotal() {
        StepVerifier.create(useCase.execute(command(new BigDecimal("1.00"))))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.AMOUNT_MISMATCH))
            .verify();
    }

    @Test
    void declinedAuthorizationIsRecordedAsFailedWithNotification() {
        when(paymentGateway.initiatePayment(any())).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(false, null, null, "Card declined")));

        StepVerifier.create(useCase.execute(command(total)))
            .assertNext(result -> {
                assertThat(result.status()).isEqualTo("FAILED");
                assertThat(result.failureReason()).isEqualTo("Card declined");
            })
            .verifyComplete();
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxWriter).enqueue(message.capture());
        assertThat(message.getValue().type()).isEqualTo(OutboxMessageTypes.NOTIFY_PAYMENT_FAILED);
    }

    @Test
    void gatewayTimeoutFailsThePaymentAttempt() {
        when(paymentGateway.initiatePayment(any())).thenReturn(Mono.never());

        StepVerifier.create(useCase.execute(command(total)))
            .assertNext(result -> assertThat(result.failureReason()).isEqualTo("Payment gateway timed out"))
            .verifyComplete();
    }

    @Test
    void concurrentDuplicateVoidsItsAuthorizationAndFails() {
        when(paymentRepository.save(any())).thenReturn(Mono.error(
            new PaymentDomainException(PaymentErrorCode.DUPLICATE_PAYMENT, "duplicate")));

        StepVerifier.create(useCase.execute(command(total)))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.DUPLICATE_PAYMENT))
            .verify();
        var message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxWriter).enqueue(message.capture());
        assertThat(message.getValue().type()).isEqualTo(OutboxMessageTypes.VOID_PAYMENT_AUTHORIZATION);
        assertThat(message.getValue().payload()).containsEntry("gatewayReference", "GW-1");
    }

    @Test
    void rejectsUnknownPaymentMethod() {
        var command = new CreatePaymentCommand(userId, orderId, total, "INR", "BITCOIN", "MOCK");
        org.junit.jupiter.api.Assertions.assertThrows(PaymentDomainException.class, () -> useCase.execute(command));
    }

    private CreatePaymentCommand command(BigDecimal amount) {
        return new CreatePaymentCommand(userId, orderId, amount, "INR", "UPI", "MOCK");
    }

    private OrderPaymentPort.OrderData order(String status) {
        return new OrderPaymentPort.OrderData(orderId, userId, "ORD-1", total, "INR", status);
    }

    private Payment authorizedPayment() {
        var payment = Payment.create(PaymentId.generate(), PaymentReference.generate(), orderId, userId, total, "INR");
        payment.initiatePayment(PaymentMethod.UPI, PaymentProvider.MOCK);
        payment.markProcessing();
        payment.authorize("TXN-1", "GW-1");
        return payment;
    }

    private static boolean isCode(Throwable e, PaymentErrorCode code) {
        return e instanceof PaymentDomainException pde && pde.getErrorCode() == code;
    }
}
