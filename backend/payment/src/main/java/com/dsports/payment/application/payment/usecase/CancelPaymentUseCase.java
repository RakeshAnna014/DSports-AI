package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.CancelPaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.result.PaymentResult;
import com.dsports.payment.application.payment.result.PaymentResultMapper;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Abandons an uncaptured payment attempt. The payment is cancelled locally and the gateway authorisation is voided
 * through the outbox, so a failing void is retried rather than ignored.
 */
public class CancelPaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(CancelPaymentUseCase.class);

    private final PaymentRepository paymentRepository;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final OutboxWriter outboxWriter;

    public CancelPaymentUseCase(PaymentRepository paymentRepository,
                                 EventPublisher eventPublisher,
                                 TransactionRunner transactionRunner,
                                 OutboxWriter outboxWriter) {
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
        this.transactionRunner = transactionRunner;
        this.outboxWriter = outboxWriter;
    }

    public Mono<PaymentResult> execute(CancelPaymentCommand command) {
        return transactionRunner.inTransaction(() -> paymentRepository.findById(PaymentId.fromUUID(command.paymentId()))
                .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                    "Payment not found: " + command.paymentId())))
                .flatMap(payment -> {
                    if (!payment.getUserId().equals(command.userId())) {
                        return Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER,
                            "Payment does not belong to the current user"));
                    }
                    return voidPayment(payment, paymentRepository, outboxWriter);
                }))
            .map(payment -> {
                payment.getDomainEvents().forEach(eventPublisher::publish);
                payment.clearDomainEvents();
                log.info("Payment {} cancelled for order {}", payment.getPaymentReference().value(), payment.getOrderId());
                return PaymentResultMapper.toResult(payment);
            });
    }

    /** Cancels the payment and schedules the gateway void. Must run inside a transaction. */
    public static Mono<Payment> voidPayment(Payment payment, PaymentRepository repository, OutboxWriter outbox) {
        var gatewayReference = payment.getGatewayReference();
        payment.cancel();
        return repository.save(payment)
            .then(gatewayReference == null
                ? Mono.empty()
                : outbox.enqueue(OutboxMessage.of(OutboxMessageTypes.VOID_PAYMENT_AUTHORIZATION,
                    payment.getPaymentReference().value(), Map.of("gatewayReference", gatewayReference))))
            .thenReturn(payment);
    }
}
