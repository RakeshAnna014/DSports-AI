package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.CancelPaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.result.PaymentResult;
import com.dsports.payment.application.payment.result.PaymentResultMapper;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.PaymentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class CancelPaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(CancelPaymentUseCase.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final EventPublisher eventPublisher;

    public CancelPaymentUseCase(PaymentRepository paymentRepository,
                                 PaymentGateway paymentGateway,
                                 EventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
    }

    public Mono<PaymentResult> execute(CancelPaymentCommand command) {
        return paymentRepository.findById(PaymentId.fromUUID(command.paymentId()))
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                "Payment not found: " + command.paymentId())))
            .flatMap(payment -> {
                if (!payment.getUserId().equals(command.userId())) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER,
                        "Payment does not belong to user: " + command.userId()));
                }
                if (payment.getGatewayReference() != null) {
                    return paymentGateway.cancelPayment(payment.getGatewayReference())
                        .flatMap(gatewayResult -> {
                            if (!gatewayResult.success()) {
                                log.warn("Gateway cancel returned failure for payment {}: {}",
                                    payment.getPaymentReference(), gatewayResult.message());
                            }
                            payment.cancel();
                            return paymentRepository.save(payment).thenReturn(payment);
                        });
                }
                payment.cancel();
                return paymentRepository.save(payment).thenReturn(payment);
            })
            .map(payment -> {
                payment.getDomainEvents().forEach(eventPublisher::publish);
                payment.clearDomainEvents();
                log.info("Payment cancelled: {} for order {}",
                    payment.getPaymentReference(), payment.getOrderId());
                return PaymentResultMapper.toResult(payment);
            });
    }
}
