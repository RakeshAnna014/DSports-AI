package com.dsports.ground.application.port;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/** What the booking module needs from payments. Connected to the payment module in the composition root; the booking module never imports it. */
public interface BookingPaymentPort {

    /**
     * Cancels the booking's payment attempts that were started but not captured, so an expired or cancelled hold cannot be charged later.
     * Fails with {@code PAYMENT_IN_PROGRESS} when a capture is in flight: that booking must be left alone and retried later.
     */
    Mono<Void> voidOpenPayments(UUID bookingId);

    /** Whether money was captured for the booking (a payment in a captured state exists). */
    Mono<Boolean> hasCapturedPayment(UUID bookingId);

    /**
     * Queues an automatic, full refund of a captured payment whose booking could not be honoured. It runs in the caller's transaction and
     * only records the work: the refund itself is paid out afterwards through the outbox.
     */
    Mono<Void> refundCapturedPayment(UUID paymentId, String reason);

    /**
     * Files a refund request of {@code amount} for a captured payment on behalf of its customer. Nothing is paid out until an administrator
     * approves it. Idempotent on {@code idempotencyKey}.
     */
    Mono<Void> requestRefund(UUID paymentId, UUID requesterId, BigDecimal amount, String reason, String idempotencyKey);
}
