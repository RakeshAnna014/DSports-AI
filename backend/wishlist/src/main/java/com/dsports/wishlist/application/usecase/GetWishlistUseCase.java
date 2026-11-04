package com.dsports.wishlist.application.usecase;

import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.application.result.WishlistResult;
import com.dsports.wishlist.domain.model.UserId;
import reactor.core.publisher.Mono;

public class GetWishlistUseCase {
    private final WishlistRepository wishlistRepository;

    public GetWishlistUseCase(WishlistRepository wishlistRepository) {
        this.wishlistRepository = wishlistRepository;
    }

    public Mono<WishlistResult> execute(UserId userId) {
        return wishlistRepository.findByUserId(userId)
            .map(WishlistResultMapper::toResult);
    }
}
