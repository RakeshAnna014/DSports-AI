package com.dsports.order.infrastructure.checkout.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("checkouts")
public class CheckoutEntity {

    @Id
    private UUID id;

    @Column("customer_id")
    private UUID customerId;

    @Column("cart_id")
    private UUID cartId;

    @Column("status")
    private String status;

    @Column("shipping_address_id")
    private UUID shippingAddressId;

    @Column("delivery_method_code")
    private String deliveryMethodCode;

    @Column("delivery_method_name")
    private String deliveryMethodName;

    @Column("delivery_charge")
    private BigDecimal deliveryCharge;

    @Column("subtotal")
    private BigDecimal subtotal;

    @Column("tax_amount")
    private BigDecimal taxAmount;

    @Column("discount_amount")
    private BigDecimal discountAmount;

    @Column("total_amount")
    private BigDecimal totalAmount;

    @Column("currency")
    private String currency;

    @Column("notes")
    private String notes;

    @Column("expires_at")
    private Instant expiresAt;

    @Column("validated_at")
    private Instant validatedAt;

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
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getCartId() { return cartId; }
    public void setCartId(UUID cartId) { this.cartId = cartId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getShippingAddressId() { return shippingAddressId; }
    public void setShippingAddressId(UUID shippingAddressId) { this.shippingAddressId = shippingAddressId; }
    public String getDeliveryMethodCode() { return deliveryMethodCode; }
    public void setDeliveryMethodCode(String deliveryMethodCode) { this.deliveryMethodCode = deliveryMethodCode; }
    public String getDeliveryMethodName() { return deliveryMethodName; }
    public void setDeliveryMethodName(String deliveryMethodName) { this.deliveryMethodName = deliveryMethodName; }
    public BigDecimal getDeliveryCharge() { return deliveryCharge; }
    public void setDeliveryCharge(BigDecimal deliveryCharge) { this.deliveryCharge = deliveryCharge; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant validatedAt) { this.validatedAt = validatedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
