package com.dsports.cart.application.port;

import com.dsports.cart.domain.model.Cart;
import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.UserId;
import reactor.core.publisher.Mono;

public interface CartRepository {
    Mono<Cart> findById(CartId id);
    Mono<Cart> findByUserId(UserId userId);
    Mono<Boolean> existsActiveCartByUserId(UserId userId);
    Mono<Void> save(Cart cart);
    Mono<Void> deleteItem(CartId cartId, java.util.UUID itemId);
    Mono<Void> clear(CartId cartId);
}
