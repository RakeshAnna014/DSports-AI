package com.dsports.order.application.promotion.port;

import com.dsports.order.domain.promotion.model.Promotion;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/** Read access to promotions and their usage, plus the row lock that serialises concurrent redemptions of one promotion. */
public interface PromotionRepository {

    Mono<Promotion> findByCode(String code);

    /** Active promotions, for listing; validity against the clock is decided by the calculator. */
    Flux<Promotion> findActive();

    /** Takes a row lock on the promotion for the current transaction, so two redemptions can not both pass the same usage limit. */
    Mono<Promotion> lockById(UUID promotionId);

    /** Redemptions by this customer, not counting {@code exceptCheckoutId} and not counting cancelled orders. */
    Mono<Long> countCustomerRedemptions(UUID promotionId, UUID customerId, UUID exceptCheckoutId);

    /** Redemptions overall, not counting {@code exceptCheckoutId} and not counting cancelled orders. */
    Mono<Long> countTotalRedemptions(UUID promotionId, UUID exceptCheckoutId);

    /** The order created from the checkout (in the current transaction), if any. */
    Mono<UUID> findOrderIdByCheckout(UUID checkoutId);

    Mono<Void> insertRedemption(UUID promotionId, UUID customerId, UUID checkoutId, UUID orderId, BigDecimal discountAmount);
}
