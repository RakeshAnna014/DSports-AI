package com.dsports.order.domain.checkout.model;

import com.dsports.order.domain.checkout.event.CheckoutCreatedEvent;
import com.dsports.order.domain.checkout.event.CheckoutDeliveryMethodSelectedEvent;
import com.dsports.order.domain.checkout.event.CheckoutExpiredEvent;
import com.dsports.order.domain.checkout.event.CheckoutReadyForPaymentEvent;
import com.dsports.order.domain.checkout.event.CheckoutShippingAddressSelectedEvent;
import com.dsports.order.domain.checkout.event.CheckoutValidatedEvent;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Checkout {
    private static final BigDecimal TAX_RATE = new BigDecimal("0.18");
    private static final long DEFAULT_EXPIRY_MINUTES = 30;

    private final CheckoutId id;
    private final UUID customerId;
    private final UUID cartId;
    private CheckoutStatus status;
    private UUID shippingAddressId;
    private DeliveryMethod deliveryMethod;
    private final List<CheckoutItem> items;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal deliveryCharge;
    private BigDecimal discountAmount;
    private UUID promotionId;
    private String promotionCode;
    private BigDecimal totalAmount;
    private String currency;
    private String notes;
    private Instant expiresAt;
    private Instant validatedAt;
    private Instant cancelledAt;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Checkout(CheckoutId id, UUID customerId, UUID cartId, CheckoutStatus status,
                     UUID shippingAddressId, DeliveryMethod deliveryMethod, List<CheckoutItem> items,
                     BigDecimal subtotal, BigDecimal taxAmount, BigDecimal deliveryCharge,
                     BigDecimal discountAmount, BigDecimal totalAmount, String currency, String notes,
                     Instant expiresAt, Instant validatedAt, Instant cancelledAt,
                     int version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.cartId = cartId;
        this.status = status;
        this.shippingAddressId = shippingAddressId;
        this.deliveryMethod = deliveryMethod;
        this.items = new ArrayList<>(items);
        this.subtotal = subtotal;
        this.taxAmount = taxAmount;
        this.deliveryCharge = deliveryCharge;
        this.discountAmount = discountAmount;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.notes = notes;
        this.expiresAt = expiresAt;
        this.validatedAt = validatedAt;
        this.cancelledAt = cancelledAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Checkout create(CheckoutId id, UUID customerId, UUID cartId, List<CheckoutItem> items) {
        var now = Instant.now();
        var expiresAt = now.plusSeconds(DEFAULT_EXPIRY_MINUTES * 60);
        var subtotal = items.stream()
            .map(CheckoutItem::getLineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        var taxAmount = subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
        var grandTotal = subtotal.add(taxAmount).setScale(2, RoundingMode.HALF_UP);
        var checkout = new Checkout(id, customerId, cartId, CheckoutStatus.PENDING,
            null, null, items, subtotal, taxAmount, BigDecimal.ZERO, BigDecimal.ZERO,
            grandTotal, "INR", null, expiresAt,
            null, null, 0, now, now);
        checkout.domainEvents.add(new CheckoutCreatedEvent(id, customerId, cartId));
        return checkout;
    }

    public static Checkout reconstitute(CheckoutId id, UUID customerId, UUID cartId,
                                         CheckoutStatus status, UUID shippingAddressId,
                                         DeliveryMethod deliveryMethod, List<CheckoutItem> items,
                                         BigDecimal subtotal, BigDecimal taxAmount,
                                         BigDecimal deliveryCharge, BigDecimal discountAmount,
                                         BigDecimal totalAmount, String currency, String notes,
                                         Instant expiresAt, Instant validatedAt, Instant cancelledAt,
                                         int version, Instant createdAt, Instant updatedAt) {
        return new Checkout(id, customerId, cartId, status, shippingAddressId, deliveryMethod,
            items, subtotal, taxAmount, deliveryCharge, discountAmount, totalAmount, currency, notes,
            expiresAt, validatedAt, cancelledAt, version, createdAt, updatedAt);
    }

    /** Same as the other overload, plus the promotion that is applied to the checkout (both null when none). */
    public static Checkout reconstitute(CheckoutId id, UUID customerId, UUID cartId,
                                         CheckoutStatus status, UUID shippingAddressId,
                                         DeliveryMethod deliveryMethod, List<CheckoutItem> items,
                                         BigDecimal subtotal, BigDecimal taxAmount,
                                         BigDecimal deliveryCharge, BigDecimal discountAmount,
                                         BigDecimal totalAmount, String currency, String notes,
                                         Instant expiresAt, Instant validatedAt, Instant cancelledAt,
                                         int version, Instant createdAt, Instant updatedAt,
                                         UUID promotionId, String promotionCode) {
        var checkout = reconstitute(id, customerId, cartId, status, shippingAddressId, deliveryMethod, items, subtotal, taxAmount,
            deliveryCharge, discountAmount, totalAmount, currency, notes, expiresAt, validatedAt, cancelledAt, version, createdAt, updatedAt);
        checkout.promotionId = promotionId;
        checkout.promotionCode = promotionCode;
        return checkout;
    }

    /**
     * Applies a promotion whose discount the caller computed from authoritative rules. One promotion per checkout: applying the
     * same one again is a no-op that refreshes the amount, a different one must be removed first. Only an unvalidated
     * (PENDING) checkout can change, so a validated total never moves underneath the customer.
     */
    public void applyPromotion(UUID promotionId, String code, BigDecimal discount) {
        ensureNotTerminal();
        ensurePromotionsEditable();
        if (this.promotionId != null && !this.promotionId.equals(promotionId)) {
            throw new CheckoutDomainException(CheckoutErrorCode.PROMOTION_ALREADY_APPLIED,
                "Promotion " + this.promotionCode + " is already applied; remove it before applying another");
        }
        if (discount.signum() < 0 || discount.compareTo(subtotal) > 0) {
            throw new CheckoutDomainException(CheckoutErrorCode.PROMOTION_NOT_APPLICABLE, "The discount is outside the order subtotal");
        }
        this.promotionId = Objects.requireNonNull(promotionId);
        this.promotionCode = Objects.requireNonNull(code);
        this.discountAmount = discount.setScale(2, RoundingMode.HALF_UP);
        recalculateTotal();
        this.updatedAt = Instant.now();
    }

    public void removePromotion() {
        ensureNotTerminal();
        ensurePromotionsEditable();
        this.promotionId = null;
        this.promotionCode = null;
        this.discountAmount = BigDecimal.ZERO.setScale(2);
        recalculateTotal();
        this.updatedAt = Instant.now();
    }

    private void ensurePromotionsEditable() {
        if (status != CheckoutStatus.PENDING) {
            throw new CheckoutDomainException(CheckoutErrorCode.PROMOTION_NOT_EDITABLE,
                "Promotions can only be changed before the checkout is validated");
        }
    }

    public void selectShippingAddress(UUID addressId) {
        ensureNotTerminal();
        this.shippingAddressId = Objects.requireNonNull(addressId);
        this.updatedAt = Instant.now();
        domainEvents.add(new CheckoutShippingAddressSelectedEvent(id, addressId));
    }

    public void selectDeliveryMethod(DeliveryMethod method) {
        ensureNotTerminal();
        this.deliveryMethod = Objects.requireNonNull(method);
        this.deliveryCharge = method.charge();
        recalculateTotal();
        this.updatedAt = Instant.now();
        domainEvents.add(new CheckoutDeliveryMethodSelectedEvent(id, method.code(), method.charge()));
    }

    public void validate() {
        ensureNotTerminal();
        if (shippingAddressId == null) {
            throw new CheckoutDomainException(CheckoutErrorCode.MISSING_SHIPPING_ADDRESS,
                "Shipping address must be selected before validation");
        }
        if (deliveryMethod == null) {
            throw new CheckoutDomainException(CheckoutErrorCode.MISSING_DELIVERY_METHOD,
                "Delivery method must be selected before validation");
        }
        if (items.isEmpty()) {
            throw new CheckoutDomainException(CheckoutErrorCode.CHECKOUT_EMPTY,
                "Checkout must have at least one item");
        }
        transitionTo(CheckoutStatus.VALIDATED);
        this.validatedAt = Instant.now();
        this.updatedAt = Instant.now();
        domainEvents.add(new CheckoutValidatedEvent(id, customerId, totalAmount));
    }

    public void markReadyForPayment() {
        ensureNotTerminal();
        transitionTo(CheckoutStatus.READY_FOR_PAYMENT);
        this.updatedAt = Instant.now();
        domainEvents.add(new CheckoutReadyForPaymentEvent(id, customerId, totalAmount));
    }

    public void expire() {
        if (status.isTerminal()) {
            throw new CheckoutDomainException(CheckoutErrorCode.CHECKOUT_ALREADY_TERMINAL,
                "Checkout is already in terminal state: " + status);
        }
        this.status = CheckoutStatus.EXPIRED;
        this.updatedAt = Instant.now();
        domainEvents.add(new CheckoutExpiredEvent(id, customerId));
    }

    /** The checkout became an order; it can no longer be modified, cancelled or reused. */
    public void complete() {
        if (isExpired()) {
            throw new CheckoutDomainException(CheckoutErrorCode.CHECKOUT_EXPIRED,
                "Checkout has expired at: " + expiresAt);
        }
        transitionTo(CheckoutStatus.COMPLETED);
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        ensureNotTerminal();
        this.status = CheckoutStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.updatedAt = Instant.now();
        domainEvents.add(new CheckoutExpiredEvent(id, customerId));
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    private void recalculateTotal() {
        this.totalAmount = subtotal.add(taxAmount).add(deliveryCharge)
            .subtract(discountAmount).setScale(2, RoundingMode.HALF_UP);
    }

    private void ensureNotTerminal() {
        if (status.isTerminal()) {
            throw new CheckoutDomainException(CheckoutErrorCode.CHECKOUT_ALREADY_TERMINAL,
                "Checkout is in terminal state: " + status);
        }
        if (isExpired()) {
            throw new CheckoutDomainException(CheckoutErrorCode.CHECKOUT_EXPIRED,
                "Checkout has expired at: " + expiresAt);
        }
    }

    private void transitionTo(CheckoutStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new CheckoutDomainException(CheckoutErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot transition from " + status + " to " + target);
        }
        this.status = target;
    }

    public CheckoutId getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getCartId() { return cartId; }
    public CheckoutStatus getStatus() { return status; }
    public UUID getShippingAddressId() { return shippingAddressId; }
    public DeliveryMethod getDeliveryMethod() { return deliveryMethod; }
    public List<CheckoutItem> getItems() { return Collections.unmodifiableList(items); }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public BigDecimal getDeliveryCharge() { return deliveryCharge; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public UUID getPromotionId() { return promotionId; }
    public String getPromotionCode() { return promotionCode; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getNotes() { return notes; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getValidatedAt() { return validatedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<DomainEvent> getDomainEvents() { return List.copyOf(domainEvents); }
    public void clearDomainEvents() { domainEvents.clear(); }
}
