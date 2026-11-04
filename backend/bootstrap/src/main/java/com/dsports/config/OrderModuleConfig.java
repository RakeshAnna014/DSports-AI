package com.dsports.config;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.domain.model.CartId;
import com.dsports.identity.application.result.AddressResult;
import com.dsports.identity.application.result.CustomerProfileResult;
import com.dsports.identity.application.usecase.GetAddressesUseCase;
import com.dsports.identity.application.usecase.GetCustomerProfileUseCase;
import com.dsports.identity.domain.model.UserId;
import com.dsports.inventory.application.usecase.ReleaseOrderReservationsUseCase;
import com.dsports.inventory.application.usecase.ReserveStockForOrderUseCase;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.order.port.CartCheckoutPort;
import com.dsports.order.application.order.port.CheckoutDataPort;
import com.dsports.order.application.order.port.EventPublisher;
import com.dsports.order.application.order.port.InventoryReservationPort;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.port.PaymentVoidPort;
import com.dsports.order.application.order.usecase.CancelOrderUseCase;
import com.dsports.order.application.order.usecase.ExpireUnpaidOrdersUseCase;
import com.dsports.order.application.order.usecase.GetOrderUseCase;
import com.dsports.order.application.order.usecase.GetOrdersUseCase;
import com.dsports.order.application.order.usecase.PlaceOrderUseCase;
import com.dsports.order.application.order.usecase.UpdateOrderStatusUseCase;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.CheckoutStatus;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Configuration
public class OrderModuleConfig {

    @Bean
    public com.dsports.order.application.order.port.CoPurchasePort coPurchasePort(org.springframework.r2dbc.core.DatabaseClient databaseClient) {
        return new com.dsports.order.infrastructure.order.persistence.repository.CoPurchaseR2dbcAdapter(databaseClient);
    }

    @Bean
    public com.dsports.order.application.order.usecase.GetCoPurchasesUseCase getCoPurchasesUseCase(
            com.dsports.order.application.order.port.CoPurchasePort port, @Value("${app.recommendations.copurchase-min-orders:2}") int minOrders) {
        return new com.dsports.order.application.order.usecase.GetCoPurchasesUseCase(port, minOrders);
    }

    @Bean
    public com.dsports.order.application.order.port.SalesFeedPort salesFeedPort(org.springframework.r2dbc.core.DatabaseClient databaseClient) {
        return new com.dsports.order.infrastructure.order.persistence.repository.SalesFeedR2dbcAdapter(databaseClient);
    }

    @Bean
    public com.dsports.order.application.order.usecase.GetSalesFeedUseCase getSalesFeedUseCase(com.dsports.order.application.order.port.SalesFeedPort port,
                                                                                               com.dsports.order.application.order.port.ApprovedReturnsPort returns) {
        return new com.dsports.order.application.order.usecase.GetSalesFeedUseCase(port, returns);
    }

    @Bean
    public com.dsports.order.application.promotion.port.PromotionRepository promotionRepository(org.springframework.r2dbc.core.DatabaseClient databaseClient) {
        return new com.dsports.order.infrastructure.promotion.persistence.PromotionR2dbcAdapter(databaseClient);
    }

    @Bean
    public com.dsports.order.application.promotion.port.ProductScopePort productScopePort(org.springframework.r2dbc.core.DatabaseClient databaseClient) {
        return new com.dsports.order.infrastructure.promotion.persistence.ProductScopeR2dbcAdapter(databaseClient);
    }

    @Bean
    public CartCheckoutPort cartCheckoutPort(CartRepository cartRepository) {
        return cartId -> cartRepository.findById(CartId.fromUUID(cartId))
            .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.CART_NOT_FOUND,
                "Cart not found for checkout: " + cartId)))
            .flatMap(cart -> {
                cart.checkout();
                return cartRepository.save(cart);
            });
    }

    @Bean
    public CheckoutDataPort checkoutDataPort(CheckoutRepository checkoutRepository,
                                              GetAddressesUseCase getAddressesUseCase,
                                              GetCustomerProfileUseCase getCustomerProfileUseCase,
                                              com.dsports.order.application.promotion.usecase.RedeemPromotionUseCase redeemPromotionUseCase) {
        return new CheckoutDataPort() {
            @Override
            public Mono<CheckoutData> getValidatedCheckout(UUID checkoutId, UUID userId) {
                return checkoutRepository.findById(CheckoutId.fromUUID(checkoutId))
                    .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_FOUND,
                        "Checkout not found: " + checkoutId)))
                    .flatMap(checkout -> {
                        if (!checkout.getCustomerId().equals(userId)) {
                            return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_FOUND,
                                "Checkout not found: " + checkoutId));
                        }
                        if (checkout.getStatus() != CheckoutStatus.VALIDATED
                            && checkout.getStatus() != CheckoutStatus.READY_FOR_PAYMENT) {
                            return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_VALIDATED,
                                "Checkout " + checkoutId + " has not been validated. Current status: " + checkout.getStatus()));
                        }
                        if (checkout.isExpired()) {
                            return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_EXPIRED,
                                "Checkout " + checkoutId + " expired at " + checkout.getExpiresAt() + "; start a new checkout"));
                        }
                        var shippingAddressId = checkout.getShippingAddressId();
                        if (shippingAddressId == null) {
                            return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_VALIDATED,
                                "Checkout " + checkoutId + " has no shipping address selected"));
                        }

                        var checkoutItems = checkout.getItems().stream()
                            .map(item -> new CheckoutDataPort.CheckoutItemData(
                                UUID.fromString(item.getProductId()),
                                item.getProductName(),
                                item.getSku(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getLineTotal(),
                                item.getImageUrl()))
                            .toList();

                        var customerId = UserId.fromUUID(userId);
                        return Mono.zip(getAddressesUseCase.execute(customerId), getCustomerProfileUseCase.execute(customerId))
                            .flatMap(tuple -> {
                                var shippingAddress = tuple.getT1().addresses().stream()
                                    .filter(a -> a.addressId().equals(shippingAddressId))
                                    .findFirst();
                                if (shippingAddress.isEmpty()) {
                                    return Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_VALIDATED,
                                        "The selected shipping address no longer exists"));
                                }
                                return Mono.just(new CheckoutDataPort.CheckoutData(
                                    checkout.getId().value(),
                                    checkout.getCartId(),
                                    shippingAddressId,
                                    null,
                                    checkoutItems,
                                    checkout.getSubtotal(),
                                    checkout.getDeliveryCharge(),
                                    checkout.getTaxAmount(),
                                    checkout.getDiscountAmount(),
                                    checkout.getTotalAmount(),
                                    checkout.getCurrency(),
                                    toAddressData(shippingAddress.get(), tuple.getT2()),
                                    null,
                                    checkout.getPromotionCode()));
                            });
                    });
            }

            @Override
            public Mono<Void> completeCheckout(UUID checkoutId) {
                return checkoutRepository.findById(CheckoutId.fromUUID(checkoutId))
                    .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.CHECKOUT_NOT_FOUND,
                        "Checkout not found: " + checkoutId)))
                    .flatMap(checkout -> redeemPromotionUseCase.redeem(checkoutId).then(Mono.defer(() -> {
                        checkout.complete();
                        checkout.clearDomainEvents();
                        return checkoutRepository.save(checkout).then();
                    })));
            }
        };
    }

    @Bean
    public InventoryReservationPort inventoryReservationPort(ReserveStockForOrderUseCase reserveStockForOrderUseCase,
                                                             ReleaseOrderReservationsUseCase releaseOrderReservationsUseCase) {
        return new InventoryReservationPort() {
            @Override
            public Mono<Void> reserve(UUID orderId, java.util.List<ReservationItem> items) {
                var lines = items.stream()
                    .map(item -> new ReserveStockForOrderUseCase.Line(item.productId(), item.quantity()))
                    .toList();
                return reserveStockForOrderUseCase.execute(orderId, lines);
            }

            @Override
            public Mono<Void> release(UUID orderId) {
                return releaseOrderReservationsUseCase.execute(orderId);
            }
        };
    }

    /** Cancels a cancelled order's uncaptured payments; a capture in flight blocks the cancellation. */
    @Bean
    public PaymentVoidPort paymentVoidPort(PaymentRepository paymentRepository, OutboxWriter outboxWriter) {
        return orderId -> paymentRepository.findByOrderId(orderId)
            .filter(payment -> payment.getStatus().isOpen())
            .concatMap(payment -> {
                if (payment.isCaptureInProgress()) {
                    return Mono.<Payment>error(new OrderDomainException(OrderErrorCode.PAYMENT_IN_PROGRESS,
                        "A payment for this order is being captured; try again shortly"));
                }
                return CancelPaymentUseCase.voidPayment(payment, paymentRepository, outboxWriter)
                    .doOnNext(Payment::clearDomainEvents);
            })
            .then();
    }

    @Bean
    public PlaceOrderUseCase placeOrderUseCase(OrderRepository orderRepository,
                                                CheckoutDataPort checkoutDataPort,
                                                CartCheckoutPort cartCheckoutPort,
                                                InventoryReservationPort inventoryReservationPort,
                                                EventPublisher eventPublisher,
                                                TransactionRunner transactionRunner,
                                                OutboxWriter outboxWriter) {
        return new PlaceOrderUseCase(orderRepository, checkoutDataPort, cartCheckoutPort,
            inventoryReservationPort, eventPublisher, transactionRunner, outboxWriter);
    }

    @Bean
    public CancelOrderUseCase cancelOrderUseCase(OrderRepository orderRepository,
                                                  EventPublisher eventPublisher,
                                                  InventoryReservationPort inventoryReservationPort,
                                                  PaymentVoidPort paymentVoidPort,
                                                  TransactionRunner transactionRunner,
                                                  OutboxWriter outboxWriter) {
        return new CancelOrderUseCase(orderRepository, eventPublisher, inventoryReservationPort, paymentVoidPort,
            transactionRunner, outboxWriter);
    }

    @Bean
    public ExpireUnpaidOrdersUseCase expireUnpaidOrdersUseCase(OrderRepository orderRepository,
                                                               CancelOrderUseCase cancelOrderUseCase,
                                                               @Value("${app.orders.payment-window:PT30M}") Duration paymentWindow) {
        return new ExpireUnpaidOrdersUseCase(orderRepository, cancelOrderUseCase, paymentWindow);
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
                                                              EventPublisher eventPublisher,
                                                              CancelOrderUseCase cancelOrderUseCase,
                                                              TransactionRunner transactionRunner,
                                                              OutboxWriter outboxWriter) {
        return new UpdateOrderStatusUseCase(orderRepository, eventPublisher, cancelOrderUseCase,
            transactionRunner, outboxWriter);
    }

    private static CheckoutDataPort.AddressData toAddressData(AddressResult address, CustomerProfileResult profile) {
        var fullName = (nullToEmpty(profile.firstName()) + " " + nullToEmpty(profile.lastName())).trim();
        return new CheckoutDataPort.AddressData(
            address.line1(),
            address.line2(),
            address.city(),
            address.state(),
            address.country(),
            address.postalCode(),
            fullName,
            nullToEmpty(profile.phoneNumber()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
