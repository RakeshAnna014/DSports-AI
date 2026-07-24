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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

public class CreatePaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreatePaymentUseCase.class);

    private final PaymentRepository paymentRepository;
    private final OrderPaymentPort orderPaymentPort;
    private final PaymentGateway paymentGateway;
    private final EventPublisher eventPublisher;

    public CreatePaymentUseCase(PaymentRepository paymentRepository,
                                 OrderPaymentPort orderPaymentPort,
                                 PaymentGateway paymentGateway,
                                 EventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.orderPaymentPort = orderPaymentPort;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
    }

    public Mono<PaymentResult> execute(CreatePaymentCommand command) {
        var paymentMethod = parsePaymentMethod(command.paymentMethod());
        var paymentProvider = parsePaymentProvider(command.paymentProvider());

        return orderPaymentPort.getOrderData(command.orderId(), command.userId())
            .flatMap(orderData -> {
                if (orderData.alreadyPaid()) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.ORDER_ALREADY_PAID,
                        "Order " + command.orderId() + " is already paid"));
                }
                if (orderData.grandTotal().compareTo(command.amount()) != 0) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.AMOUNT_MISMATCH,
                        "Payment amount " + command.amount() + " does not match order total " + orderData.grandTotal()));
                }
                if (!orderData.currency().equals(command.currency())) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.AMOUNT_MISMATCH,
                        "Payment currency " + command.currency() + " does not match order currency " + orderData.currency()));
                }

                var paymentId = PaymentId.generate();
                var paymentReference = PaymentReference.generate();
                var payment = Payment.create(paymentId, paymentReference,
                    command.orderId(), command.userId(),
                    command.amount(), command.currency());

                payment.initiatePayment(paymentMethod, paymentProvider);

                var money = new Money(command.amount(), command.currency());
                var gatewayRequest = new PaymentGateway.CreatePaymentRequest(
                    paymentReference, money, "Payment for Order " + command.orderId());

                return paymentGateway.createPayment(gatewayRequest)
                    .flatMap(gatewayResult -> {
                        if (gatewayResult.success()) {
                            payment.authorize(gatewayResult.transactionId(), gatewayResult.gatewayReference());
                        } else {
                            payment.markFailed(gatewayResult.message());
                        }
                        return paymentRepository.save(payment)
                            .thenReturn(payment);
                    })
                    .onErrorResume(e -> {
                        payment.markFailed(e.getMessage());
                        return paymentRepository.save(payment)
                            .thenReturn(payment);
                    });
            })
            .map(payment -> {
                payment.getDomainEvents().forEach(eventPublisher::publish);
                payment.clearDomainEvents();
                log.info("Payment created: {} for order {} by user {}",
                    payment.getPaymentReference(), payment.getOrderId(), payment.getUserId());
                return PaymentResultMapper.toResult(payment);
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
