package com.dsports.order.application.promotion.usecase;

import com.dsports.order.application.promotion.port.ProductScopePort;
import com.dsports.order.application.promotion.port.PromotionRepository;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.promotion.model.Promotion;
import com.dsports.order.domain.promotion.model.PromotionCalculator;
import com.dsports.order.domain.promotion.model.PromotionCalculator.Evaluation;
import com.dsports.order.domain.promotion.model.PromotionCalculator.Line;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/** Shared by the list, apply and redeem use cases so they all evaluate a promotion identically. */
final class PromotionEvaluationSupport {

    private PromotionEvaluationSupport() {}

    static Mono<java.util.List<Line>> linesOf(Checkout checkout, ProductScopePort scopes) {
        var ids = checkout.getItems().stream().map(item -> UUID.fromString(item.getProductId())).toList();
        return scopes.scopesOf(ids).map(byProduct -> checkout.getItems().stream().map(item -> {
            var productId = UUID.fromString(item.getProductId());
            var scope = byProduct.get(productId);
            return new Line(productId, item.getLineTotal(), scope == null ? null : scope.categoryId(), scope == null ? null : scope.sportId());
        }).toList());
    }

    static Mono<Evaluation> evaluate(Promotion promotion, Checkout checkout, java.util.List<Line> lines, PromotionRepository repository, Clock clock) {
        return Mono.zip(
                repository.countCustomerRedemptions(promotion.id(), checkout.getCustomerId(), checkout.getId().value()),
                repository.countTotalRedemptions(promotion.id(), checkout.getId().value()))
            .map(counts -> PromotionCalculator.evaluate(promotion, lines, clock.instant(), counts.getT1(), counts.getT2()));
    }

    /** The error a rejected evaluation is reported as; the message is the customer-readable reason. */
    static CheckoutDomainException rejection(Evaluation evaluation) {
        var code = switch (evaluation.rejection()) {
            case EXPIRED, NOT_STARTED, NOT_ACTIVE -> CheckoutErrorCode.PROMOTION_EXPIRED;
            case MIN_ORDER_NOT_MET -> CheckoutErrorCode.PROMOTION_MIN_ORDER_NOT_MET;
            case CUSTOMER_LIMIT_REACHED, USAGE_LIMIT_REACHED -> CheckoutErrorCode.PROMOTION_LIMIT_REACHED;
            case NOT_APPLICABLE -> CheckoutErrorCode.PROMOTION_NOT_APPLICABLE;
        };
        return new CheckoutDomainException(code, evaluation.reason());
    }
}
