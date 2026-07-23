package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.command.SelectDeliveryMethodCommand;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.result.CheckoutResultMapper;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.DeliveryMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SelectDeliveryMethodUseCase {
    private static final Logger log = LoggerFactory.getLogger(SelectDeliveryMethodUseCase.class);

    private final CheckoutRepository checkoutRepository;
    private final EventPublisher eventPublisher;

    public SelectDeliveryMethodUseCase(CheckoutRepository checkoutRepository, EventPublisher eventPublisher) {
        this.checkoutRepository = checkoutRepository;
        this.eventPublisher = eventPublisher;
    }

    public Mono<CheckoutResult> execute(SelectDeliveryMethodCommand command) {
        var checkoutId = CheckoutId.fromUUID(command.checkoutId());
        var deliveryMethod = DeliveryMethod.fromCode(command.deliveryMethodCode());

        return checkoutRepository.findById(checkoutId)
            .switchIfEmpty(Mono.error(new CheckoutDomainException(
                CheckoutErrorCode.CHECKOUT_NOT_FOUND, "Checkout not found: " + command.checkoutId())))
            .flatMap(checkout -> {
                if (!checkout.getCustomerId().equals(command.customerId())) {
                    return Mono.error(new CheckoutDomainException(
                        CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER,
                        "Checkout does not belong to customer: " + command.customerId()));
                }
                checkout.selectDeliveryMethod(deliveryMethod);
                return checkoutRepository.save(checkout)
                    .doOnSuccess(v -> {
                        checkout.getDomainEvents().forEach(eventPublisher::publish);
                        checkout.clearDomainEvents();
                        log.info("Delivery method {} selected for checkout {}", command.deliveryMethodCode(), checkoutId.value());
                    })
                    .thenReturn(CheckoutResultMapper.toResult(checkout));
            });
    }
}
