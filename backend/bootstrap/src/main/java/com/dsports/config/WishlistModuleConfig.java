package com.dsports.config;

import com.dsports.cart.application.usecase.AddToCartUseCase;
import com.dsports.catalog.application.usecase.GetProductUseCase;
import com.dsports.pricing.application.usecase.GetPricesUseCase;
import com.dsports.wishlist.application.port.CartPort;
import com.dsports.wishlist.application.port.ProductCatalogPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class WishlistModuleConfig {

    private final GetProductUseCase getProductUseCase;
    private final GetPricesUseCase getPricesUseCase;
    private final AddToCartUseCase addToCartUseCase;

    public WishlistModuleConfig(GetProductUseCase getProductUseCase,
                                GetPricesUseCase getPricesUseCase,
                                AddToCartUseCase addToCartUseCase) {
        this.getProductUseCase = getProductUseCase;
        this.getPricesUseCase = getPricesUseCase;
        this.addToCartUseCase = addToCartUseCase;
    }

    @Bean
    public ProductCatalogPort wishlistProductCatalogPort() {
        return productId -> {
            var catalogProductId = com.dsports.catalog.domain.model.ProductId.fromUUID(productId);
            return getProductUseCase.execute(catalogProductId)
                .flatMap(product -> {
                    var imageUrl = product.images().stream()
                        .filter(img -> img.primary())
                        .findFirst()
                        .map(img -> img.url())
                        .orElseGet(() -> product.images().isEmpty() ? null
                            : product.images().get(0).url());
                    return getPricesUseCase.execute(
                            com.dsports.pricing.domain.model.ProductId.fromUUID(productId))
                        .filter(price -> "ACTIVE".equals(price.status()))
                        .next()
                        .map(price -> new ProductCatalogPort.ProductValidationResult(
                            product.id(), product.name(), imageUrl,
                            price.sellingPrice(),
                            "ACTIVE".equals(product.status())))
                        .switchIfEmpty(Mono.defer(() -> Mono.just(
                            new ProductCatalogPort.ProductValidationResult(
                                product.id(), product.name(), imageUrl,
                                java.math.BigDecimal.ZERO, false))));
                })
                .switchIfEmpty(Mono.empty());
        };
    }

    @Bean
    public CartPort wishlistCartPort() {
        return (userId, productId, quantity) ->
            addToCartUseCase.execute(
                    com.dsports.cart.domain.model.UserId.fromUUID(userId),
                    new com.dsports.cart.application.command.AddToCartCommand(productId, quantity))
                .then();
    }
}
