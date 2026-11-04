package com.dsports.wishlist.infrastructure.persistence.repository;

import com.dsports.wishlist.infrastructure.persistence.entity.WishlistEntity;
import com.dsports.wishlist.infrastructure.persistence.entity.WishlistItemEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcWishlistRepository extends R2dbcRepository<WishlistEntity, UUID> {

    @Query("SELECT * FROM wishlists WHERE user_id = :userId")
    Mono<WishlistEntity> findByUserId(UUID userId);

    @Query("SELECT EXISTS(SELECT 1 FROM wishlists WHERE user_id = :userId)")
    Mono<Boolean> existsByUserId(UUID userId);

    @Query("SELECT * FROM wishlist_items WHERE wishlist_id = :wishlistId")
    Flux<WishlistItemEntity> findItemsByWishlistId(UUID wishlistId);

    @Query("DELETE FROM wishlist_items WHERE wishlist_id = :wishlistId AND id = :itemId")
    Mono<Integer> deleteItem(UUID wishlistId, UUID itemId);

    @Query("DELETE FROM wishlist_items WHERE wishlist_id = :wishlistId")
    Mono<Integer> clearItems(UUID wishlistId);
}
