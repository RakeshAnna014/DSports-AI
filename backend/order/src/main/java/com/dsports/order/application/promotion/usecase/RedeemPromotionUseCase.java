package com.dsports.order.application.promotion.usecase;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.promotion.port.ProductScopePort;
import com.dsports.order.application.promotion.port.PromotionRepository;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Records the redemption of the checkout's promotion as part of placing the order (same transaction). The promotion row is locked
 * first, so concurrent orders can not both pass the same usage limit; the discount is re-evaluated against the current rules and
 * must equal the discount the customer saw. Any mismatch fails the order, which rolls the whole transaction back.
 */
@Service
public class RedeemPromotionUseCase {
    private static final Logger log = LoggerFactory.getLogger(RedeemPromotionUseCase.class);

    private final CheckoutRepository checkoutRepository;
    private final PromotionRepository promotionRepository;
    private final ProductScopePort productScopePort;
    private final Clock clock;

    @Autowired
    public RedeemPromotionUseCase(CheckoutRepository checkoutRepository, PromotionRepository promotionRepository, ProductScopePort productScopePort) {
        this(checkoutRepository, promotionRepository, productScopePort, Clock.systemUTC());
    }

    RedeemPromotionUseCase(CheckoutRepository checkoutRepository, PromotionRepository promotionRepository, ProductScopePort productScopePort, Clock clock) {
        this.checkoutRepository = checkoutRepository;
        this.promotionRepository = promotionRepository;
        this.productScopePort = productScopePort;
        this.clock = clock;
    }

    /** No-op for a checkout without a promotion. */
    public Mono<Void> redeem(UUID checkoutId) {
        return checkoutRepository.findById(CheckoutId.fromUUID(checkoutId))
            .flatMap(checkout -> checkout.getPromotionId() == null ? Mono.<Void>empty() : redeemPromotion(checkout));
    }

    private Mono<Void> redeemPromotion(Checkout checkout) {
        var promotionId = checkout.getPromotionId();
        return promotionRepository.lockById(promotionId)
            .switchIfEmpty(Mono.error(noLongerValid("The promotion on this checkout no longer exists")))
            .flatMap(promotion -> PromotionEvaluationSupport.linesOf(checkout, productScopePort)
                .flatMap(lines -> PromotionEvaluationSupport.evaluate(promotion, checkout, lines, promotionRepository, clock))
                .flatMap(evaluation -> {
                    if (!evaluation.eligible()) {
                        return Mono.<Void>error(noLongerValid(evaluation.reason()));
                    }
                    if (evaluation.discount().compareTo(checkout.getDiscountAmount()) != 0) {
                        return Mono.<Void>error(noLongerValid("The discount for this offer changed; remove and apply it again"));
                    }
                    return promotionRepository.findOrderIdByCheckout(checkout.getId().value())
                        .flatMap(orderId -> promotionRepository.insertRedemption(promotionId, checkout.getCustomerId(), checkout.getId().value(), orderId, evaluation.discount()))
                        .doOnSuccess(v -> log.info("Promotion {} redeemed for checkout {}", promotion.code(), checkout.getId().value()));
                }));
    }

    private static OrderDomainException noLongerValid(String message) {
        return new OrderDomainException(OrderErrorCode.PROMOTION_NO_LONGER_VALID, message);
    }
}
