package com.dsports.returns.interfaces;

import com.dsports.returns.domain.model.ReturnRequest;

import java.time.Instant;
import java.util.UUID;

public record ReturnResponse(UUID id, UUID orderId, UUID productId, String productName, int quantity, String reason,
                             String status, String decisionNote, Instant createdAt, Instant updatedAt) {

    public static ReturnResponse from(ReturnRequest r) {
        return new ReturnResponse(r.getId(), r.getOrderId(), r.getProductId(), r.getProductName(), r.getQuantity(),
            r.getReason(), r.getStatus().name(), r.getDecisionNote(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
