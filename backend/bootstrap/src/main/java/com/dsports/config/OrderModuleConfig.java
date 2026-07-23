package com.dsports.config;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.domain.model.CartId;
import com.dsports.identity.application.result.AddressResult;
import com.dsports.identity.application.usecase.GetAddressesUseCase;
import com.dsports.identity.domain.model.UserId;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.order.port.CartCheckoutPort;
import com.dsports.order.application.order.port.CheckoutDataPort;
import com.dsports.order.application.order.port.InventoryReservationPort;
import com.dsports.order.application.order.usecase.CancelOrderUseCase;
import com.dsports.order.application.order.usecase.GetOrderUseCase;
import com.dsports.order.application.order.usecase.GetOrdersUseCase;
import com.dsports.order.application.order.usecase.PlaceOrderUseCase;
import com.dsports.order.application.order.usecase.UpdateOrderStatusUseCase;
import com.dsports.order.application.order.port.EventPublisher;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.CheckoutStatus;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class OrderModuleConfig {

    @Bean
    public CartCheckoutPort cartCheckoutPort(CartRepository cartRepository) {
        return cartId -> cartRepository.findById(CartId.fromUUID(cartId))
            .flatMap(cart -> {
                cart.checkout();
                return cartRepository.save(cart);
            });
    }

    @Bean
    public CheckoutDataPort checkoutDataPort(CheckoutRepository checkoutRepository,
                                              GetAddressesUseCase getAddressesUseCase) {
        return (UUID checkoutId, UUID userId) ->
            checkoutRepository.findById(CheckoutId.fromUUID(checkoutId))
                .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_FOUND,
                    "Checkout not found: " + checkoutId)))
                .flatMap(checkout -> {
                    if (!checkout.getCustomerId().equals(userId)) {
                        return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_FOUND,
                            "Checkout not found for user: " + userId));
                    }
                    if (checkout.getStatus() != CheckoutStatus.VALIDATED &&
                        checkout.getStatus() != CheckoutStatus.READY_FOR_PAYMENT) {
                        return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_VALIDATED,
                            "Checkout " + checkoutId + " has not been validated. Current status: " + checkout.getStatus()));
                    }

                    var checkoutItems = checkout.getItems().stream()
                        .map(item -> new CheckoutDataPort.CheckoutItemData(
                            UUID.fromString(item.getProductId()),
                            item.getProductName(),
                            item.getSku(),
                            item.getQuantity(),
                            item.getUnitPrice(),
                            item.getLineTotal(),
                            item.getImageUrl()
                        ))
                        .toList();

                    var shippingAddressId = checkout.getShippingAddressId();

                    if (shippingAddressId == null) {
                        return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_VALIDATED,
                            "Checkout " + checkoutId + " has no shipping address selected"));
                    }

                    return getAddressesUseCase.execute(UserId.fromUUID(userId))
                        .flatMap(addressList -> {
                            var shippingAddr = addressList.addresses().stream()
                                .filter(a -> a.addressId().equals(shippingAddressId))
                                .findFirst();

                            if (shippingAddr.isEmpty()) {
                                return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_VALIDATED,
                                    "Shipping address not found for checkout"));
                            }

                            var addr = shippingAddr.get();
                            return Mono.just(new CheckoutDataPort.CheckoutData(
                                checkout.getId().value(),
                                checkout.getCartId(),
                                checkout.getShippingAddressId(),
                                null,
                                checkoutItems,
                                checkout.getSubtotal(),
                                checkout.getDeliveryCharge(),
                                checkout.getTaxAmount(),
                                checkout.getDiscountAmount(),
                                checkout.getTotalAmount(),
                                checkout.getCurrency(),
                                toAddressData(addr, ""),
                                null
                            ));
                        });
                });
    }

    @Bean
    public InventoryReservationPort inventoryReservationPort(
            com.dsports.order.application.checkout.port.InventoryPort inventoryPort) {
        return items -> {
            if (items.isEmpty()) return Mono.empty();
            return reactor.core.publisher.Flux.fromIterable(items)
                .flatMap(item -> inventoryPort.checkAvailability(item.productId(), item.quantity())
                    .flatMap(result -> {
                        if (!result.sufficient()) {
                            return Mono.error(new OrderDomainException(OrderErrorCode.INSUFFICIENT_STOCK,
                                "Insufficient stock for product " + item.productId()
                                    + ". Requested: " + item.quantity()
                                    + ", Available: " + result.availableQuantity()));
                        }
                        return Mono.empty();
                    }))
                .then();
        };
    }

    @Bean
    public PlaceOrderUseCase placeOrderUseCase(OrderRepository orderRepository,
                                                CheckoutDataPort checkoutDataPort,
                                                CartCheckoutPort cartCheckoutPort,
                                                InventoryReservationPort inventoryReservationPort,
                                                EventPublisher eventPublisher) {
        return new PlaceOrderUseCase(orderRepository, checkoutDataPort,
            cartCheckoutPort, inventoryReservationPort, eventPublisher);
    }

    @Bean
    public CancelOrderUseCase cancelOrderUseCase(OrderRepository orderRepository,
                                                   EventPublisher eventPublisher) {
        return new CancelOrderUseCase(orderRepository, eventPublisher);
    }

    @Bean
    public GetOrderUseCase getOrderUseCase(OrderRepository orderRepository) {
        return new GetOrderUseCase(orderRepository);
    }

    @Bean
    public GetOrdersUseCase getOrdersUseCase(OrderRepository orderRepository) {
        return new GetOrdersUseCase(orderRepository);
    }

    @Bean
    public UpdateOrderStatusUseCase updateOrderStatusUseCase(OrderRepository orderRepository,
                                                              EventPublisher eventPublisher) {
        return new UpdateOrderStatusUseCase(orderRepository, eventPublisher);
    }

    private static CheckoutDataPort.AddressData toAddressData(AddressResult addr, String phone) {
        return new CheckoutDataPort.AddressData(
            addr.line1(),
            addr.line2(),
            addr.city(),
            addr.state(),
            addr.country(),
            addr.postalCode(),
            "",
            phone
        );
    }
}
