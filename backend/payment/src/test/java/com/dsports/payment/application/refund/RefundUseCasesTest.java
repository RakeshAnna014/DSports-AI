package com.dsports.payment.application.refund;

import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.refund.port.RefundRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentMethod;
import com.dsports.payment.domain.payment.model.PaymentProvider;
import com.dsports.payment.domain.payment.model.PaymentReference;
import com.dsports.payment.domain.payment.model.PaymentStatus;
import com.dsports.payment.domain.refund.Refund;
import com.dsports.payment.domain.refund.RefundStatus;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundUseCasesTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentGateway paymentGateway;
    @Mock private OrderPaymentPort orderPaymentPort;
    @Mock private EventPublisher eventPublisher;
    @Mock private OutboxWriter outboxWriter;

    private static final TransactionRunner PASS_THROUGH = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };

    private final InMemoryRefunds refunds = new InMemoryRefunds();
    private RequestRefundUseCase requestRefund;
    private ProcessRefundUseCase processRefund;
    private final UUID customer = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private Payment payment;

    @BeforeEach
    void setUp() {
        requestRefund = new RequestRefundUseCase(paymentRepository, refunds);
        processRefund = new ProcessRefundUseCase(refunds, paymentRepository, paymentGateway, orderPaymentPort,
            eventPublisher, PASS_THROUGH, outboxWriter, Duration.ofMillis(200));

        payment = Payment.create(PaymentId.generate(), PaymentReference.generate(), UUID.randomUUID(), customer,
            new BigDecimal("100.00"), "INR");
        payment.initiatePayment(PaymentMethod.UPI, PaymentProvider.MOCK);
        payment.markProcessing();
        payment.authorize("TXN", "GW-1");
        payment.beginCapture();
        payment.markSuccess("TXN", "GW-1");
        payment.clearDomainEvents();

        when(paymentRepository.findById(payment.getId())).thenReturn(Mono.just(payment));
        when(paymentRepository.save(any())).thenReturn(Mono.empty());
        when(orderPaymentPort.markOrderRefunded(any())).thenReturn(Mono.empty());
        when(outboxWriter.enqueue(any())).thenReturn(Mono.empty());
        when(paymentGateway.refundPayment(anyString(), any(), anyString())).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(true, "REF-1", "GW-1", "refunded")));
    }

    @Test
    void customerCannotRequestRefundForAnotherCustomersPayment() {
        StepVerifier.create(requestRefund.execute(customerRequest(UUID.randomUUID(), null, "key-1")))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER))
            .verify();
        assertThat(refunds.store).isEmpty();
    }

    @Test
    void customerRequestIsRecordedButNothingIsPaidOut() {
        StepVerifier.create(requestRefund.execute(customerRequest(customer, null, "key-1")))
            .assertNext(refund -> {
                assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
                assertThat(refund.getAmount()).isEqualByComparingTo("100.00");
            })
            .verifyComplete();
        verify(paymentGateway, never()).refundPayment(anyString(), any(), anyString());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void duplicateRequestWithSameIdempotencyKeyReturnsOriginalRefund() {
        var first = requestRefund.execute(customerRequest(customer, new BigDecimal("40.00"), "key-1")).block();
        var second = requestRefund.execute(customerRequest(customer, new BigDecimal("40.00"), "key-1")).block();

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(refunds.store).hasSize(1);
    }

    @Test
    void requestsCannotExceedRefundableAmountIncludingPendingRequests() {
        requestRefund.execute(customerRequest(customer, new BigDecimal("70.00"), "key-1")).block();

        StepVerifier.create(requestRefund.execute(customerRequest(customer, new BigDecimal("30.01"), "key-2")))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.INVALID_REFUND_AMOUNT))
            .verify();
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        StepVerifier.create(requestRefund.execute(customerRequest(customer, null, " ")))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.VALIDATION_ERROR))
            .verify();
    }

    @Test
    void approvedRefundIsPaidOutAndOnlyThenRecordedOnThePayment() {
        var refund = requestRefund.execute(customerRequest(customer, new BigDecimal("40.00"), "key-1")).block();

        StepVerifier.create(processRefund.execute(refund.getId()))
            .assertNext(done -> assertThat(done.getStatus()).isEqualTo(RefundStatus.COMPLETED))
            .verifyComplete();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("40.00");
        verify(orderPaymentPort, never()).markOrderRefunded(any());
        verify(paymentGateway).refundPayment("GW-1", new com.dsports.payment.domain.payment.model.Money(
            new BigDecimal("40.00"), "INR"), refund.getId().toString());
    }

    @Test
    void fullRefundMarksOrderRefunded() {
        var refund = requestRefund.execute(adminRequest(null, "key-1")).block();

        processRefund.execute(refund.getId()).block();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(orderPaymentPort).markOrderRefunded(payment.getOrderId());
    }

    @Test
    void gatewayDeclineMarksRefundFailedAndLeavesPaymentUntouched() {
        when(paymentGateway.refundPayment(anyString(), any(), anyString())).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(false, null, null, "Refund declined")));
        var refund = requestRefund.execute(adminRequest(null, "key-1")).block();

        StepVerifier.create(processRefund.execute(refund.getId()))
            .assertNext(done -> {
                assertThat(done.getStatus()).isEqualTo(RefundStatus.FAILED);
                assertThat(done.getFailureReason()).isEqualTo("Refund declined");
            })
            .verifyComplete();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void timeoutLeavesRefundProcessingAndRetryUsesSameGatewayKey() {
        when(paymentGateway.refundPayment(anyString(), any(), anyString())).thenReturn(Mono.never());
        var refund = requestRefund.execute(adminRequest(null, "key-1")).block();

        StepVerifier.create(processRefund.execute(refund.getId()))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.GATEWAY_TIMEOUT))
            .verify();
        assertThat(refunds.store.get(refund.getId()).getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        when(paymentGateway.refundPayment(anyString(), any(), anyString())).thenReturn(Mono.just(
            new PaymentGateway.GatewayResult(true, "REF-1", "GW-1", "refunded")));
        StepVerifier.create(processRefund.execute(refund.getId()))
            .assertNext(done -> {
                assertThat(done.getStatus()).isEqualTo(RefundStatus.COMPLETED);
                assertThat(done.getAttempts()).isEqualTo(2);
            })
            .verifyComplete();
        verify(paymentGateway, times(2)).refundPayment(anyString(), any(), org.mockito.ArgumentMatchers.eq(refund.getId().toString()));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void completedRefundCannotBeProcessedAgain() {
        var refund = requestRefund.execute(adminRequest(new BigDecimal("10.00"), "key-1")).block();
        processRefund.execute(refund.getId()).block();

        StepVerifier.create(processRefund.execute(refund.getId()))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.INVALID_REFUND_STATUS))
            .verify();
        assertThat(payment.getRefundedAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void uncapturedPaymentCannotBeRefunded() {
        var open = Payment.create(PaymentId.generate(), PaymentReference.generate(), UUID.randomUUID(), customer,
            new BigDecimal("50.00"), "INR");
        when(paymentRepository.findById(open.getId())).thenReturn(Mono.just(open));

        StepVerifier.create(requestRefund.execute(new RequestRefundUseCase.Command(open.getId().value(), admin, false,
                null, "test", "key-1")))
            .expectErrorMatches(e -> isCode(e, PaymentErrorCode.REFUND_NOT_ALLOWED))
            .verify();
    }

    private RequestRefundUseCase.Command customerRequest(UUID requester, BigDecimal amount, String key) {
        return new RequestRefundUseCase.Command(payment.getId().value(), requester, true, amount, "damaged", key);
    }

    private RequestRefundUseCase.Command adminRequest(BigDecimal amount, String key) {
        return new RequestRefundUseCase.Command(payment.getId().value(), admin, false, amount, "goodwill", key);
    }

    private static boolean isCode(Throwable e, PaymentErrorCode code) {
        return e instanceof PaymentDomainException pde && pde.getErrorCode() == code;
    }

    /** Stores refunds by ID and enforces the idempotency-key uniqueness that the database provides. */
    private static final class InMemoryRefunds implements RefundRepository {
        private final Map<UUID, Refund> store = new ConcurrentHashMap<>();

        @Override
        public Mono<Refund> findById(UUID refundId) {
            return Mono.justOrEmpty(store.get(refundId));
        }

        @Override
        public Flux<Refund> findByPaymentId(PaymentId paymentId) {
            return Flux.fromIterable(store.values()).filter(r -> r.getPaymentId().equals(paymentId));
        }

        @Override
        public Mono<Refund> findByPaymentIdAndIdempotencyKey(PaymentId paymentId, String idempotencyKey) {
            return findByPaymentId(paymentId).filter(r -> r.getIdempotencyKey().equals(idempotencyKey)).next();
        }

        @Override
        public Flux<Refund> findAll() {
            return Flux.fromIterable(store.values());
        }

        @Override
        public Mono<Void> save(Refund refund) {
            store.put(refund.getId(), refund);
            return Mono.empty();
        }
    }
}
