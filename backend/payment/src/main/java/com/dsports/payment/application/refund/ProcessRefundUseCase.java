package com.dsports.payment.application.refund;

import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.refund.port.RefundRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Money;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.refund.Refund;
import com.dsports.payment.domain.refund.RefundStatus;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Pays out an approved refund (administrators only).
 * <ol>
 *   <li><b>Claim</b> (transaction): REQUESTED/FAILED → PROCESSING. The version check stops two operators from
 *       processing the same refund at once.</li>
 *   <li><b>Gateway refund</b>, idempotent on the refund ID, with a timeout.</li>
 *   <li><b>Outcome</b> (transaction): on success the refund is COMPLETED and only then is the payment's refunded
 *       amount increased (and the order marked REFUNDED when fully refunded); a decline marks the refund FAILED and
 *       leaves the payment untouched; an unknown outcome (timeout) leaves it PROCESSING for a safe retry.</li>
 * </ol>
 */
public class ProcessRefundUseCase {
    private static final Logger log = LoggerFactory.getLogger(ProcessRefundUseCase.class);

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final OrderPaymentPort orderPaymentPort;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final OutboxWriter outboxWriter;
    private final Duration gatewayTimeout;

    public ProcessRefundUseCase(RefundRepository refundRepository, PaymentRepository paymentRepository,
                                PaymentGateway paymentGateway, OrderPaymentPort orderPaymentPort,
                                EventPublisher eventPublisher, TransactionRunner transactionRunner,
                                OutboxWriter outboxWriter, Duration gatewayTimeout) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.orderPaymentPort = orderPaymentPort;
        this.eventPublisher = eventPublisher;
        this.transactionRunner = transactionRunner;
        this.outboxWriter = outboxWriter;
        this.gatewayTimeout = gatewayTimeout;
    }

    public Mono<Refund> execute(UUID refundId) {
        return transactionRunner.inTransaction(() -> claim(refundId))
            .flatMap(claimed -> callGateway(claimed.refund(), claimed.payment())
                .flatMap(result -> result.success()
                    ? complete(claimed.refund().getId(), result.transactionId())
                    : fail(claimed.refund().getId(), result.message())));
    }

    private record Claimed(Refund refund, Payment payment) {}

    private Mono<Claimed> claim(UUID refundId) {
        return refundRepository.findById(refundId)
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.REFUND_NOT_FOUND,
                "Refund not found: " + refundId)))
            .flatMap(refund -> {
                if (refund.getStatus() == RefundStatus.COMPLETED || refund.getStatus() == RefundStatus.REJECTED) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.INVALID_REFUND_STATUS,
                        "Refund " + refundId + " is already " + refund.getStatus()));
                }
                return paymentRepository.findById(refund.getPaymentId())
                    .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                        "Payment not found: " + refund.getPaymentId().value())))
                    .flatMap(payment -> {
                        if (refund.getAmount().compareTo(payment.getRefundableAmount()) > 0) {
                            return Mono.error(new PaymentDomainException(PaymentErrorCode.INVALID_REFUND_AMOUNT,
                                "Refund amount " + refund.getAmount() + " exceeds the refundable amount "
                                    + payment.getRefundableAmount()));
                        }
                        refund.startProcessing();
                        return refundRepository.save(refund).thenReturn(new Claimed(refund, payment));
                    });
            });
    }

    private Mono<PaymentGateway.GatewayResult> callGateway(Refund refund, Payment payment) {
        return paymentGateway.refundPayment(payment.getGatewayReference(),
                new Money(refund.getAmount(), refund.getCurrency()), refund.getId().toString())
            .timeout(gatewayTimeout)
            .onErrorMap(TimeoutException.class, e -> new PaymentDomainException(PaymentErrorCode.GATEWAY_TIMEOUT,
                "The payment gateway did not confirm refund " + refund.getId()
                    + " in time. It stays PROCESSING and can be retried safely."))
            .onErrorMap(e -> !(e instanceof PaymentDomainException), e -> {
                log.error("Refund call failed for refund {}: {}", refund.getId(), e.toString());
                return new PaymentDomainException(PaymentErrorCode.GATEWAY_ERROR,
                    "The payment gateway is unavailable. Refund " + refund.getId() + " can be retried safely.");
            });
    }

    private Mono<Refund> complete(UUID refundId, String gatewayReference) {
        return transactionRunner.inTransaction(() -> refundRepository.findById(refundId)
                .zipWhen(refund -> paymentRepository.findById(refund.getPaymentId()))
                .flatMap(pair -> {
                    var refund = pair.getT1();
                    var payment = pair.getT2();
                    refund.complete(gatewayReference);
                    payment.applyRefund(refund.getAmount());
                    return refundRepository.save(refund)
                        .then(Mono.defer(() -> paymentRepository.save(payment)))
                        .then(Mono.defer(() -> payment.isFullyRefunded()
                            ? orderPaymentPort.markOrderRefunded(payment.getOrderId())
                            : Mono.<Void>empty()))
                        .then(Mono.defer(() -> outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_REFUND_COMPLETED,
                            refund.getId(), Map.of(
                                "userId", refund.getCustomerId().toString(),
                                "orderId", refund.getOrderId().toString(),
                                "amount", refund.getAmount().toPlainString(),
                                "currency", refund.getCurrency())))))
                        .then(Mono.fromRunnable(() -> {
                            payment.getDomainEvents().forEach(eventPublisher::publish);
                            payment.clearDomainEvents();
                        }))
                        .thenReturn(refund);
                }))
            .doOnNext(r -> log.info("Refund {} completed: {} {}", r.getId(), r.getAmount(), r.getCurrency()));
    }

    private Mono<Refund> fail(UUID refundId, String reason) {
        return transactionRunner.inTransaction(() -> refundRepository.findById(refundId)
                .flatMap(refund -> {
                    refund.fail(reason == null ? "Refund declined by the payment gateway" : reason);
                    return refundRepository.save(refund).thenReturn(refund);
                }))
            .doOnNext(r -> log.warn("Refund {} failed at the gateway: {}", r.getId(), r.getFailureReason()));
    }
}
