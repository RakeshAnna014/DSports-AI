package com.dsports.order.domain.model;

import com.dsports.order.domain.order.event.OrderCancelledEvent;
import com.dsports.order.domain.order.event.OrderDeliveredEvent;
import com.dsports.order.domain.order.event.OrderShippedEvent;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.domain.order.model.AddressSnapshot;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.order.domain.order.model.OrderItemId;
import com.dsports.order.domain.order.model.OrderNumber;
import com.dsports.order.domain.order.model.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

    private Order order;

    @BeforeEach
    void setUp() {
        var id = OrderId.generate();
        var item = OrderItem.create(OrderItemId.generate(), id, UUID.randomUUID(), "Bat", "SKU", 1,
            new BigDecimal("100.00"), new BigDecimal("100.00"), null);
        var address = new AddressSnapshot("1 MG Road", null, "Bengaluru", "KA", "IN", "560001", "A B", "999");
        order = Order.place(id, OrderNumber.generate(1), UUID.randomUUID(), UUID.randomUUID(), address, address,
            List.of(item), new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("100.00"), "INR");
        order.markPendingPayment();
        order.clearDomainEvents();
    }

    @Test
    void unpaidOrderCanBeCancelled() {
        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
        assertThat(order.getDomainEvents()).hasAtLeastOneElementOfType(OrderCancelledEvent.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "PROCESSING", "SHIPPED"})
    void paidOrderCannotBeCancelled(OrderStatus paidStatus) {
        advanceTo(paidStatus);

        assertThatThrownBy(order::cancel)
            .isInstanceOf(OrderDomainException.class)
            .extracting(e -> ((OrderDomainException) e).getErrorCode())
            .isEqualTo(OrderErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void cancelledOrderCannotBeCancelledAgain() {
        order.cancel();
        assertThatThrownBy(order::cancel)
            .extracting(e -> ((OrderDomainException) e).getErrorCode())
            .isEqualTo(OrderErrorCode.ORDER_ALREADY_CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "REFUNDED", "PENDING_PAYMENT", "CREATED"})
    void administratorCannotSetPaymentControlledStatuses(OrderStatus status) {
        assertThatThrownBy(() -> order.updateStatus(status))
            .extracting(e -> ((OrderDomainException) e).getErrorCode())
            .isEqualTo(OrderErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void fulfilmentProgressionEmitsEvents() {
        order.confirm();
        order.updateStatus(OrderStatus.PROCESSING);
        order.updateStatus(OrderStatus.SHIPPED);
        order.updateStatus(OrderStatus.DELIVERED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDomainEvents()).hasAtLeastOneElementOfType(OrderShippedEvent.class)
            .hasAtLeastOneElementOfType(OrderDeliveredEvent.class);
    }

    @Test
    void cannotShipBeforePayment() {
        assertThatThrownBy(() -> order.updateStatus(OrderStatus.SHIPPED))
            .isInstanceOf(OrderDomainException.class);
    }

    @Test
    void refundedIsReachableOnlyFromPaidStates() {
        assertThatThrownBy(order::markRefunded).isInstanceOf(OrderDomainException.class);

        order.confirm();
        order.markRefunded();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThatThrownBy(order::markRefunded).isInstanceOf(OrderDomainException.class);
    }

    private void advanceTo(OrderStatus status) {
        order.confirm();
        if (status == OrderStatus.CONFIRMED) {
            return;
        }
        order.process();
        if (status == OrderStatus.PROCESSING) {
            return;
        }
        order.ship();
    }
}
