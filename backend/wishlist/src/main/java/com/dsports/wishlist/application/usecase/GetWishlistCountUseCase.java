package com.dsports.wishlist.application.usecase;

import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.application.result.WishlistSummaryResult;
import com.dsports.wishlist.domain.model.UserId;
import reactor.core.publisher.Mono;

public class GetWishlistCountUseCase {
    private final WishlistRepository wishlistRepository;

    public GetWishlistCountUseCase(WishlistRepository wishlistRepository) {
        this.wishlistRepository = wishlistRepository;
    }

    public Mono<WishlistSummaryResult> execute(UserId userId) {
        return wishlistRepository.findByUserId(userId)
            .map(WishlistResultMapper::toSummary);
    }
}
