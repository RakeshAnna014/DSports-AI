package com.dsports.order.domain.order.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class OrderItem {
    private final OrderItemId id;
    private final OrderId orderId;
    private final UUID productId;
    private final String productName;
    private final String sku;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal lineTotal;
    private final String productImage;
    private final Instant createdAt;

    public OrderItem(OrderItemId id, OrderId orderId, UUID productId, String productName,
                     String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                     String productImage, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.orderId = Objects.requireNonNull(orderId);
        this.productId = Objects.requireNonNull(productId);
        this.productName = Objects.requireNonNull(productName);
        this.sku = Objects.requireNonNull(sku);
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.lineTotal = Objects.requireNonNull(lineTotal);
        this.productImage = productImage;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static OrderItem create(OrderItemId id, OrderId orderId, UUID productId,
                                    String productName, String sku, int quantity,
                                    BigDecimal unitPrice, BigDecimal lineTotal,
                                    String productImage) {
        return new OrderItem(id, orderId, productId, productName, sku, quantity,
            unitPrice, lineTotal, productImage, Instant.now());
    }

    public OrderItemId getId() { return id; }
    public OrderId getOrderId() { return orderId; }
    public UUID getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public String getProductImage() { return productImage; }
    public Instant getCreatedAt() { return createdAt; }
}
