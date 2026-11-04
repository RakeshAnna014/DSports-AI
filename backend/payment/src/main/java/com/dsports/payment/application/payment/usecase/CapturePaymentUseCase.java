package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.CapturePaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentCompletionPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.result.PaymentResult;
import com.dsports.payment.application.payment.result.PaymentResultMapper;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentStatus;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Captures an authorised payment and completes the order.
 * <ol>
 *   <li><b>Claim</b> (transaction): the order must still await payment; the payment moves AUTHORIZED → PROCESSING.
 *       A cancellation racing with the capture now fails instead of voiding a payment that is being captured.</li>
 *   <li><b>Gateway capture</b> (no transaction held): idempotent per authorisation, with a timeout.</li>
 *   <li><b>Complete</b> (transaction): payment SUCCESS, order CONFIRMED, stock fulfilled, cart COMPLETED and
 *       follow-up work recorded in the outbox — all or nothing.</li>
 * </ol>
 * Retrying is always safe: a captured payment is returned unchanged, and a payment left PROCESSING by a timeout or a
 * failed completion is captured again (the gateway returns the original capture) and completed.
 */
public class CapturePaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(CapturePaymentUseCase.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final OrderPaymentPort orderPaymentPort;
    private final PaymentCompletionPort paymentCompletionPort;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final OutboxWriter outboxWriter;
    private final Duration gatewayTimeout;

    public CapturePaymentUseCase(PaymentRepository paymentRepository,
                                  PaymentGateway paymentGateway,
                                  OrderPaymentPort orderPaymentPort,
                                  PaymentCompletionPort paymentCompletionPort,
                                  EventPublisher eventPublisher,
                                  TransactionRunner transactionRunner,
                                  OutboxWriter outboxWriter,
                                  Duration gatewayTimeout) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.orderPaymentPort = orderPaymentPort;
        this.paymentCompletionPort = paymentCompletionPort;
        this.eventPublisher = eventPublisher;
        this.transactionRunner = transactionRunner;
        this.outboxWriter = outboxWriter;
        this.gatewayTimeout = gatewayTimeout;
    }

    public Mono<PaymentResult> execute(CapturePaymentCommand command) {
        return Mono.defer(() -> capture(command))
            .retryWhen(Retry.max(3).filter(CapturePaymentUseCase::isConcurrentModification))
            .map(PaymentResultMapper::toResult);
    }

    private Mono<Payment> capture(CapturePaymentCommand command) {
        return loadOwnedPayment(command)
            .flatMap(payment -> {
                if (payment.getStatus().isCaptured()) {
                    log.info("Payment {} already captured; returning current state", payment.getPaymentReference().value());
                    return Mono.just(payment);
                }
                if (payment.getStatus() != PaymentStatus.AUTHORIZED && !payment.isCaptureInProgress()) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.INVALID_STATUS_TRANSITION,
                        "Payment " + payment.getPaymentReference().value() + " cannot be captured in status " + payment.getStatus()));
                }
                return claim(payment, command)
                    .then(Mono.defer(() -> callGateway(payment)))
                    .flatMap(result -> result.success()
                        ? complete(payment, result)
                        : decline(payment, result.message()));
            });
    }

    private Mono<Payment> loadOwnedPayment(CapturePaymentCommand command) {
        return paymentRepository.findById(PaymentId.fromUUID(command.paymentId()))
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                "Payment not found: " + command.paymentId())))
            .flatMap(payment -> payment.getUserId().equals(command.userId())
                ? Mono.just(payment)
                : Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER,
                    "Payment does not belong to the current user")));
    }

    private Mono<Void> claim(Payment payment, CapturePaymentCommand command) {
        return transactionRunner.inTransaction(() -> orderPaymentPort.getOrderData(payment.getOrderId(), command.userId())
            .flatMap(order -> {
                if (!order.awaitingPayment()) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.ORDER_NOT_PAYABLE,
                        "Order " + order.orderNumber() + " cannot be paid in status " + order.status()));
                }
                payment.beginCapture();
                return paymentRepository.save(payment);
            }));
    }

    private Mono<PaymentGateway.GatewayResult> callGateway(Payment payment) {
        return paymentGateway.capturePayment(payment.getGatewayReference())
            .timeout(gatewayTimeout)
            .onErrorMap(TimeoutException.class, e -> new PaymentDomainException(PaymentErrorCode.GATEWAY_TIMEOUT,
                "The payment gateway did not respond in time. The capture can be retried safely."))
            .onErrorMap(e -> !(e instanceof PaymentDomainException), e -> {
                log.error("Capture call failed for payment {}: {}", payment.getPaymentReference().value(), e.toString());
                return new PaymentDomainException(PaymentErrorCode.GATEWAY_ERROR,
                    "The payment gateway is unavailable. The capture can be retried safely.");
            });
    }

    private Mono<Payment> complete(Payment payment, PaymentGateway.GatewayResult result) {
        return transactionRunner.inTransaction(() -> {
                payment.markSuccess(result.transactionId(), result.gatewayReference());
                return paymentRepository.save(payment)
                    .then(Mono.defer(() -> paymentCompletionPort.completeOrder(payment.getOrderId())))
                    .then(Mono.defer(() -> outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_PAYMENT_SUCCEEDED,
                        payment.getId().value(), Map.of(
                            "userId", payment.getUserId().toString(),
                            "orderId", payment.getOrderId().toString(),
                            "amount", payment.getAmount().toPlainString(),
                            "currency", payment.getCurrency(),
                            "paymentReference", payment.getPaymentReference().value())))))
                    .thenReturn(payment);
            })
            .doOnNext(this::publishEvents)
            .doOnNext(p -> log.info("Payment {} captured; order {} confirmed", p.getPaymentReference().value(), p.getOrderId()))
            .doOnError(e -> log.error("Payment {} was captured by the gateway but completing order {} failed: {}. "
                + "It stays PROCESSING and the capture can be retried.",
                payment.getPaymentReference().value(), payment.getOrderId(), e.toString()));
    }

    private Mono<Payment> decline(Payment payment, String reason) {
        return transactionRunner.inTransaction(() -> {
                payment.markFailed(reason == null ? "Capture declined" : reason);
                return paymentRepository.save(payment)
                    .then(Mono.defer(() -> outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_PAYMENT_FAILED,
                        payment.getId().value(), Map.of(
                            "userId", payment.getUserId().toString(),
                            "orderId", payment.getOrderId().toString(),
                            "reason", payment.getFailureReason())))))
                    .thenReturn(payment);
            })
            .doOnNext(this::publishEvents)
            .doOnNext(p -> log.warn("Capture declined for payment {}: {}", p.getPaymentReference().value(), reason));
    }

    private void publishEvents(Payment payment) {
        payment.getDomainEvents().forEach(eventPublisher::publish);
        payment.clearDomainEvents();
    }

    private static boolean isConcurrentModification(Throwable e) {
        return e instanceof PaymentDomainException pde && pde.getErrorCode() == PaymentErrorCode.OPTIMISTIC_LOCKING_CONFLICT;
    }
}
