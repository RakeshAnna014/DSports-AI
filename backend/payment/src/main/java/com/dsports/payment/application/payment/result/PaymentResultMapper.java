package com.dsports.payment.application.payment.result;

import com.dsports.payment.domain.payment.model.Payment;

import java.util.List;

public final class PaymentResultMapper {
    private PaymentResultMapper() {}

    public static PaymentResult toResult(Payment payment) {
        return new PaymentResult(
            payment.getId().value(),
            payment.getPaymentReference().value(),
            payment.getOrderId(),
            payment.getUserId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null,
            payment.getPaymentProvider() != null ? payment.getPaymentProvider().name() : null,
            payment.getTransactionId(),
            payment.getGatewayReference(),
            payment.getStatus().name(),
            payment.getFailureReason(),
            payment.getPaidAt(),
            payment.getVersion(),
            payment.getCreatedAt(),
            payment.getUpdatedAt()
        );
    }

    public static PaymentSummaryResult toSummary(Payment payment) {
        return new PaymentSummaryResult(
            payment.getId().value(),
            payment.getPaymentReference().value(),
            payment.getOrderId(),
            payment.getAmount(),
            payment.getCurrency(),
            payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null,
            payment.getStatus().name(),
            payment.getPaidAt(),
            payment.getCreatedAt()
        );
    }

    public static List<PaymentSummaryResult> toSummaryList(List<Payment> payments) {
        return payments.stream()
            .map(PaymentResultMapper::toSummary)
            .toList();
    }
}
