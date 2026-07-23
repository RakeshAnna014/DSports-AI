package com.dsports.order.application.checkout.port;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public interface CartPort {
    Mono<CartData> getActiveCart(UUID customerId);

    record CartData(UUID cartId, List<CartItemData> items) {}

    record CartItemData(UUID productId, String productName, String sku, int quantity,
                        java.math.BigDecimal unitPrice, String currency, String imageUrl) {}
}
