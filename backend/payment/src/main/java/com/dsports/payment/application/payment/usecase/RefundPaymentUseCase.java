package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.command.RefundPaymentCommand;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.result.PaymentResult;
import com.dsports.payment.application.payment.result.PaymentResultMapper;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Money;
import com.dsports.payment.domain.payment.model.PaymentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

public class RefundPaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(RefundPaymentUseCase.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final OrderPaymentPort orderPaymentPort;
    private final EventPublisher eventPublisher;

    public RefundPaymentUseCase(PaymentRepository paymentRepository,
                                 PaymentGateway paymentGateway,
                                 OrderPaymentPort orderPaymentPort,
                                 EventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.orderPaymentPort = orderPaymentPort;
        this.eventPublisher = eventPublisher;
    }

    public Mono<PaymentResult> execute(RefundPaymentCommand command) {
        return paymentRepository.findById(PaymentId.fromUUID(command.paymentId()))
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                "Payment not found: " + command.paymentId())))
            .flatMap(payment -> {
                if (!payment.getUserId().equals(command.userId())) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER,
                        "Payment does not belong to user: " + command.userId()));
                }
                payment.refund();
                var money = new Money(payment.getAmount(), payment.getCurrency());
                return paymentGateway.refundPayment(payment.getGatewayReference(), money)
                    .flatMap(gatewayResult -> {
                        if (!gatewayResult.success()) {
                            log.warn("Gateway refund returned failure for payment {}: {}",
                                payment.getPaymentReference(), gatewayResult.message());
                        }
                        return paymentRepository.save(payment).thenReturn(payment);
                    });
            })
            .map(payment -> {
                payment.getDomainEvents().forEach(eventPublisher::publish);
                payment.clearDomainEvents();
                log.info("Payment refunded: {} for order {}",
                    payment.getPaymentReference(), payment.getOrderId());
                return PaymentResultMapper.toResult(payment);
            });
    }
}
