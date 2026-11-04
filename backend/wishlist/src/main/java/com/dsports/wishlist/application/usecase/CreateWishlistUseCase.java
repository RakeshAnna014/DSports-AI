package com.dsports.wishlist.application.usecase;

import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.application.result.WishlistResult;
import com.dsports.wishlist.domain.model.UserId;
import com.dsports.wishlist.domain.model.Wishlist;
import com.dsports.wishlist.domain.model.WishlistId;
import reactor.core.publisher.Mono;

public class CreateWishlistUseCase {
    private final WishlistRepository wishlistRepository;

    public CreateWishlistUseCase(WishlistRepository wishlistRepository) {
        this.wishlistRepository = wishlistRepository;
    }

    public Mono<WishlistResult> execute(UserId userId) {
        var wishlist = Wishlist.create(WishlistId.generate(), userId);
        return wishlistRepository.save(wishlist)
            .thenReturn(WishlistResultMapper.toResult(wishlist));
    }
}
