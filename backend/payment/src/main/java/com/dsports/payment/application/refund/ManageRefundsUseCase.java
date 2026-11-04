package com.dsports.payment.application.refund;

import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.refund.port.RefundRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.refund.Refund;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Read access to refunds and rejection of pending requests. */
public class ManageRefundsUseCase {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;

    public ManageRefundsUseCase(RefundRepository refundRepository, PaymentRepository paymentRepository) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
    }

    /** Refunds of one payment; customers only see refunds of their own payments. */
    public Flux<Refund> forPayment(UUID paymentId, UUID requesterId, boolean admin) {
        var id = PaymentId.fromUUID(paymentId);
        return paymentRepository.findById(id)
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                "Payment not found: " + paymentId)))
            .flatMapMany(payment -> admin || payment.getUserId().equals(requesterId)
                ? refundRepository.findByPaymentId(id)
                : Flux.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER,
                    "Payment does not belong to the current user")));
    }

    public Flux<Refund> all() {
        return refundRepository.findAll();
    }

    public Mono<Refund> reject(UUID refundId, String reason) {
        return refundRepository.findById(refundId)
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.REFUND_NOT_FOUND,
                "Refund not found: " + refundId)))
            .flatMap(refund -> {
                refund.reject(reason == null || reason.isBlank() ? "Rejected by administrator" : reason);
                return refundRepository.save(refund).thenReturn(refund);
            });
    }
}
