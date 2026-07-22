package com.dsports.cart.infrastructure.persistence.mapper;

import com.dsports.cart.domain.model.*;
import com.dsports.cart.infrastructure.persistence.entity.CartEntity;
import com.dsports.cart.infrastructure.persistence.entity.CartItemEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class CartEntityMapper {

    private CartEntityMapper() {}

    public static CartEntity toEntity(Cart cart) {
        var entity = new CartEntity();
        entity.setId(cart.getId().value());
        entity.setUserId(cart.getUserId().value());
        entity.setStatus(cart.getStatus().name());
        entity.setTotalItems(cart.getTotalItems());
        entity.setTotalAmount(cart.getTotalAmount().value());
        entity.setVersion(cart.getVersion());
        entity.setCreatedAt(cart.getCreatedAt());
        entity.setUpdatedAt(cart.getUpdatedAt());
        return entity;
    }

    public static CartItemEntity toItemEntity(CartItem item) {
        var entity = new CartItemEntity();
        entity.setId(item.getId().value());
        entity.setCartId(item.getCartId().value());
        entity.setProductId(UUID.fromString(item.getProductId()));
        entity.setProductName(item.getProductName());
        entity.setUnitPrice(item.getUnitPrice().value());
        entity.setQuantity(item.getQuantity().value());
        entity.setLineTotal(item.getLineTotal().value());
        entity.setCreatedAt(item.getCreatedAt());
        entity.setUpdatedAt(item.getUpdatedAt());
        return entity;
    }

    public static Cart toDomain(CartEntity entity, List<CartItemEntity> itemEntities) {
        var items = itemEntities.stream()
            .map(CartEntityMapper::toItemDomain)
            .toList();
        var cartId = CartId.fromUUID(entity.getId());
        var userId = UserId.fromUUID(entity.getUserId());
        return Cart.reconstitute(
            cartId,
            userId,
            items,
            CartStatus.valueOf(entity.getStatus()),
            entity.getTotalItems(),
            entity.getTotalAmount(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public static CartItem toItemDomain(CartItemEntity entity) {
        return CartItem.reconstitute(
            CartItemId.fromUUID(entity.getId()),
            CartId.fromUUID(entity.getCartId()),
            entity.getProductId().toString(),
            entity.getProductName(),
            Money.from(entity.getUnitPrice()),
            Quantity.from(entity.getQuantity()),
            Money.from(entity.getLineTotal()),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
