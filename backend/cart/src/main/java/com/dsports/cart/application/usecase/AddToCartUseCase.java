package com.dsports.cart.application.usecase;

import com.dsports.cart.application.command.AddToCartCommand;
import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.application.port.InventoryPort;
import com.dsports.cart.application.port.PricingPort;
import com.dsports.cart.application.port.ProductCatalogPort;
import com.dsports.cart.application.result.CartResult;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.cart.domain.model.*;
import reactor.core.publisher.Mono;

public class AddToCartUseCase {
    private final CartRepository cartRepository;
    private final ProductCatalogPort productCatalogPort;
    private final InventoryPort inventoryPort;
    private final PricingPort pricingPort;

    public AddToCartUseCase(CartRepository cartRepository,
                            ProductCatalogPort productCatalogPort,
                            InventoryPort inventoryPort,
                            PricingPort pricingPort) {
        this.cartRepository = cartRepository;
        this.productCatalogPort = productCatalogPort;
        this.inventoryPort = inventoryPort;
        this.pricingPort = pricingPort;
    }

    public Mono<CartResult> execute(UserId userId, AddToCartCommand command) {
        var productId = command.productId();
        var requestedQty = command.quantity();

        return validateProduct(productId)
            .flatMap(product -> validateInventory(productId, requestedQty)
                .then(Mono.defer(() -> getActivePrice(productId)))
                .flatMap(price -> getOrCreateCart(userId)
                    .flatMap(cart -> {
                        var itemId = CartItemId.generate();
                        var qty = Quantity.from(requestedQty);
                        cart.addItem(itemId, productId.toString(), product.name(),
                            Money.from(price.unitPrice()), qty);
                        return cartRepository.save(cart)
                            .thenReturn(CartResultMapper.toResult(cart));
                    })));
    }

    private Mono<ProductCatalogPort.ProductValidationResult> validateProduct(java.util.UUID productId) {
        return productCatalogPort.findActiveProduct(productId)
            .switchIfEmpty(Mono.error(new CartDomainException(CartErrorCode.PRODUCT_NOT_FOUND,
                "Product not found: " + productId)))
            .flatMap(product -> {
                if (!product.active()) {
                    return Mono.error(new CartDomainException(CartErrorCode.PRODUCT_NOT_ACTIVE,
                        "Product is not active: " + productId));
                }
                return Mono.just(product);
            });
    }

    private Mono<Void> validateInventory(java.util.UUID productId, int requestedQuantity) {
        return inventoryPort.checkAvailability(productId, requestedQuantity)
            .flatMap(result -> {
                if (!result.sufficient()) {
                    return Mono.error(new CartDomainException(CartErrorCode.INSUFFICIENT_STOCK,
                        "Insufficient stock for product " + productId
                            + ". Available: " + result.availableQuantity()
                            + ", Requested: " + requestedQuantity));
                }
                return Mono.empty();
            });
    }

    private Mono<PricingPort.ActivePriceResult> getActivePrice(java.util.UUID productId) {
        return pricingPort.getActivePrice(productId)
            .switchIfEmpty(Mono.error(new CartDomainException(CartErrorCode.PRICE_NOT_FOUND,
                "No active price found for product: " + productId)));
    }

    private Mono<Cart> getOrCreateCart(UserId userId) {
        return cartRepository.findByUserId(userId)
            .switchIfEmpty(Mono.defer(() -> {
                var newCart = Cart.create(CartId.generate(), userId);
                return cartRepository.save(newCart).thenReturn(newCart);
            }));
    }
}
