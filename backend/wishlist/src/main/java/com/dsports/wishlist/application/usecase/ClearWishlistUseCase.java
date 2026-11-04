package com.dsports.wishlist.application.usecase;

import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.application.result.WishlistResult;
import com.dsports.wishlist.domain.exception.WishlistDomainException;
import com.dsports.wishlist.domain.exception.WishlistErrorCode;
import com.dsports.wishlist.domain.model.UserId;
import reactor.core.publisher.Mono;

public class ClearWishlistUseCase {
    private final WishlistRepository wishlistRepository;

    public ClearWishlistUseCase(WishlistRepository wishlistRepository) {
        this.wishlistRepository = wishlistRepository;
    }

    public Mono<WishlistResult> execute(UserId userId) {
        return wishlistRepository.findByUserId(userId)
            .switchIfEmpty(Mono.error(new WishlistDomainException(WishlistErrorCode.WISHLIST_NOT_FOUND,
                "No wishlist found for user")))
            .flatMap(wishlist -> {
                wishlist.clear();
                return wishlistRepository.save(wishlist)
                    .thenReturn(WishlistResultMapper.toResult(wishlist));
            });
    }
}
