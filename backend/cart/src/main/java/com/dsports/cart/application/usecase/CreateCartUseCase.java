package com.dsports.cart.application.usecase;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.cart.domain.model.Cart;
import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.UserId;
import reactor.core.publisher.Mono;

public class CreateCartUseCase {
    private final CartRepository cartRepository;

    public CreateCartUseCase(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    public Mono<CartResult> execute(UserId userId) {
        return cartRepository.existsActiveCartByUserId(userId)
            .flatMap(exists -> {
                if (exists) {
                    return Mono.error(new CartDomainException(CartErrorCode.DUPLICATE_ACTIVE_CART,
                        "User already has an active cart"));
                }
                var cart = Cart.create(CartId.generate(), userId);
                return cartRepository.save(cart)
                    .thenReturn(CartResultMapper.toResult(cart));
            });
    }
}
