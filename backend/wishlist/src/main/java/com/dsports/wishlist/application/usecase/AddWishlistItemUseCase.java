package com.dsports.wishlist.application.usecase;

import com.dsports.wishlist.application.command.AddWishlistItemCommand;
import com.dsports.wishlist.application.port.ProductCatalogPort;
import com.dsports.wishlist.application.port.WishlistRepository;
import com.dsports.wishlist.application.result.WishlistResult;
import com.dsports.wishlist.domain.exception.WishlistDomainException;
import com.dsports.wishlist.domain.exception.WishlistErrorCode;
import com.dsports.wishlist.domain.model.UserId;
import com.dsports.wishlist.domain.model.Wishlist;
import com.dsports.wishlist.domain.model.WishlistId;
import com.dsports.wishlist.domain.model.WishlistItemId;
import reactor.core.publisher.Mono;

public class AddWishlistItemUseCase {
    private final WishlistRepository wishlistRepository;
    private final ProductCatalogPort productCatalogPort;

    public AddWishlistItemUseCase(WishlistRepository wishlistRepository,
                                  ProductCatalogPort productCatalogPort) {
        this.wishlistRepository = wishlistRepository;
        this.productCatalogPort = productCatalogPort;
    }

    public Mono<WishlistResult> execute(UserId userId, AddWishlistItemCommand command) {
        var productId = command.productId();

        return validateProduct(productId)
            .flatMap(product ->
                getOrCreateWishlist(userId)
                    .flatMap(wishlist -> {
                        var itemId = WishlistItemId.generate();
                        wishlist.addItem(itemId, productId.toString(), product.name(),
                            product.image(), product.price());
                        return wishlistRepository.save(wishlist)
                            .thenReturn(WishlistResultMapper.toResult(wishlist));
                    }));
    }

    private Mono<ProductCatalogPort.ProductValidationResult> validateProduct(java.util.UUID productId) {
        return productCatalogPort.findActiveProduct(productId)
            .switchIfEmpty(Mono.error(new WishlistDomainException(WishlistErrorCode.PRODUCT_NOT_FOUND,
                "Product not found: " + productId)))
            .flatMap(product -> {
                if (!product.active()) {
                    return Mono.error(new WishlistDomainException(WishlistErrorCode.PRODUCT_NOT_ACTIVE,
                        "Product is not active: " + productId));
                }
                return Mono.just(product);
            });
    }

    private Mono<Wishlist> getOrCreateWishlist(UserId userId) {
        return wishlistRepository.findByUserId(userId)
            .switchIfEmpty(Mono.defer(() -> {
                var newWishlist = Wishlist.create(WishlistId.generate(), userId);
                return wishlistRepository.save(newWishlist).thenReturn(newWishlist);
            }));
    }
}
