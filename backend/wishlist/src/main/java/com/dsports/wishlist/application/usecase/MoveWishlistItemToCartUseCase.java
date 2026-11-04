package com.dsports.wishlist.application.usecase;

import com.dsports.wishlist.application.port.CartPort;
import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.application.result.WishlistResult;
import com.dsports.wishlist.domain.exception.WishlistDomainException;
import com.dsports.wishlist.domain.exception.WishlistErrorCode;
import com.dsports.wishlist.domain.model.UserId;
import com.dsports.wishlist.domain.model.WishlistItemId;
import reactor.core.publisher.Mono;

public class MoveWishlistItemToCartUseCase {
    private final WishlistRepository wishlistRepository;
    private final CartPort cartPort;

    public MoveWishlistItemToCartUseCase(WishlistRepository wishlistRepository,
                                         CartPort cartPort) {
        this.wishlistRepository = wishlistRepository;
        this.cartPort = cartPort;
    }

    public Mono<WishlistResult> execute(UserId userId, WishlistItemId itemId) {
        return wishlistRepository.findByUserId(userId)
            .switchIfEmpty(Mono.error(new WishlistDomainException(WishlistErrorCode.WISHLIST_NOT_FOUND,
                "No wishlist found for user")))
            .flatMap(wishlist -> {
                var item = wishlist.findItemById(itemId)
                    .orElseThrow(() -> new WishlistDomainException(WishlistErrorCode.ITEM_NOT_FOUND,
                        "Wishlist item not found: " + itemId.value()));

                var productId = java.util.UUID.fromString(item.getProductId());
                wishlist.moveToCart(itemId);

                return wishlistRepository.save(wishlist)
                    .then(cartPort.addProduct(userId.value(), productId, 1))
                    .thenReturn(WishlistResultMapper.toResult(wishlist));
            });
    }
}
