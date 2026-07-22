package com.dsports.cart.application.usecase;

import com.dsports.cart.application.result.CartItemResult;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.domain.model.Cart;
import com.dsports.cart.domain.model.CartItem;

import java.util.List;

public final class CartResultMapper {

    private CartResultMapper() {}

    public static CartResult toResult(Cart cart) {
        List<CartItemResult> itemResults = cart.getItems().stream()
            .map(CartResultMapper::toItemResult)
            .toList();

        return new CartResult(
            cart.getId().value(),
            cart.getUserId().value(),
            cart.getStatus().name(),
            cart.getTotalItems(),
            cart.getTotalAmount().value(),
            cart.getVersion(),
            itemResults,
            cart.getCreatedAt(),
            cart.getUpdatedAt()
        );
    }

    public static CartItemResult toItemResult(CartItem item) {
        return new CartItemResult(
            item.getId().value(),
            java.util.UUID.fromString(item.getProductId()),
            item.getProductName(),
            item.getUnitPrice().value(),
            item.getQuantity().value(),
            item.getLineTotal().value(),
            item.getCreatedAt(),
            item.getUpdatedAt()
        );
    }
}
