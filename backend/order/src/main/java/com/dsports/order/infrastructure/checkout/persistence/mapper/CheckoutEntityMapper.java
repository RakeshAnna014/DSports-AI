package com.dsports.order.infrastructure.checkout.persistence.mapper;

import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.CheckoutItem;
import com.dsports.order.domain.checkout.model.CheckoutItemId;
import com.dsports.order.domain.checkout.model.CheckoutStatus;
import com.dsports.order.domain.checkout.model.DeliveryMethod;
import com.dsports.order.infrastructure.checkout.persistence.entity.CheckoutEntity;
import com.dsports.order.infrastructure.checkout.persistence.entity.CheckoutItemEntity;

import java.util.List;
import java.util.UUID;

public final class CheckoutEntityMapper {

    private CheckoutEntityMapper() {}

    public static CheckoutEntity toEntity(Checkout checkout) {
        var entity = new CheckoutEntity();
        entity.setId(checkout.getId().value());
        entity.setCustomerId(checkout.getCustomerId());
        entity.setCartId(checkout.getCartId());
        entity.setStatus(checkout.getStatus().name());
        entity.setShippingAddressId(checkout.getShippingAddressId());
        if (checkout.getDeliveryMethod() != null) {
            entity.setDeliveryMethodCode(checkout.getDeliveryMethod().code());
            entity.setDeliveryMethodName(checkout.getDeliveryMethod().name());
        }
        entity.setDeliveryCharge(checkout.getDeliveryCharge());
        entity.setDiscountAmount(checkout.getDiscountAmount());
        entity.setSubtotal(checkout.getSubtotal());
        entity.setTaxAmount(checkout.getTaxAmount());
        entity.setTotalAmount(checkout.getTotalAmount());
        entity.setCurrency(checkout.getCurrency());
        entity.setNotes(checkout.getNotes());
        entity.setExpiresAt(checkout.getExpiresAt());
        entity.setValidatedAt(checkout.getValidatedAt());
        entity.setCancelledAt(checkout.getCancelledAt());
        entity.setVersion(checkout.getVersion());
        entity.setCreatedAt(checkout.getCreatedAt());
        entity.setUpdatedAt(checkout.getUpdatedAt());
        return entity;
    }

    public static CheckoutItemEntity toItemEntity(CheckoutItem item) {
        var entity = new CheckoutItemEntity();
        entity.setId(item.getId().value());
        entity.setCheckoutId(item.getCheckoutId().value());
        entity.setProductId(UUID.fromString(item.getProductId()));
        entity.setProductName(item.getProductName());
        entity.setSku(item.getSku());
        entity.setQuantity(item.getQuantity());
        entity.setUnitPrice(item.getUnitPrice());
        entity.setLineTotal(item.getLineTotal());
        entity.setImageUrl(item.getImageUrl());
        entity.setCreatedAt(item.getCreatedAt());
        entity.setNew(true);
        return entity;
    }

    public static Checkout toDomain(CheckoutEntity entity, List<CheckoutItemEntity> itemEntities) {
        var items = itemEntities.stream()
            .map(CheckoutEntityMapper::toItemDomain)
            .toList();
        var checkoutId = CheckoutId.fromUUID(entity.getId());
        DeliveryMethod deliveryMethod = null;
        if (entity.getDeliveryMethodCode() != null) {
            try {
                deliveryMethod = DeliveryMethod.fromCode(entity.getDeliveryMethodCode());
            } catch (IllegalArgumentException ignored) {}
        }
        return Checkout.reconstitute(
            checkoutId, entity.getCustomerId(), entity.getCartId(),
            CheckoutStatus.valueOf(entity.getStatus()),
            entity.getShippingAddressId(), deliveryMethod, items,
            entity.getSubtotal(), entity.getTaxAmount(),
            entity.getDeliveryCharge(), entity.getDiscountAmount(),
            entity.getTotalAmount(), entity.getCurrency(), entity.getNotes(),
            entity.getExpiresAt(), entity.getValidatedAt(),
            entity.getCancelledAt(), entity.getVersion(),
            entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    public static CheckoutItem toItemDomain(CheckoutItemEntity entity) {
        return new CheckoutItem(
            CheckoutItemId.fromUUID(entity.getId()),
            CheckoutId.fromUUID(entity.getCheckoutId()),
            entity.getProductId().toString(),
            entity.getProductName(),
            entity.getSku(),
            entity.getQuantity(),
            entity.getUnitPrice(),
            entity.getLineTotal(),
            entity.getImageUrl(),
            entity.getCreatedAt()
        );
    }
}
