package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.CreatePaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreatePaymentUseCaseTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderPaymentPort orderPaymentPort;
    @Mock
    private PaymentGateway paymentGateway;
    @Mock
    private EventPublisher eventPublisher;

    private CreatePaymentUseCase useCase;
    private UUID userId;
    private UUID orderId;
    private CreatePaymentCommand command;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        command = new CreatePaymentCommand(userId, orderId,
            new BigDecimal("500.00"), "INR", "UPI", "MOCK");
        useCase = new CreatePaymentUseCase(paymentRepository, orderPaymentPort, paymentGateway, eventPublisher);
    }

    @Test
    void shouldCreatePaymentSuccessfully() {
        var orderData = new OrderPaymentPort.OrderData(orderId, userId,
            new BigDecimal("500.00"), "INR", false);
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(orderData));
        when(paymentGateway.initiatePayment(any())).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(true, "TXN-001", "GW-001", "Success")));
        when(paymentRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.orderId()).isEqualTo(orderId);
                assertThat(result.userId()).isEqualTo(userId);
                assertThat(result.amount()).isEqualByComparingTo("500.00");
                assertThat(result.currency()).isEqualTo("INR");
                assertThat(result.paymentMethod()).isEqualTo("UPI");
                assertThat(result.paymentProvider()).isEqualTo("MOCK");
                assertThat(result.status()).isEqualTo("AUTHORIZED");
                assertThat(result.transactionId()).isEqualTo("TXN-001");
                assertThat(result.gatewayReference()).isEqualTo("GW-001");
            })
            .verifyComplete();

        verify(eventPublisher, times(2)).publish(any());
        verify(paymentRepository).save(any());
    }

    @Test
    void shouldRejectAlreadyPaidOrder() {
        var orderData = new OrderPaymentPort.OrderData(orderId, userId,
            new BigDecimal("500.00"), "INR", true);
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(orderData));

        StepVerifier.create(useCase.execute(command))
            .expectErrorMatches(e ->
                e instanceof PaymentDomainException &&
                ((PaymentDomainException) e).getErrorCode() == PaymentErrorCode.ORDER_ALREADY_PAID)
            .verify();
    }

    @Test
    void shouldRejectAmountMismatch() {
        var orderData = new OrderPaymentPort.OrderData(orderId, userId,
            new BigDecimal("600.00"), "INR", false);
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(orderData));

        StepVerifier.create(useCase.execute(command))
            .expectErrorMatches(e ->
                e instanceof PaymentDomainException &&
                ((PaymentDomainException) e).getErrorCode() == PaymentErrorCode.AMOUNT_MISMATCH)
            .verify();
    }

    @Test
    void shouldRejectCurrencyMismatch() {
        var orderData = new OrderPaymentPort.OrderData(orderId, userId,
            new BigDecimal("500.00"), "USD", false);
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(orderData));

        StepVerifier.create(useCase.execute(command))
            .expectErrorMatches(e ->
                e instanceof PaymentDomainException &&
                ((PaymentDomainException) e).getErrorCode() == PaymentErrorCode.AMOUNT_MISMATCH)
            .verify();
    }

    @Test
    void shouldHandleGatewayFailure() {
        var orderData = new OrderPaymentPort.OrderData(orderId, userId,
            new BigDecimal("500.00"), "INR", false);
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(orderData));
        when(paymentGateway.initiatePayment(any())).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(false, null, null, "Insufficient funds")));
        when(paymentRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result.status()).isEqualTo("FAILED");
                assertThat(result.failureReason()).contains("Insufficient funds");
            })
            .verifyComplete();
    }

    @Test
    void shouldHandleGatewayError() {
        var orderData = new OrderPaymentPort.OrderData(orderId, userId,
            new BigDecimal("500.00"), "INR", false);
        when(orderPaymentPort.getOrderData(orderId, userId)).thenReturn(Mono.just(orderData));
        when(paymentGateway.initiatePayment(any())).thenReturn(
            Mono.error(new RuntimeException("Gateway timeout")));
        when(paymentRepository.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertThat(result.status()).isEqualTo("FAILED");
                assertThat(result.failureReason()).contains("Gateway timeout");
            })
            .verifyComplete();
    }

    @Test
    void shouldRejectInvalidPaymentMethod() {
        var invalidCommand = new CreatePaymentCommand(userId, orderId,
            new BigDecimal("500.00"), "INR", "INVALID", "MOCK");

        var ex = org.junit.jupiter.api.Assertions.assertThrows(PaymentDomainException.class,
            () -> useCase.execute(invalidCommand));
        org.junit.jupiter.api.Assertions.assertEquals(PaymentErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void shouldRejectInvalidPaymentProvider() {
        var invalidCommand = new CreatePaymentCommand(userId, orderId,
            new BigDecimal("500.00"), "INR", "UPI", "INVALID");

        var ex = org.junit.jupiter.api.Assertions.assertThrows(PaymentDomainException.class,
            () -> useCase.execute(invalidCommand));
        org.junit.jupiter.api.Assertions.assertEquals(PaymentErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }
}
