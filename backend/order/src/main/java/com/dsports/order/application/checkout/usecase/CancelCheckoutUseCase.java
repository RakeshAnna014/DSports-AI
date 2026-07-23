package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.result.CheckoutResultMapper;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.CheckoutId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
public class CancelCheckoutUseCase {
    private static final Logger log = LoggerFactory.getLogger(CancelCheckoutUseCase.class);

    private final CheckoutRepository checkoutRepository;
    private final EventPublisher eventPublisher;

    public CancelCheckoutUseCase(CheckoutRepository checkoutRepository, EventPublisher eventPublisher) {
        this.checkoutRepository = checkoutRepository;
        this.eventPublisher = eventPublisher;
    }

    public Mono<CheckoutResult> execute(UUID checkoutId, UUID customerId) {
        var id = CheckoutId.fromUUID(checkoutId);

        return checkoutRepository.findById(id)
            .switchIfEmpty(Mono.error(new CheckoutDomainException(
                CheckoutErrorCode.CHECKOUT_NOT_FOUND, "Checkout not found: " + checkoutId)))
            .flatMap(checkout -> {
                if (!checkout.getCustomerId().equals(customerId)) {
                    return Mono.error(new CheckoutDomainException(
                        CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER,
                        "Checkout does not belong to customer: " + customerId));
                }
                checkout.cancel();
                return checkoutRepository.save(checkout)
                    .doOnSuccess(v -> {
                        checkout.getDomainEvents().forEach(eventPublisher::publish);
                        checkout.clearDomainEvents();
                        log.info("Checkout {} cancelled", checkoutId);
                    })
                    .thenReturn(CheckoutResultMapper.toResult(checkout));
            });
    }
}
