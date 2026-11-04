package com.dsports.review.application.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

/** Verified-purchase check: may this customer review this product on the basis of this order? */
public interface OrderQueryService {

    Mono<PurchaseVerification> verifyPurchase(UUID orderId, UUID userId, UUID productId);

    /** The customer's most recent delivered order containing the product, if any. */
    Mono<UUID> findReviewableOrder(UUID userId, UUID productId);

    enum PurchaseVerification {
        /** The order belongs to the customer, contains the product and has been delivered. */
        ELIGIBLE,
        /** No such order, or it belongs to another customer (not distinguished, to avoid leaking orders). */
        ORDER_NOT_FOUND,
        PRODUCT_NOT_IN_ORDER,
        NOT_DELIVERED
    }
}
