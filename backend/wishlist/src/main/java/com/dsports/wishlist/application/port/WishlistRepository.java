package com.dsports.wishlist.application.port;

import com.dsports.wishlist.domain.model.Wishlist;
import com.dsports.wishlist.domain.model.WishlistId;
import com.dsports.wishlist.domain.model.UserId;
import reactor.core.publisher.Mono;

public interface WishlistRepository {
    Mono<Wishlist> findById(WishlistId id);
    Mono<Wishlist> findByUserId(UserId userId);
    Mono<Boolean> existsByUserId(UserId userId);
    Mono<Void> save(Wishlist wishlist);
    Mono<Void> deleteItem(WishlistId wishlistId, java.util.UUID itemId);
    Mono<Void> clear(WishlistId wishlistId);
}
