package com.dsports.review.infrastructure.config;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.model.AddressSnapshot;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.order.domain.order.model.OrderItemId;
import com.dsports.order.domain.order.model.OrderNumber;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.review.application.port.OrderQueryService;
import com.dsports.review.application.port.OrderQueryService.PurchaseVerification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real adapter against order data. The original adapter treated the user ID as an order ID, so every
 * review was rejected; use-case tests could not catch it because they mocked this port.
 */
class ReviewOrderQueryServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private OrderQueryService service;

    private final UUID customer = UUID.randomUUID();
    private final UUID purchasedProduct = UUID.randomUUID();
    private final OrderId orderId = OrderId.generate();

    @BeforeEach
    void setUp() {
        service = new ReviewInfrastructureConfiguration().reviewOrderQueryService(orderRepository);
    }

    @Test
    void deliveredOrderOfTheCustomerContainingTheProductIsEligible() {
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order(OrderStatus.DELIVERED)));

        StepVerifier.create(service.verifyPurchase(orderId.value(), customer, purchasedProduct))
            .expectNext(PurchaseVerification.ELIGIBLE)
            .verifyComplete();
    }

    @Test
    void productThatIsNotInTheOrderIsRejected() {
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order(OrderStatus.DELIVERED)));

        StepVerifier.create(service.verifyPurchase(orderId.value(), customer, UUID.randomUUID()))
            .expectNext(PurchaseVerification.PRODUCT_NOT_IN_ORDER)
            .verifyComplete();
    }

    @Test
    void orderOfAnotherCustomerLooksLikeAMissingOrder() {
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order(OrderStatus.DELIVERED)));

        StepVerifier.create(service.verifyPurchase(orderId.value(), UUID.randomUUID(), purchasedProduct))
            .expectNext(PurchaseVerification.ORDER_NOT_FOUND)
            .verifyComplete();
    }

    @Test
    void undeliveredOrderIsNotYetEligible() {
        when(orderRepository.findById(orderId)).thenReturn(Mono.just(order(OrderStatus.SHIPPED)));

        StepVerifier.create(service.verifyPurchase(orderId.value(), customer, purchasedProduct))
            .expectNext(PurchaseVerification.NOT_DELIVERED)
            .verifyComplete();
    }

    @Test
    void unknownOrderIsRejected() {
        when(orderRepository.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.verifyPurchase(UUID.randomUUID(), customer, purchasedProduct))
            .expectNext(PurchaseVerification.ORDER_NOT_FOUND)
            .verifyComplete();
    }

    private Order order(OrderStatus status) {
        var item = OrderItem.create(OrderItemId.generate(), orderId, purchasedProduct, "Bat", "SKU-1", 1,
            new BigDecimal("100.00"), new BigDecimal("100.00"), null);
        var address = new AddressSnapshot("1 MG Road", null, "Bengaluru", "KA", "IN", "560001", "A B", "999");
        var now = Instant.now();
        return Order.reconstitute(orderId, new OrderNumber("ORD-20261006-000001"), customer, UUID.randomUUID(),
            address, address, List.of(item), status, new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, new BigDecimal("100.00"), "INR", now, null, 1, now, now);
    }
}
