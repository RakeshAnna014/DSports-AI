package com.dsports.cart.domain.model;

import com.dsports.cart.domain.event.CartClearedEvent;
import com.dsports.cart.domain.event.CartCreatedEvent;
import com.dsports.cart.domain.event.CartItemRemovedEvent;
import com.dsports.cart.domain.event.CartItemUpdatedEvent;
import com.dsports.cart.domain.event.ProductAddedToCartEvent;
import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Cart {
    private static final int MAX_DIFFERENT_PRODUCTS = 50;

    private final CartId id;
    private final UserId userId;
    private final List<CartItem> items;
    private CartStatus status;
    private int totalItems;
    private Money totalAmount;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Cart(CartId id, UserId userId, List<CartItem> items, CartStatus status,
                 int totalItems, Money totalAmount, int version,
                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.items = new ArrayList<>(items);
        this.status = status;
        this.totalItems = totalItems;
        this.totalAmount = totalAmount;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Cart create(CartId id, UserId userId) {
        var now = Instant.now();
        var cart = new Cart(id, userId, new ArrayList<>(), CartStatus.ACTIVE,
            0, Money.zero(), 0, now, now);
        cart.domainEvents.add(new CartCreatedEvent(id, userId));
        return cart;
    }

    public static Cart reconstitute(CartId id, UserId userId, List<CartItem> items, CartStatus status,
                                    int totalItems, BigDecimal totalAmount, int version,
                                    Instant createdAt, Instant updatedAt) {
        return new Cart(id, userId, items, status, totalItems, Money.from(totalAmount), version, createdAt, updatedAt);
    }

    public void addItem(CartItemId itemId, String productId, String productName,
                        Money unitPrice, Quantity quantity) {
        ensureActive();

        var existing = findItemByProductId(productId);
        if (existing.isPresent()) {
            var existingItem = existing.get();
            var newQty = existingItem.getQuantity().add(quantity);
            existingItem.updateQuantity(newQty);
            domainEvents.add(new CartItemUpdatedEvent(id, CartItemId.fromUUID(existingItem.getId().value()),
                existingItem.getProductId(), newQty));
        } else {
            if (items.size() >= MAX_DIFFERENT_PRODUCTS) {
                throw new CartDomainException(CartErrorCode.MAX_ITEMS_EXCEEDED,
                    "Cart cannot have more than " + MAX_DIFFERENT_PRODUCTS + " different products");
            }
            var newItem = CartItem.create(itemId, id, productId, productName, unitPrice, quantity);
            items.add(newItem);
            domainEvents.add(new ProductAddedToCartEvent(id, itemId, productId, productName,
                unitPrice.value(), quantity.value()));
        }
        calculateTotals();
    }

    public void updateItemQuantity(CartItemId itemId, Quantity newQuantity) {
        ensureActive();
        var item = findItemById(itemId)
            .orElseThrow(() -> new CartDomainException(CartErrorCode.ITEM_NOT_FOUND,
                "Cart item not found: " + itemId.value()));
        item.updateQuantity(newQuantity);
        domainEvents.add(new CartItemUpdatedEvent(id, itemId, item.getProductId(), newQuantity));
        calculateTotals();
    }

    public void removeItem(CartItemId itemId) {
        ensureActive();
        var item = findItemById(itemId)
            .orElseThrow(() -> new CartDomainException(CartErrorCode.ITEM_NOT_FOUND,
                "Cart item not found: " + itemId.value()));
        items.remove(item);
        domainEvents.add(new CartItemRemovedEvent(id, itemId, item.getProductId()));
        calculateTotals();
    }

    public void clear() {
        ensureActive();
        items.clear();
        domainEvents.add(new CartClearedEvent(id, userId));
        calculateTotals();
    }

    private void calculateTotals() {
        this.totalItems = items.stream()
            .mapToInt(item -> item.getQuantity().value())
            .sum();
        this.totalAmount = items.stream()
            .map(CartItem::getLineTotal)
            .reduce(Money::add)
            .orElse(Money.zero());
        this.updatedAt = Instant.now();
    }

    public Optional<CartItem> findItemByProductId(String productId) {
        return items.stream()
            .filter(item -> item.getProductId().equals(productId))
            .findFirst();
    }

    public Optional<CartItem> findItemById(CartItemId itemId) {
        return items.stream()
            .filter(item -> item.getId().equals(itemId))
            .findFirst();
    }

    public void checkout() {
        ensureActive();
        this.status = CartStatus.CHECKED_OUT;
        this.updatedAt = Instant.now();
    }

    public void abandon() {
        if (!status.isActive()) {
            throw new CartDomainException(CartErrorCode.INVALID_STATUS_TRANSITION,
                "Only active carts can be abandoned");
        }
        this.status = CartStatus.ABANDONED;
        this.updatedAt = Instant.now();
    }

    private void ensureActive() {
        if (!status.isActive()) {
            throw new CartDomainException(CartErrorCode.CART_NOT_ACTIVE,
                "Cart is not active: " + status);
        }
    }

    public CartId getId() {
        return id;
    }

    public UserId getUserId() {
        return userId;
    }

    public List<CartItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public CartStatus getStatus() {
        return status;
    }

    public int getTotalItems() {
        return totalItems;
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<DomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cart cart)) return false;
        return Objects.equals(id, cart.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Cart{id=" + id + ", userId=" + userId + ", status=" + status + ", items=" + items.size() + "}";
    }
}
