package com.dsports.payment.infrastructure.payment.persistence.mapper;

import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentMethod;
import com.dsports.payment.domain.payment.model.PaymentProvider;
import com.dsports.payment.domain.payment.model.PaymentReference;
import com.dsports.payment.domain.payment.model.PaymentStatus;
import com.dsports.payment.infrastructure.payment.persistence.entity.PaymentEntity;

import java.util.UUID;

public final class PaymentEntityMapper {
    private PaymentEntityMapper() {}

    public static PaymentEntity toEntity(Payment payment) {
        var entity = new PaymentEntity();
        entity.setId(payment.getId().value());
        entity.setPaymentReference(payment.getPaymentReference().value());
        entity.setOrderId(payment.getOrderId());
        entity.setUserId(payment.getUserId());
        entity.setAmount(payment.getAmount());
        entity.setCurrency(payment.getCurrency());
        entity.setPaymentMethod(payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : null);
        entity.setPaymentProvider(payment.getPaymentProvider() != null ? payment.getPaymentProvider().name() : null);
        entity.setTransactionId(payment.getTransactionId());
        entity.setGatewayReference(payment.getGatewayReference());
        entity.setStatus(payment.getStatus().name());
        entity.setFailureReason(payment.getFailureReason());
        entity.setPaidAt(payment.getPaidAt());
        entity.setVersion(payment.getVersion());
        entity.setCreatedAt(payment.getCreatedAt());
        entity.setUpdatedAt(payment.getUpdatedAt());
        return entity;
    }

    public static Payment toDomain(PaymentEntity entity) {
        return Payment.reconstitute(
            PaymentId.fromUUID(entity.getId()),
            new PaymentReference(entity.getPaymentReference()),
            entity.getOrderId(),
            entity.getUserId(),
            entity.getAmount(),
            entity.getCurrency(),
            entity.getPaymentMethod() != null ? PaymentMethod.valueOf(entity.getPaymentMethod()) : null,
            entity.getPaymentProvider() != null ? PaymentProvider.valueOf(entity.getPaymentProvider()) : null,
            entity.getTransactionId(),
            entity.getGatewayReference(),
            PaymentStatus.valueOf(entity.getStatus()),
            entity.getFailureReason(),
            entity.getPaidAt(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
