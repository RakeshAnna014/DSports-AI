package com.dsports.cart.infrastructure.persistence.repository;

import com.dsports.cart.infrastructure.persistence.entity.CartEntity;
import com.dsports.cart.infrastructure.persistence.entity.CartItemEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcCartRepository extends R2dbcRepository<CartEntity, UUID> {

    @Query("SELECT * FROM carts WHERE user_id = :userId AND status = 'ACTIVE'")
    Mono<CartEntity> findActiveByUserId(UUID userId);

    @Query("SELECT EXISTS(SELECT 1 FROM carts WHERE user_id = :userId AND status = 'ACTIVE')")
    Mono<Boolean> existsActiveByUserId(UUID userId);

    @Query("SELECT * FROM cart_items WHERE cart_id = :cartId")
    Flux<CartItemEntity> findItemsByCartId(UUID cartId);

    @Query("DELETE FROM cart_items WHERE cart_id = :cartId AND id = :itemId")
    Mono<Integer> deleteItem(UUID cartId, UUID itemId);

    @Query("DELETE FROM cart_items WHERE cart_id = :cartId")
    Mono<Integer> clearItems(UUID cartId);
}
