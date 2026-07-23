package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.result.CheckoutResultMapper;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.CheckoutId;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class GetCheckoutUseCase {
    private final CheckoutRepository checkoutRepository;

    public GetCheckoutUseCase(CheckoutRepository checkoutRepository) {
        this.checkoutRepository = checkoutRepository;
    }

    public Mono<CheckoutResult> execute(UUID checkoutId, UUID customerId) {
        return checkoutRepository.findById(CheckoutId.fromUUID(checkoutId))
            .switchIfEmpty(Mono.error(new CheckoutDomainException(
                CheckoutErrorCode.CHECKOUT_NOT_FOUND, "Checkout not found: " + checkoutId)))
            .flatMap(checkout -> {
                if (!checkout.getCustomerId().equals(customerId)) {
                    return Mono.error(new CheckoutDomainException(
                        CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER,
                        "Checkout does not belong to customer: " + customerId));
                }
                return Mono.just(CheckoutResultMapper.toResult(checkout));
            });
    }

    public Mono<CheckoutResult> getActiveCheckout(UUID customerId) {
        return checkoutRepository.findByCustomerId(customerId)
            .map(CheckoutResultMapper::toResult);
    }
}
