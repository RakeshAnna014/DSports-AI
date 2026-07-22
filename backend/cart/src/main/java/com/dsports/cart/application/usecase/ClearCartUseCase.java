package com.dsports.cart.application.usecase;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.cart.domain.model.UserId;
import reactor.core.publisher.Mono;

public class ClearCartUseCase {
    private final CartRepository cartRepository;

    public ClearCartUseCase(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    public Mono<CartResult> execute(UserId userId) {
        return cartRepository.findByUserId(userId)
            .switchIfEmpty(Mono.error(new CartDomainException(CartErrorCode.CART_NOT_FOUND,
                "No active cart found for user")))
            .flatMap(cart -> {
                cart.clear();
                return cartRepository.save(cart)
                    .thenReturn(CartResultMapper.toResult(cart));
            });
    }
}
