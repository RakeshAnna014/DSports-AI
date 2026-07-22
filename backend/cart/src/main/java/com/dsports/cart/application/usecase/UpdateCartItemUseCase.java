package com.dsports.cart.application.usecase;

import com.dsports.cart.application.command.UpdateCartItemCommand;
import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.port.InventoryPort;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.cart.domain.model.*;
import reactor.core.publisher.Mono;

public class UpdateCartItemUseCase {
    private final CartRepository cartRepository;
    private final InventoryPort inventoryPort;

    public UpdateCartItemUseCase(CartRepository cartRepository, InventoryPort inventoryPort) {
        this.cartRepository = cartRepository;
        this.inventoryPort = inventoryPort;
    }

    public Mono<CartResult> execute(UserId userId, UpdateCartItemCommand command) {
        var itemId = CartItemId.fromUUID(command.itemId());
        var newQty = Quantity.from(command.quantity());

        return cartRepository.findByUserId(userId)
            .switchIfEmpty(Mono.error(new CartDomainException(CartErrorCode.CART_NOT_FOUND,
                "No active cart found for user")))
            .flatMap(cart -> {
                var existingItem = cart.findItemById(itemId)
                    .orElseThrow(() -> new CartDomainException(CartErrorCode.ITEM_NOT_FOUND,
                        "Cart item not found: " + itemId.value()));

                var delta = newQty.value() - existingItem.getQuantity().value();
                if (delta > 0) {
                    return inventoryPort.checkAvailability(
                            java.util.UUID.fromString(existingItem.getProductId()), delta)
                        .flatMap(result -> {
                            if (!result.sufficient()) {
                                return Mono.error(new CartDomainException(CartErrorCode.INSUFFICIENT_STOCK,
                                    "Insufficient stock to increase quantity"));
                            }
                            cart.updateItemQuantity(itemId, newQty);
                            return cartRepository.save(cart)
                                .thenReturn(CartResultMapper.toResult(cart));
                        });
                }
                cart.updateItemQuantity(itemId, newQty);
                return cartRepository.save(cart)
                    .thenReturn(CartResultMapper.toResult(cart));
            });
    }
}
