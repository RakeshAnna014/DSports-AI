package com.dsports.cart.application.usecase;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.cart.domain.model.CartItemId;
import com.dsports.cart.domain.model.UserId;
import reactor.core.publisher.Mono;

public class RemoveCartItemUseCase {
    private final CartRepository cartRepository;

    public RemoveCartItemUseCase(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    public Mono<CartResult> execute(UserId userId, CartItemId itemId) {
        return cartRepository.findByUserId(userId)
            .switchIfEmpty(Mono.error(new CartDomainException(CartErrorCode.CART_NOT_FOUND,
                "No active cart found for user")))
            .flatMap(cart -> {
                cart.removeItem(itemId);
                return cartRepository.save(cart)
                    .thenReturn(CartResultMapper.toResult(cart));
            });
    }
}
