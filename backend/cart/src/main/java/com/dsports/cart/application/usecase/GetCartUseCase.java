package com.dsports.cart.application.usecase;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.domain.model.UserId;
import reactor.core.publisher.Mono;

public class GetCartUseCase {
    private final CartRepository cartRepository;

    public GetCartUseCase(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    public Mono<CartResult> execute(UserId userId) {
        return cartRepository.findByUserId(userId)
            .map(CartResultMapper::toResult);
    }
}
