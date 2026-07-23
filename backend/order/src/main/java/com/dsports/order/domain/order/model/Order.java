package com.dsports.order.domain.order.model;

import com.dsports.order.domain.order.event.OrderCancelledEvent;
import com.dsports.order.domain.order.event.OrderConfirmedEvent;
import com.dsports.order.domain.order.event.OrderDeliveredEvent;
import com.dsports.order.domain.order.event.OrderPlacedEvent;
import com.dsports.order.domain.order.event.OrderShippedEvent;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Order {
    private final OrderId id;
    private OrderNumber orderNumber;
    private final UUID userId;
    private final UUID checkoutId;
    private AddressSnapshot shippingAddressSnapshot;
    private AddressSnapshot billingAddressSnapshot;
    private final List<OrderItem> items;
    private OrderStatus status;
    private BigDecimal subtotal;
    private BigDecimal shippingCharge;
    private BigDecimal taxAmount;
    private BigDecimal discountAmount;
    private BigDecimal grandTotal;
    private String currency;
    private Instant placedAt;
    private Instant cancelledAt;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Order(OrderId id, OrderNumber orderNumber, UUID userId, UUID checkoutId,
                  AddressSnapshot shippingAddressSnapshot, AddressSnapshot billingAddressSnapshot,
                  List<OrderItem> items, OrderStatus status,
                  BigDecimal subtotal, BigDecimal shippingCharge, BigDecimal taxAmount,
                  BigDecimal discountAmount, BigDecimal grandTotal, String currency,
                  Instant placedAt, Instant cancelledAt,
                  int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.orderNumber = orderNumber;
        this.userId = Objects.requireNonNull(userId);
        this.checkoutId = Objects.requireNonNull(checkoutId);
        this.shippingAddressSnapshot = shippingAddressSnapshot;
        this.billingAddressSnapshot = billingAddressSnapshot;
        this.items = new ArrayList<>(items);
        this.status = Objects.requireNonNull(status);
        this.subtotal = subtotal;
        this.shippingCharge = shippingCharge;
        this.taxAmount = taxAmount;
        this.discountAmount = discountAmount;
        this.grandTotal = grandTotal;
        this.currency = currency;
        this.placedAt = placedAt;
        this.cancelledAt = cancelledAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Order place(OrderId id, OrderNumber orderNumber, UUID userId, UUID checkoutId,
                               AddressSnapshot shippingAddress, AddressSnapshot billingAddress,
                               List<OrderItem> items, BigDecimal subtotal, BigDecimal shippingCharge,
                               BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal grandTotal,
                               String currency) {
        if (items.isEmpty()) {
            throw new OrderDomainException(OrderErrorCode.ORDER_EMPTY,
                "Cannot place an order with no items");
        }
        var now = Instant.now();
        var order = new Order(id, orderNumber, userId, checkoutId,
            shippingAddress, billingAddress, items, OrderStatus.CREATED,
            subtotal, shippingCharge, taxAmount, discountAmount, grandTotal, currency,
            now, null, 0, now, now);
        order.domainEvents.add(new OrderPlacedEvent(id, orderNumber, userId, grandTotal, currency));
        return order;
    }

    public static Order reconstitute(OrderId id, OrderNumber orderNumber, UUID userId, UUID checkoutId,
                                      AddressSnapshot shippingAddressSnapshot,
                                      AddressSnapshot billingAddressSnapshot,
                                      List<OrderItem> items, OrderStatus status,
                                      BigDecimal subtotal, BigDecimal shippingCharge,
                                      BigDecimal taxAmount, BigDecimal discountAmount,
                                      BigDecimal grandTotal, String currency,
                                      Instant placedAt, Instant cancelledAt,
                                      int version, Instant createdAt, Instant updatedAt) {
        return new Order(id, orderNumber, userId, checkoutId,
            shippingAddressSnapshot, billingAddressSnapshot, items, status,
            subtotal, shippingCharge, taxAmount, discountAmount, grandTotal, currency,
            placedAt, cancelledAt, version, createdAt, updatedAt);
    }

    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
        this.updatedAt = Instant.now();
        domainEvents.add(new OrderConfirmedEvent(id, userId));
    }

    public void process() {
        ensureNotDeliveredOrCancelled();
        transitionTo(OrderStatus.PROCESSING);
        this.updatedAt = Instant.now();
    }

    public void ship() {
        ensureNotDeliveredOrCancelled();
        transitionTo(OrderStatus.SHIPPED);
        this.updatedAt = Instant.now();
        domainEvents.add(new OrderShippedEvent(id, userId));
    }

    public void deliver() {
        if (status == OrderStatus.DELIVERED) {
            throw new OrderDomainException(OrderErrorCode.ORDER_ALREADY_DELIVERED,
                "Order is already delivered");
        }
        ensureNotCancelled();
        transitionTo(OrderStatus.DELIVERED);
        this.updatedAt = Instant.now();
        domainEvents.add(new OrderDeliveredEvent(id, userId));
    }

    public void cancel() {
        if (status == OrderStatus.DELIVERED) {
            throw new OrderDomainException(OrderErrorCode.ORDER_ALREADY_DELIVERED,
                "Delivered orders cannot be cancelled");
        }
        if (status == OrderStatus.CANCELLED) {
            throw new OrderDomainException(OrderErrorCode.ORDER_ALREADY_CANCELLED,
                "Order is already cancelled");
        }
        if (status.isTerminal()) {
            throw new OrderDomainException(OrderErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot cancel order in terminal state: " + status);
        }
        transitionTo(OrderStatus.CANCELLED);
        this.cancelledAt = Instant.now();
        this.updatedAt = Instant.now();
        domainEvents.add(new OrderCancelledEvent(id, userId));
    }

    public void updateStatus(OrderStatus newStatus) {
        ensureNotCancelled();
        if (status == OrderStatus.DELIVERED) {
            throw new OrderDomainException(OrderErrorCode.ORDER_ALREADY_DELIVERED,
                "Delivered orders cannot be modified");
        }
        transitionTo(newStatus);
        this.updatedAt = Instant.now();
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new OrderDomainException(OrderErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot transition from " + status + " to " + target);
        }
        this.status = target;
    }

    private void ensureNotCancelled() {
        if (status == OrderStatus.CANCELLED) {
            throw new OrderDomainException(OrderErrorCode.ORDER_ALREADY_CANCELLED,
                "Order is cancelled");
        }
    }

    private void ensureNotDeliveredOrCancelled() {
        if (status == OrderStatus.DELIVERED) {
            throw new OrderDomainException(OrderErrorCode.ORDER_ALREADY_DELIVERED,
                "Delivered orders cannot be modified");
        }
        if (status == OrderStatus.CANCELLED) {
            throw new OrderDomainException(OrderErrorCode.ORDER_ALREADY_CANCELLED,
                "Cancelled orders cannot be modified");
        }
    }

    public OrderId getId() { return id; }
    public OrderNumber getOrderNumber() { return orderNumber; }
    public UUID getUserId() { return userId; }
    public UUID getCheckoutId() { return checkoutId; }
    public AddressSnapshot getShippingAddressSnapshot() { return shippingAddressSnapshot; }
    public AddressSnapshot getBillingAddressSnapshot() { return billingAddressSnapshot; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getShippingCharge() { return shippingCharge; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public String getCurrency() { return currency; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<DomainEvent> getDomainEvents() { return List.copyOf(domainEvents); }
    public void clearDomainEvents() { domainEvents.clear(); }
}
