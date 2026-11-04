package com.dsports.wishlist.domain.model;

import com.dsports.shared.domain.kernel.DomainEvent;
import com.dsports.wishlist.domain.event.WishlistClearedEvent;
import com.dsports.wishlist.domain.event.WishlistCreatedEvent;
import com.dsports.wishlist.domain.event.WishlistItemAddedEvent;
import com.dsports.wishlist.domain.event.WishlistItemMovedToCartEvent;
import com.dsports.wishlist.domain.event.WishlistItemRemovedEvent;
import com.dsports.wishlist.domain.exception.WishlistDomainException;
import com.dsports.wishlist.domain.exception.WishlistErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Wishlist {
    private final WishlistId id;
    private final UserId userId;
    private final List<WishlistItem> items;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Wishlist(WishlistId id, UserId userId, List<WishlistItem> items,
                     int version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.items = new ArrayList<>(items);
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Wishlist create(WishlistId id, UserId userId) {
        var now = Instant.now();
        var wishlist = new Wishlist(id, userId, new ArrayList<>(), 0, now, now);
        wishlist.domainEvents.add(new WishlistCreatedEvent(id, userId));
        return wishlist;
    }

    public static Wishlist reconstitute(WishlistId id, UserId userId,
                                        List<WishlistItem> items, int version,
                                        Instant createdAt, Instant updatedAt) {
        return new Wishlist(id, userId, items, version, createdAt, updatedAt);
    }

    public void addItem(WishlistItemId itemId, String productId, String productName,
                        String productImage, BigDecimal currentPrice) {
        var existing = findItemByProductId(productId);
        if (existing.isPresent()) {
            throw new WishlistDomainException(WishlistErrorCode.DUPLICATE_WISHLIST_ITEM,
                "Product already exists in wishlist: " + productId);
        }
        var newItem = WishlistItem.create(itemId, id, productId, productName,
            productImage, Price.from(currentPrice));
        items.add(newItem);
        domainEvents.add(new WishlistItemAddedEvent(id, itemId, productId,
            productName, currentPrice));
        this.updatedAt = Instant.now();
    }

    public void removeItem(WishlistItemId itemId) {
        var item = findItemById(itemId)
            .orElseThrow(() -> new WishlistDomainException(WishlistErrorCode.ITEM_NOT_FOUND,
                "Wishlist item not found: " + itemId.value()));
        items.remove(item);
        domainEvents.add(new WishlistItemRemovedEvent(id, itemId, item.getProductId()));
        this.updatedAt = Instant.now();
    }

    public void moveToCart(WishlistItemId itemId) {
        var item = findItemById(itemId)
            .orElseThrow(() -> new WishlistDomainException(WishlistErrorCode.ITEM_NOT_FOUND,
                "Wishlist item not found: " + itemId.value()));
        items.remove(item);
        domainEvents.add(new WishlistItemMovedToCartEvent(id, itemId,
            item.getProductId(), item.getProductName(),
            item.getCurrentPrice().value()));
        this.updatedAt = Instant.now();
    }

    public void clear() {
        items.clear();
        domainEvents.add(new WishlistClearedEvent(id, userId));
        this.updatedAt = Instant.now();
    }

    public Optional<WishlistItem> findItemByProductId(String productId) {
        return items.stream()
            .filter(item -> item.getProductId().equals(productId))
            .findFirst();
    }

    public Optional<WishlistItem> findItemById(WishlistItemId itemId) {
        return items.stream()
            .filter(item -> item.getId().equals(itemId))
            .findFirst();
    }

    public int getItemCount() {
        return items.size();
    }

    public WishlistId getId() { return id; }
    public UserId getUserId() { return userId; }
    public List<WishlistItem> getItems() { return Collections.unmodifiableList(items); }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<DomainEvent> getDomainEvents() { return List.copyOf(domainEvents); }
    public void clearDomainEvents() { domainEvents.clear(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Wishlist wishlist)) return false;
        return Objects.equals(id, wishlist.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }

    @Override
    public String toString() {
        return "Wishlist{id=" + id + ", userId=" + userId + ", items=" + items.size() + "}";
    }
}
