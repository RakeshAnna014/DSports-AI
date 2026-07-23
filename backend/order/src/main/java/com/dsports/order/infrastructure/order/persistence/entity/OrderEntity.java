package com.dsports.order.infrastructure.order.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("orders")
public class OrderEntity {

    @Id
    private UUID id;

    @Column("order_number")
    private String orderNumber;

    @Column("user_id")
    private UUID userId;

    @Column("checkout_id")
    private UUID checkoutId;

    @Column("status")
    private String status;

    @Column("shipping_address_snapshot")
    private String shippingAddressSnapshot;

    @Column("billing_address_snapshot")
    private String billingAddressSnapshot;

    @Column("subtotal")
    private BigDecimal subtotal;

    @Column("shipping_charge")
    private BigDecimal shippingCharge;

    @Column("tax_amount")
    private BigDecimal taxAmount;

    @Column("discount_amount")
    private BigDecimal discountAmount;

    @Column("grand_total")
    private BigDecimal grandTotal;

    @Column("currency")
    private String currency;

    @Column("placed_at")
    private Instant placedAt;

    @Column("cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column("version")
    private int version;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getCheckoutId() { return checkoutId; }
    public void setCheckoutId(UUID checkoutId) { this.checkoutId = checkoutId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getShippingAddressSnapshot() { return shippingAddressSnapshot; }
    public void setShippingAddressSnapshot(String shippingAddressSnapshot) { this.shippingAddressSnapshot = shippingAddressSnapshot; }
    public String getBillingAddressSnapshot() { return billingAddressSnapshot; }
    public void setBillingAddressSnapshot(String billingAddressSnapshot) { this.billingAddressSnapshot = billingAddressSnapshot; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getShippingCharge() { return shippingCharge; }
    public void setShippingCharge(BigDecimal shippingCharge) { this.shippingCharge = shippingCharge; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Instant getPlacedAt() { return placedAt; }
    public void setPlacedAt(Instant placedAt) { this.placedAt = placedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
