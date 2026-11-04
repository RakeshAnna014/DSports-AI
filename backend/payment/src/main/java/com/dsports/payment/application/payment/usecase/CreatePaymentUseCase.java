package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.CreatePaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.result.PaymentResult;
import com.dsports.payment.application.payment.result.PaymentResultMapper;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Money;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Authorises a payment for an order awaiting payment.
 * <ul>
 *   <li>Idempotent per order: while an attempt is still open (authorised or capture in progress) it is returned
 *       instead of authorising again. A failed or cancelled attempt allows a new one.</li>
 *   <li>The amount and currency must match the order; the client cannot choose what it pays.</li>
 *   <li>A concurrent duplicate loses on the {@code uq_payments_order_open} index; its gateway authorisation is
 *       voided through the outbox.</li>
 * </ul>
 */
public class CreatePaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final PaymentRepository paymentRepository;
    private final OrderPaymentPort orderPaymentPort;
    private final PaymentGateway paymentGateway;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final OutboxWriter outboxWriter;
    private final Duration gatewayTimeout;

    public CreatePaymentUseCase(PaymentRepository paymentRepository,
                                 OrderPaymentPort orderPaymentPort,
                                 PaymentGateway paymentGateway,
                                 EventPublisher eventPublisher,
                                 TransactionRunner transactionRunner,
                                 OutboxWriter outboxWriter,
                                 Duration gatewayTimeout) {
        this.paymentRepository = paymentRepository;
        this.orderPaymentPort = orderPaymentPort;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
        this.transactionRunner = transactionRunner;
        this.outboxWriter = outboxWriter;
        this.gatewayTimeout = gatewayTimeout;
    }

    public Mono<PaymentResult> execute(CreatePaymentCommand command) {
        var paymentMethod = parsePaymentMethod(command.paymentMethod());
        var paymentProvider = parsePaymentProvider(command.paymentProvider());

        return orderPaymentPort.getOrderData(command.orderId(), command.userId())
            .flatMap(orderData -> {
                if (orderData.paid()) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.ORDER_ALREADY_PAID,
                        "Order " + orderData.orderNumber() + " is already paid"));
                }
                if (!orderData.awaitingPayment()) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.ORDER_NOT_PAYABLE,
                        "Order " + orderData.orderNumber() + " cannot be paid in status " + orderData.status()));
                }
                if (orderData.grandTotal().compareTo(command.amount()) != 0) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.AMOUNT_MISMATCH,
                        "Payment amount " + command.amount() + " does not match order total " + orderData.grandTotal()));
                }
                if (!orderData.currency().equals(command.currency())) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.AMOUNT_MISMATCH,
                        "Payment currency " + command.currency() + " does not match order currency " + orderData.currency()));
                }
                return paymentRepository.findByOrderId(command.orderId())
                    .filter(existing -> existing.getStatus().isOpen() || existing.getStatus().isCaptured())
                    .next()
                    .flatMap(existing -> existing.getStatus().isCaptured()
                        ? Mono.<Payment>error(new PaymentDomainException(PaymentErrorCode.ORDER_ALREADY_PAID,
                            "Order " + orderData.orderNumber() + " is already paid"))
                        : Mono.just(existing))
                    .doOnNext(existing -> log.info("Returning open payment {} for order {} (idempotent replay)",
                        existing.getPaymentReference().value(), orderData.orderNumber()))
                    .switchIfEmpty(Mono.defer(() -> authorize(command, paymentMethod, paymentProvider)));
            })
            .map(PaymentResultMapper::toResult);
    }

    private Mono<Payment> authorize(CreatePaymentCommand command, PaymentMethod method, PaymentProvider provider) {
        var payment = Payment.create(PaymentId.generate(), PaymentReference.generate(),
            command.orderId(), command.userId(), command.amount(), command.currency());
        payment.initiatePayment(method, provider);
        payment.markProcessing();

        var request = new PaymentGateway.InitiatePaymentRequest(payment.getPaymentReference(),
            new Money(command.amount(), command.currency()), "Payment for order " + command.orderId());

        return paymentGateway.initiatePayment(request)
            .timeout(gatewayTimeout)
            .map(result -> {
                if (result.success()) {
                    payment.authorize(result.transactionId(), result.gatewayReference());
                } else {
                    payment.markFailed(result.message());
                }
                return payment;
            })
            .onErrorResume(e -> {
                var reason = e instanceof TimeoutException ? "Payment gateway timed out" : "Payment gateway unavailable";
                log.error("Authorization failed for payment {}: {}", payment.getPaymentReference().value(), e.toString());
                payment.markFailed(reason);
                return Mono.just(payment);
            })
            .flatMap(this::persist)
            .doOnNext(saved -> {
                saved.getDomainEvents().forEach(eventPublisher::publish);
                saved.clearDomainEvents();
                log.info("Payment {} for order {} is {}", saved.getPaymentReference().value(),
                    saved.getOrderId(), saved.getStatus());
            });
    }

    private Mono<Payment> persist(Payment payment) {
        return transactionRunner.inTransaction(() -> paymentRepository.save(payment)
                .then(payment.getStatus() == PaymentStatus.FAILED
                    ? outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_PAYMENT_FAILED,
                        payment.getId().value(), Map.of(
                            "userId", payment.getUserId().toString(),
                            "orderId", payment.getOrderId().toString(),
                            "reason", payment.getFailureReason())))
                    : Mono.empty())
                .thenReturn(payment))
            .onErrorResume(PaymentDomainException.class, e -> {
                if (e.getErrorCode() != PaymentErrorCode.DUPLICATE_PAYMENT || payment.getGatewayReference() == null) {
                    return Mono.error(e);
                }
                log.warn("Concurrent payment for order {} detected; voiding authorization {}",
                    payment.getOrderId(), payment.getPaymentReference().value());
                return transactionRunner.inTransaction(() -> outboxWriter.enqueue(OutboxMessage.of(
                        OutboxMessageTypes.VOID_PAYMENT_AUTHORIZATION, payment.getPaymentReference().value(),
                        Map.of("gatewayReference", payment.getGatewayReference()))))
                    .then(Mono.error(e));
            });
    }

    private PaymentMethod parsePaymentMethod(String method) {
        try {
            return PaymentMethod.valueOf(method.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PaymentDomainException(PaymentErrorCode.VALIDATION_ERROR,
                "Invalid payment method: " + method);
        }
    }

    private PaymentProvider parsePaymentProvider(String provider) {
        try {
            return PaymentProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new PaymentDomainException(PaymentErrorCode.VALIDATION_ERROR,
                "Invalid payment provider: " + provider);
        }
    }
}
