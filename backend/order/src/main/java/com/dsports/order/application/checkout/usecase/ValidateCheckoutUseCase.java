package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.order.application.checkout.port.InventoryPort;
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
public class ValidateCheckoutUseCase {
    private static final Logger log = LoggerFactory.getLogger(ValidateCheckoutUseCase.class);

    private final CheckoutRepository checkoutRepository;
    private final InventoryPort inventoryPort;
    private final EventPublisher eventPublisher;

    public ValidateCheckoutUseCase(CheckoutRepository checkoutRepository,
                                    InventoryPort inventoryPort, EventPublisher eventPublisher) {
        this.checkoutRepository = checkoutRepository;
        this.inventoryPort = inventoryPort;
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
                var items = checkout.getItems();
                return Mono.when(
                    items.stream()
                        .map(item -> inventoryPort.checkAvailability(
                            UUID.fromString(item.getProductId()), item.getQuantity())
                            .flatMap(result -> {
                                if (!result.sufficient()) {
                                    return Mono.error(new CheckoutDomainException(
                                        CheckoutErrorCode.ITEM_OUT_OF_STOCK,
                                        "Insufficient stock for product: " + item.getProductName()));
                                }
                                return Mono.empty();
                            }))
                        .toList()
                ).then(Mono.fromRunnable(() -> {
                    checkout.validate();
                    log.info("Checkout {} validated with total {}", checkoutId, checkout.getTotalAmount());
                }))
                .thenReturn(checkout);
            })
            .flatMap(validatedCheckout -> checkoutRepository.findById(validatedCheckout.getId()))
            .flatMap(checkout -> checkoutRepository.save(checkout)
                .doOnSuccess(v -> {
                    checkout.getDomainEvents().forEach(eventPublisher::publish);
                    checkout.clearDomainEvents();
                })
                .thenReturn(CheckoutResultMapper.toResult(checkout)));
    }
}
