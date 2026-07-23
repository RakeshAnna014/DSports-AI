package com.dsports.order.application.checkout.usecase;

import com.dsports.order.application.checkout.command.CreateCheckoutCommand;
import com.dsports.order.application.checkout.port.CartPort;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.checkout.port.EventPublisher;
import com.dsports.order.application.checkout.port.InventoryPort;
import com.dsports.order.application.checkout.port.PricingPort;
import com.dsports.order.application.checkout.result.CheckoutResult;
import com.dsports.order.application.checkout.result.CheckoutResultMapper;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.CheckoutItem;
import com.dsports.order.domain.checkout.model.CheckoutItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class CreateCheckoutUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateCheckoutUseCase.class);

    private final CheckoutRepository checkoutRepository;
    private final CartPort cartPort;
    private final InventoryPort inventoryPort;
    private final PricingPort pricingPort;
    private final EventPublisher eventPublisher;

    public CreateCheckoutUseCase(CheckoutRepository checkoutRepository, CartPort cartPort,
                                  InventoryPort inventoryPort, PricingPort pricingPort,
                                  EventPublisher eventPublisher) {
        this.checkoutRepository = checkoutRepository;
        this.cartPort = cartPort;
        this.inventoryPort = inventoryPort;
        this.pricingPort = pricingPort;
        this.eventPublisher = eventPublisher;
    }

    public Mono<CheckoutResult> execute(CreateCheckoutCommand command) {
        var customerId = command.customerId();

        return checkoutRepository.existsActiveCheckout(customerId)
            .flatMap(exists -> {
                if (exists) {
                    return Mono.error(new CheckoutDomainException(
                        CheckoutErrorCode.CHECKOUT_ALREADY_TERMINAL,
                        "An active checkout already exists for customer: " + customerId));
                }
                return cartPort.getActiveCart(customerId);
            })
            .switchIfEmpty(Mono.error(new CheckoutDomainException(
                CheckoutErrorCode.CART_NOT_FOUND, "No active cart found for customer: " + customerId)))
            .flatMap(cartData -> {
                if (cartData.items().isEmpty()) {
                    return Mono.error(new CheckoutDomainException(
                        CheckoutErrorCode.CART_EMPTY, "Cart is empty for customer: " + customerId));
                }
                var checkoutId = CheckoutId.generate();
                log.info("Creating checkout {} for customer {}", checkoutId.value(), customerId);

                return validateItems(cartData.items())
                    .then(Mono.fromCallable(() -> buildCheckoutItems(cartData.items(), checkoutId)))
                    .flatMap(items -> {
                        var checkout = Checkout.create(checkoutId, customerId, cartData.cartId(), items);
                        return checkoutRepository.save(checkout)
                            .doOnSuccess(v -> {
                                checkout.getDomainEvents().forEach(eventPublisher::publish);
                                checkout.clearDomainEvents();
                                log.info("Checkout {} created successfully", checkoutId.value());
                            })
                            .thenReturn(CheckoutResultMapper.toResult(checkout));
                    });
            });
    }

    private Mono<Void> validateItems(List<CartPort.CartItemData> items) {
        return Flux.fromIterable(items)
            .flatMap(item -> {
                var invCheck = inventoryPort.checkAvailability(item.productId(), item.quantity())
                    .flatMap(inv -> {
                        if (!inv.sufficient()) {
                            return Mono.error(new CheckoutDomainException(
                                CheckoutErrorCode.ITEM_OUT_OF_STOCK,
                                "Insufficient stock for product: " + item.productId()));
                        }
                        return Mono.empty();
                    });
                var priceCheck = pricingPort.getActivePrice(item.productId())
                    .switchIfEmpty(Mono.error(new CheckoutDomainException(
                        CheckoutErrorCode.PRICE_NOT_FOUND,
                        "No active price for product: " + item.productId())))
                    .then();
                return invCheck.then(priceCheck);
            })
            .then();
    }

    private List<CheckoutItem> buildCheckoutItems(List<CartPort.CartItemData> cartItems, CheckoutId checkoutId) {
        return cartItems.stream()
            .map(cartItem -> {
                var checkoutItemId = CheckoutItemId.generate();
                var lineTotal = cartItem.unitPrice()
                    .multiply(BigDecimal.valueOf(cartItem.quantity()));
                return new CheckoutItem(
                    checkoutItemId, checkoutId,
                    cartItem.productId().toString(), cartItem.productName(),
                    cartItem.sku() != null ? cartItem.sku() : "N/A", cartItem.quantity(),
                    cartItem.unitPrice(), lineTotal,
                    cartItem.imageUrl(), Instant.now()
                );
            })
            .toList();
    }
}
