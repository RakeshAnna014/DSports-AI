package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.query.GetPaymentQuery;
import com.dsports.payment.application.payment.result.PaymentResult;
import com.dsports.payment.application.payment.result.PaymentResultMapper;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.PaymentId;
import reactor.core.publisher.Mono;

public class GetPaymentUseCase {
    private final PaymentRepository paymentRepository;

    public GetPaymentUseCase(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public Mono<PaymentResult> execute(GetPaymentQuery query) {
        return paymentRepository.findById(PaymentId.fromUUID(query.paymentId()))
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                "Payment not found: " + query.paymentId())))
            .flatMap(payment -> {
                if (!payment.getUserId().equals(query.userId())) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER,
                        "Payment does not belong to user: " + query.userId()));
                }
                return Mono.just(PaymentResultMapper.toResult(payment));
            });
    }
}
