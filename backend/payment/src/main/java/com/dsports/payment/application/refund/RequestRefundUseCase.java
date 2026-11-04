package com.dsports.payment.application.refund;

import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.refund.port.RefundRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.refund.Refund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Records a refund request. Customers may only request refunds for their own captured payments; nothing is paid
 * out until an administrator approves the request ({@link ProcessRefundUseCase}). Administrators can create a
 * request for any captured payment.
 * <p>
 * Idempotent on {@code (paymentId, idempotencyKey)}: repeating a request returns the original refund.
 */
public class RequestRefundUseCase {
    private static final Logger log = LoggerFactory.getLogger(RequestRefundUseCase.class);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public RequestRefundUseCase(PaymentRepository paymentRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    /**
     * @param amount       amount to refund; {@code null} means the full remaining refundable amount
     * @param customerOnly when true the requester must own the payment
     */
    public record Command(UUID paymentId, UUID requesterId, boolean customerOnly, BigDecimal amount,
                          String reason, String idempotencyKey) {}

    public Mono<Refund> execute(Command command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return Mono.error(new PaymentDomainException(PaymentErrorCode.VALIDATION_ERROR,
                "An Idempotency-Key header is required to request a refund"));
        }
        var paymentId = PaymentId.fromUUID(command.paymentId());
        return paymentRepository.findById(paymentId)
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_FOUND,
                "Payment not found: " + command.paymentId())))
            .flatMap(payment -> {
                if (command.customerOnly() && !payment.getUserId().equals(command.requesterId())) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.PAYMENT_NOT_OWNED_BY_USER,
                        "Payment does not belong to the current user"));
                }
                return refundRepository.findByPaymentIdAndIdempotencyKey(paymentId, command.idempotencyKey())
                    .flatMap(existing -> sameRequest(existing, command)
                        ? Mono.just(existing)
                        : Mono.error(new PaymentDomainException(PaymentErrorCode.REFUND_NOT_ALLOWED,
                            "Idempotency key " + command.idempotencyKey() + " was already used for a different refund")))
                    .switchIfEmpty(Mono.defer(() -> createRequest(payment, command)));
            });
    }

    private Mono<Refund> createRequest(Payment payment, Command command) {
        if (!payment.getStatus().isCaptured() || payment.getRefundableAmount().signum() <= 0) {
            return Mono.error(new PaymentDomainException(PaymentErrorCode.REFUND_NOT_ALLOWED,
                "Payment " + payment.getPaymentReference().value() + " has nothing left to refund (status "
                    + payment.getStatus() + ")"));
        }
        return refundRepository.findByPaymentId(payment.getId())
            .filter(r -> r.getStatus().isPending())
            .map(Refund::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .flatMap(pending -> {
                var available = payment.getRefundableAmount().subtract(pending);
                var amount = command.amount() == null ? available : command.amount();
                if (amount.signum() <= 0 || amount.compareTo(available) > 0) {
                    return Mono.error(new PaymentDomainException(PaymentErrorCode.INVALID_REFUND_AMOUNT,
                        "Refund amount must be between 0.01 and " + available + " " + payment.getCurrency()
                            + " (refundable " + payment.getRefundableAmount() + ", already requested " + pending + ")"));
                }
                var refund = Refund.request(payment.getId(), payment.getOrderId(), payment.getUserId(), amount,
                    payment.getCurrency(), command.reason(), command.requesterId(), command.idempotencyKey());
                return refundRepository.save(refund)
                    .thenReturn(refund)
                    .onErrorResume(PaymentDomainException.class, e -> e.getErrorCode() == PaymentErrorCode.DUPLICATE_PAYMENT
                        ? refundRepository.findByPaymentIdAndIdempotencyKey(payment.getId(), command.idempotencyKey())
                        : Mono.error(e))
                    .doOnNext(r -> log.info("Refund {} of {} {} requested for payment {} by {}", r.getId(), r.getAmount(),
                        r.getCurrency(), payment.getPaymentReference().value(), command.requesterId()));
            });
    }

    private static boolean sameRequest(Refund existing, Command command) {
        return command.amount() == null || existing.getAmount().compareTo(command.amount()) == 0;
    }
}
