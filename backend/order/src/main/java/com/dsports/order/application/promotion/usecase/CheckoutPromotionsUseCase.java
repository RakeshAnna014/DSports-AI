package com.dsports.order.application.promotion.usecase;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.result.CheckoutResultMapper;
import com.dsports.order.application.promotion.port.ProductScopePort;
import com.dsports.order.application.promotion.port.PromotionRepository;
import com.dsports.order.application.promotion.result.PromotionOfferResult;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.promotion.model.Promotion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Lists, applies and removes promotions on the customer's own checkout. Validity and the discount amount are computed here
 * (never by a client); applying is idempotent for the same code, one promotion per checkout, and only before validation.
 * The binding usage check happens again, under a row lock, when the order is placed.
 */
@Service
public class CheckoutPromotionsUseCase {
    private static final Logger log = LoggerFactory.getLogger(CheckoutPromotionsUseCase.class);

    private final CheckoutRepository checkoutRepository;
    private final PromotionRepository promotionRepository;
    private final ProductScopePort productScopePort;
    private final Clock clock;

    @Autowired
    public CheckoutPromotionsUseCase(CheckoutRepository checkoutRepository, PromotionRepository promotionRepository, ProductScopePort productScopePort) {
        this(checkoutRepository, promotionRepository, productScopePort, Clock.systemUTC());
    }

    CheckoutPromotionsUseCase(CheckoutRepository checkoutRepository, PromotionRepository promotionRepository, ProductScopePort productScopePort, Clock clock) {
        this.checkoutRepository = checkoutRepository;
        this.promotionRepository = promotionRepository;
        this.productScopePort = productScopePort;
        this.clock = clock;
    }

    /** Offers that are currently valid, each with this cart's authoritative discount or the reason it does not apply. */
    public Mono<List<PromotionOfferResult>> list(UUID checkoutId, UUID customerId) {
        return ownedCheckout(checkoutId, customerId).flatMap(checkout ->
            PromotionEvaluationSupport.linesOf(checkout, productScopePort).flatMap(lines ->
                promotionRepository.findActive()
                    .filter(p -> p.validUntil() == null || clock.instant().isBefore(p.validUntil()))
                    .concatMap(p -> Mono.zip(PromotionEvaluationSupport.evaluate(p, checkout, lines, promotionRepository, clock),
                            productScopePort.scopeNames(p.scopeType(), p.scopeIds()))
                        .map(t -> new PromotionOfferResult(p.code(), p.title(), p.description(), p.discountType().name(), p.discountValue(),
                            p.maxDiscountAmount(), p.minOrderAmount(), p.scopeType().name(), t.getT2(), p.validUntil(), t.getT1().eligible(), t.getT1().discount(),
                            t.getT1().eligibleSubtotal(), t.getT1().reason(), p.id().equals(checkout.getPromotionId()), p.demo())))
                    .collectList()
                    .map(offers -> offers.stream()
                        .sorted(Comparator.comparing(PromotionOfferResult::eligible).reversed()
                            .thenComparing(PromotionOfferResult::discountAmount, Comparator.reverseOrder())
                            .thenComparing(PromotionOfferResult::code))
                        .toList())));
    }

    public Mono<CheckoutResult> apply(UUID checkoutId, UUID customerId, String rawCode) {
        var code = normalise(rawCode);
        return ownedCheckout(checkoutId, customerId).flatMap(checkout ->
            promotionRepository.findByCode(code)
                .switchIfEmpty(Mono.error(new CheckoutDomainException(CheckoutErrorCode.PROMOTION_NOT_FOUND, "We do not recognise the code " + code)))
                .flatMap(promotion -> PromotionEvaluationSupport.linesOf(checkout, productScopePort)
                    .flatMap(lines -> PromotionEvaluationSupport.evaluate(promotion, checkout, lines, promotionRepository, clock))
                    .flatMap(evaluation -> {
                        if (!evaluation.eligible()) {
                            return Mono.<CheckoutResult>error(PromotionEvaluationSupport.rejection(evaluation));
                        }
                        checkout.applyPromotion(promotion.id(), promotion.code(), evaluation.discount());
                        return checkoutRepository.save(checkout)
                            .doOnSuccess(saved -> log.info("Promotion {} applied to checkout {}: discount {}", promotion.code(), checkoutId, evaluation.discount()))
                            .thenReturn(CheckoutResultMapper.toResult(checkout));
                    })));
    }

    public Mono<CheckoutResult> remove(UUID checkoutId, UUID customerId) {
        return ownedCheckout(checkoutId, customerId).flatMap(checkout -> {
            if (checkout.getPromotionId() == null) {
                return Mono.just(CheckoutResultMapper.toResult(checkout));                      // nothing applied: removing again is a no-op
            }
            checkout.removePromotion();
            return checkoutRepository.save(checkout)
                .doOnSuccess(saved -> log.info("Promotion removed from checkout {}", checkoutId))
                .thenReturn(CheckoutResultMapper.toResult(checkout));
        });
    }

    static String normalise(String rawCode) {
        return rawCode == null ? "" : rawCode.strip().toUpperCase(Locale.ROOT);
    }

    private Mono<Checkout> ownedCheckout(UUID checkoutId, UUID customerId) {
        return checkoutRepository.findById(CheckoutId.fromUUID(checkoutId))
            .switchIfEmpty(Mono.error(new CheckoutDomainException(CheckoutErrorCode.CHECKOUT_NOT_FOUND, "Checkout not found: " + checkoutId)))
            .flatMap(checkout -> checkout.getCustomerId().equals(customerId)
                ? Mono.just(checkout)
                : Mono.error(new CheckoutDomainException(CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER, "Checkout does not belong to customer: " + customerId)));
    }
}
