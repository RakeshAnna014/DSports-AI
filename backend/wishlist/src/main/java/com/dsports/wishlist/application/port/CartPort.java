package com.dsports.wishlist.application.port;

import reactor.core.publisher.Mono;
import java.util.UUID;

public interface CartPort {
    Mono<Void> addProduct(UUID userId, UUID productId, int quantity);
}
