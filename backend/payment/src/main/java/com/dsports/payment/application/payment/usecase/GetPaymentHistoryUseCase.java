package com.dsports.payment.application.payment.usecase;

import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.query.GetPaymentHistoryQuery;
import com.dsports.payment.application.payment.result.PaymentSummaryResult;
import com.dsports.payment.application.payment.result.PaymentResultMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class GetPaymentHistoryUseCase {
    private final PaymentRepository paymentRepository;

    public GetPaymentHistoryUseCase(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public Flux<PaymentSummaryResult> execute(GetPaymentHistoryQuery query) {
        return paymentRepository.findByUserId(query.userId())
            .map(PaymentResultMapper::toSummary);
    }
}
