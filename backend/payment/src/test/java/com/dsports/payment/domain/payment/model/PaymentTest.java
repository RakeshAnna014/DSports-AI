package com.dsports.payment.domain.payment.model;

import com.dsports.payment.domain.payment.event.PaymentAuthorizedEvent;
import com.dsports.payment.domain.payment.event.PaymentCancelledEvent;
import com.dsports.payment.domain.payment.event.PaymentCreatedEvent;
import com.dsports.payment.domain.payment.event.PaymentFailedEvent;
import com.dsports.payment.domain.payment.event.PaymentRefundedEvent;
import com.dsports.payment.domain.payment.event.PaymentSucceededEvent;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("1000.00");
    private static final String CURRENCY = "INR";
    private static final PaymentMethod METHOD = PaymentMethod.UPI;
    private static final PaymentProvider PROVIDER = PaymentProvider.MOCK;

    private Payment payment;

    @BeforeEach
    void setUp() {
        var id = PaymentId.generate();
        var reference = PaymentReference.generate();
        payment = Payment.create(id, reference, ORDER_ID, USER_ID, AMOUNT, CURRENCY);
    }

    @Nested
    @DisplayName("Payment Creation")
    class PaymentCreation {

        @Test
        @DisplayName("Should create payment with CREATED status")
        void shouldCreatePayment() {
            assertNotNull(payment.getId());
            assertNotNull(payment.getPaymentReference());
            assertEquals(ORDER_ID, payment.getOrderId());
            assertEquals(USER_ID, payment.getUserId());
            assertEquals(AMOUNT, payment.getAmount());
            assertEquals(CURRENCY, payment.getCurrency());
            assertEquals(PaymentStatus.CREATED, payment.getStatus());
            assertNull(payment.getPaymentMethod());
            assertNull(payment.getPaymentProvider());
            assertNull(payment.getTransactionId());
            assertNull(payment.getGatewayReference());
            assertNull(payment.getFailureReason());
            assertNull(payment.getPaidAt());
            assertEquals(0, payment.getVersion());
            assertNotNull(payment.getCreatedAt());
            assertNotNull(payment.getUpdatedAt());
        }

        @Test
        @DisplayName("Should emit PaymentCreatedEvent on creation")
        void shouldEmitCreatedEvent() {
            var events = payment.getDomainEvents();
            assertEquals(1, events.size());
            assertInstanceOf(PaymentCreatedEvent.class, events.getFirst());
            var event = (PaymentCreatedEvent) events.getFirst();
            assertEquals(payment.getId(), event.getPaymentId());
            assertEquals(payment.getPaymentReference(), event.getPaymentReference());
            assertEquals(ORDER_ID, event.getOrderId());
            assertEquals(USER_ID, event.getUserId());
            assertEquals(AMOUNT, event.getAmount());
            assertEquals(CURRENCY, event.getCurrency());
        }

        @Test
        @DisplayName("Should reject zero amount")
        void shouldRejectZeroAmount() {
            assertThrows(PaymentDomainException.class, () ->
                Payment.create(PaymentId.generate(), PaymentReference.generate(),
                    ORDER_ID, USER_ID, BigDecimal.ZERO, CURRENCY));
        }

        @Test
        @DisplayName("Should reject negative amount")
        void shouldRejectNegativeAmount() {
            assertThrows(PaymentDomainException.class, () ->
                Payment.create(PaymentId.generate(), PaymentReference.generate(),
                    ORDER_ID, USER_ID, new BigDecimal("-100.00"), CURRENCY));
        }
    }

    @Nested
    @DisplayName("Payment Lifecycle")
    class PaymentLifecycle {

        @BeforeEach
        void setUp() {
            payment.initiatePayment(METHOD, PROVIDER);
        }

        @Test
        @DisplayName("Should transition from CREATED to PENDING on initiate")
        void shouldInitiatePayment() {
            assertEquals(PaymentStatus.PENDING, payment.getStatus());
            assertEquals(METHOD, payment.getPaymentMethod());
            assertEquals(PROVIDER, payment.getPaymentProvider());
        }

        @Test
        @DisplayName("Should transition from PENDING to PROCESSING")
        void shouldMarkProcessing() {
            payment.markProcessing();
            assertEquals(PaymentStatus.PROCESSING, payment.getStatus());
        }

        @Test
        @DisplayName("Should transition from PROCESSING to AUTHORIZED")
        void shouldAuthorize() {
            payment.markProcessing();
            payment.authorize("TXN-001", "GW-001");
            assertEquals(PaymentStatus.AUTHORIZED, payment.getStatus());
            assertEquals("TXN-001", payment.getTransactionId());
            assertEquals("GW-001", payment.getGatewayReference());
            assertTrue(payment.getDomainEvents().stream().anyMatch(e -> e instanceof PaymentAuthorizedEvent));
        }

        @Test
        @DisplayName("Should transition from AUTHORIZED to SUCCESS")
        void shouldMarkSuccess() {
            payment.markProcessing();
            payment.authorize("TXN-001", "GW-001");
            payment.markSuccess("TXN-001", "GW-001");
            assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
            assertNotNull(payment.getPaidAt());
            assertTrue(payment.getDomainEvents().stream().anyMatch(e -> e instanceof PaymentSucceededEvent));
        }

        @Test
        @DisplayName("Should transition from PROCESSING to SUCCESS directly")
        void shouldMarkSuccessFromProcessing() {
            payment.markProcessing();
            payment.markSuccess("TXN-001", "GW-001");
            assertEquals(PaymentStatus.SUCCESS, payment.getStatus());
            assertNotNull(payment.getPaidAt());
        }

        @Test
        @DisplayName("Should transition to FAILED from any non-terminal state")
        void shouldMarkFailed() {
            payment.markFailed("Insufficient funds");
            assertEquals(PaymentStatus.FAILED, payment.getStatus());
            assertEquals("Insufficient funds", payment.getFailureReason());
            assertTrue(payment.getDomainEvents().stream().anyMatch(e -> e instanceof PaymentFailedEvent));
        }

        @Test
        @DisplayName("Should cancel from PENDING")
        void shouldCancel() {
            payment.cancel();
            assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
            assertTrue(payment.getDomainEvents().stream().anyMatch(e -> e instanceof PaymentCancelledEvent));
        }

        @Test
        @DisplayName("Should not cancel successful payment")
        void shouldNotCancelSuccess() {
            payment.markProcessing();
            payment.markSuccess("TXN-001", "GW-001");
            assertThrows(PaymentDomainException.class, payment::cancel);
        }

        @Test
        @DisplayName("Partial refunds accumulate until the payment is fully refunded")
        void shouldTrackPartialAndFullRefunds() {
            payment.markProcessing();
            payment.markSuccess("TXN-001", "GW-001");

            payment.applyRefund(new BigDecimal("400.00"));
            assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
            assertEquals(0, new BigDecimal("600.00").compareTo(payment.getRefundableAmount()));

            payment.applyRefund(new BigDecimal("600.00"));
            assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
            assertTrue(payment.isFullyRefunded());
            assertEquals(0, BigDecimal.ZERO.compareTo(payment.getRefundableAmount()));
            assertEquals(2, payment.getDomainEvents().stream().filter(e -> e instanceof PaymentRefundedEvent).count());
        }

        @Test
        @DisplayName("Cannot refund more than was captured")
        void shouldRejectRefundAboveCapturedAmount() {
            payment.markProcessing();
            payment.markSuccess("TXN-001", "GW-001");
            payment.applyRefund(new BigDecimal("900.00"));

            var ex = assertThrows(PaymentDomainException.class, () -> payment.applyRefund(new BigDecimal("100.01")));
            assertEquals(PaymentErrorCode.INVALID_REFUND_AMOUNT, ex.getErrorCode());
            assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
        }

        @Test
        @DisplayName("Cannot refund an uncaptured payment")
        void shouldNotRefundUncapturedPayment() {
            var ex = assertThrows(PaymentDomainException.class, () -> payment.applyRefund(new BigDecimal("1.00")));
            assertEquals(PaymentErrorCode.CANNOT_REFUND_NON_SUCCESS_PAYMENT, ex.getErrorCode());
        }

        @Test
        @DisplayName("Capture claim moves AUTHORIZED to PROCESSING and blocks cancellation")
        void shouldClaimCaptureAndBlockCancellation() {
            payment.markProcessing();
            payment.authorize("TXN-001", "GW-001");

            payment.beginCapture();

            assertEquals(PaymentStatus.PROCESSING, payment.getStatus());
            assertTrue(payment.isCaptureInProgress());
            var ex = assertThrows(PaymentDomainException.class, payment::cancel);
            assertEquals(PaymentErrorCode.PAYMENT_IN_PROGRESS, ex.getErrorCode());
        }

        @Test
        @DisplayName("Capture claim is re-entrant for retries and rejected for terminal payments")
        void shouldAllowRetryingClaimButNotOnTerminalPayment() {
            payment.markProcessing();
            payment.authorize("TXN-001", "GW-001");
            payment.beginCapture();
            assertDoesNotThrow(payment::beginCapture);

            payment.markFailed("declined");
            assertThrows(PaymentDomainException.class, payment::beginCapture);
        }

        @Test
        @DisplayName("An authorized payment can be voided")
        void shouldVoidAuthorizedPayment() {
            payment.markProcessing();
            payment.authorize("TXN-001", "GW-001");
            payment.cancel();
            assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        }

        @Test
        @DisplayName("A captured payment cannot be marked failed")
        void shouldNotFailCapturedPayment() {
            payment.markProcessing();
            payment.markSuccess("TXN-001", "GW-001");
            assertThrows(PaymentDomainException.class, () -> payment.markFailed("late decline"));
        }

        @Test
        @DisplayName("Should reject invalid status transitions")
        void shouldRejectInvalidTransition() {
            payment.markProcessing();
            var ex = assertThrows(PaymentDomainException.class, () ->
                payment.initiatePayment(METHOD, PROVIDER));
            assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, ex.getErrorCode());
        }

        @Test
        @DisplayName("Should not authorize from PENDING (requires PROCESSING)")
        void shouldNotAuthorizeFromPending() {
            var ex = assertThrows(PaymentDomainException.class, () ->
                payment.authorize("TXN", "GW"));
            assertEquals(PaymentErrorCode.INVALID_STATUS_TRANSITION, ex.getErrorCode());
        }
    }
}
