package com.dsports.pricing.domain.model;

import com.dsports.pricing.domain.event.*;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Price {

    private final PriceId id;
    private final ProductId productId;
    private Money mrp;
    private Money sellingPrice;
    private Currency currency;
    private EffectiveDate effectiveDate;
    private PriceStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Price(PriceId id, ProductId productId, Money mrp, Money sellingPrice,
                  Currency currency, EffectiveDate effectiveDate, PriceStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.mrp = Objects.requireNonNull(mrp, "mrp must not be null");
        this.sellingPrice = Objects.requireNonNull(sellingPrice, "sellingPrice must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.effectiveDate = Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.version = 0;
    }

    public static Price create(ProductId productId, Money mrp, Money sellingPrice,
                                Currency currency, EffectiveDate effectiveDate) {
        if (sellingPrice.isGreaterThan(mrp)) {
            throw new PricingDomainException(PricingErrorCode.INVALID_PRICE,
                    "Selling price must not exceed MRP");
        }
        var price = new Price(PriceId.generate(), productId, mrp, sellingPrice, currency,
                effectiveDate, PriceStatus.DRAFT);
        price.recordEvent(new PriceCreatedEvent(price.id));
        return price;
    }

    public static Price reconstitute(PriceId id, ProductId productId, Money mrp, Money sellingPrice,
                                      Currency currency, EffectiveDate effectiveDate, PriceStatus status,
                                      Instant createdAt, Instant updatedAt, int version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        var price = new Price(id, productId, mrp, sellingPrice, currency, effectiveDate, status);
        price.createdAt = createdAt;
        price.updatedAt = updatedAt;
        price.version = version;
        return price;
    }

    public void updatePrice(Money newMrp, Money newSellingPrice, EffectiveDate newEffectiveDate) {
        if (this.status == PriceStatus.ARCHIVED) {
            throw new PricingDomainException(PricingErrorCode.CANNOT_MODIFY_ARCHIVED,
                    "Cannot update an archived price");
        }
        if (newSellingPrice.isGreaterThan(newMrp)) {
            throw new PricingDomainException(PricingErrorCode.INVALID_PRICE,
                    "Selling price must not exceed MRP");
        }
        this.mrp = newMrp;
        this.sellingPrice = newSellingPrice;
        this.effectiveDate = newEffectiveDate;
        this.updatedAt = Instant.now();
        recordEvent(new PriceUpdatedEvent(this.id));
    }

    public void schedule(Instant scheduledFrom) {
        if (this.status == PriceStatus.ARCHIVED) {
            throw new PricingDomainException(PricingErrorCode.CANNOT_MODIFY_ARCHIVED,
                    "Cannot schedule an archived price");
        }
        if (this.status != PriceStatus.DRAFT) {
            throw new PricingDomainException(PricingErrorCode.CANNOT_SCHEDULE_NON_DRAFT,
                    "Only DRAFT prices can be scheduled. Current status: " + this.status);
        }
        this.effectiveDate = EffectiveDate.from(scheduledFrom);
        this.status = PriceStatus.SCHEDULED;
        this.updatedAt = Instant.now();
        recordEvent(new PriceScheduledEvent(this.id, scheduledFrom));
    }

    public void activate() {
        if (this.status == PriceStatus.ARCHIVED) {
            throw new PricingDomainException(PricingErrorCode.CANNOT_ACTIVATE_ARCHIVED,
                    "Cannot activate an archived price");
        }
        if (this.status == PriceStatus.ACTIVE) {
            return;
        }
        this.effectiveDate = EffectiveDate.immediate();
        this.status = PriceStatus.ACTIVE;
        this.updatedAt = Instant.now();
        recordEvent(new PriceActivatedEvent(this.id));
    }

    public void archive() {
        if (this.status == PriceStatus.ARCHIVED) {
            return;
        }
        this.status = PriceStatus.ARCHIVED;
        this.updatedAt = Instant.now();
        recordEvent(new PriceArchivedEvent(this.id));
    }

    private void recordEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    public PriceId getId() { return id; }
    public ProductId getProductId() { return productId; }
    public Money getMrp() { return mrp; }
    public Money getSellingPrice() { return sellingPrice; }
    public Currency getCurrency() { return currency; }
    public EffectiveDate getEffectiveDate() { return effectiveDate; }
    public PriceStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }

    public List<DomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Price that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
