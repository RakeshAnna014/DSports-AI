package com.dsports.pricing.domain.model;

import com.dsports.pricing.domain.event.*;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PriceTest {

    private static final ProductId PRODUCT_ID = ProductId.fromUUID(UUID.randomUUID());
    private static final Money MRP = Money.from(200);
    private static final Money SELLING_PRICE = Money.from(150);
    private static final Currency CURRENCY = Currency.from("INR");

    @Test
    void shouldCreateDraftPrice() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());

        assertThat(price.getId()).isNotNull();
        assertThat(price.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(price.getMrp()).isEqualTo(MRP);
        assertThat(price.getSellingPrice()).isEqualTo(SELLING_PRICE);
        assertThat(price.getCurrency()).isEqualTo(CURRENCY);
        assertThat(price.getStatus()).isEqualTo(PriceStatus.DRAFT);
        assertThat(price.getVersion()).isZero();
        assertThat(price.getCreatedAt()).isNotNull();
        assertThat(price.getUpdatedAt()).isNotNull();
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceCreatedEvent.class);
    }

    @Test
    void shouldRejectSellingPriceExceedingMrp() {
        assertThatThrownBy(() -> Price.create(PRODUCT_ID, Money.from(100), Money.from(150), CURRENCY, EffectiveDate.immediate()))
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("Selling price must not exceed MRP")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.INVALID_PRICE));
    }

    @Test
    void shouldUpdatePriceWhenDraft() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.clearDomainEvents();

        var newMrp = Money.from(250);
        var newSellingPrice = Money.from(200);
        var newEffectiveDate = EffectiveDate.immediate();
        price.updatePrice(newMrp, newSellingPrice, newEffectiveDate);

        assertThat(price.getMrp()).isEqualTo(newMrp);
        assertThat(price.getSellingPrice()).isEqualTo(newSellingPrice);
        assertThat(price.getEffectiveDate()).isEqualTo(newEffectiveDate);
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceUpdatedEvent.class);
    }

    @Test
    void shouldRejectUpdateWhenArchived() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.archive();
        price.clearDomainEvents();

        assertThatThrownBy(() -> price.updatePrice(Money.from(300), Money.from(250), EffectiveDate.immediate()))
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("Cannot update an archived price")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.CANNOT_MODIFY_ARCHIVED));
    }

    @Test
    void shouldScheduleDraftPrice() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.clearDomainEvents();

        var scheduledFrom = Instant.now().plusSeconds(86400);
        price.schedule(scheduledFrom);

        assertThat(price.getStatus()).isEqualTo(PriceStatus.SCHEDULED);
        assertThat(price.getEffectiveDate().effectiveFrom()).isEqualTo(scheduledFrom);
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceScheduledEvent.class);
    }

    @Test
    void shouldRejectScheduleNonDraft() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.activate();
        price.clearDomainEvents();

        assertThatThrownBy(() -> price.schedule(Instant.now().plusSeconds(86400)))
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("Only DRAFT prices can be scheduled")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.CANNOT_SCHEDULE_NON_DRAFT));
    }

    @Test
    void shouldRejectScheduleWhenArchived() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.archive();
        price.clearDomainEvents();

        assertThatThrownBy(() -> price.schedule(Instant.now().plusSeconds(86400)))
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("Cannot schedule an archived price")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.CANNOT_MODIFY_ARCHIVED));
    }

    @Test
    void shouldActivateDraftPrice() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.clearDomainEvents();

        price.activate();

        assertThat(price.getStatus()).isEqualTo(PriceStatus.ACTIVE);
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceActivatedEvent.class);
    }

    @Test
    void shouldActivateScheduledPrice() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.schedule(Instant.now().plusSeconds(86400));
        price.clearDomainEvents();

        price.activate();

        assertThat(price.getStatus()).isEqualTo(PriceStatus.ACTIVE);
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceActivatedEvent.class);
    }

    @Test
    void shouldRejectActivateWhenArchived() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.archive();
        price.clearDomainEvents();

        assertThatThrownBy(() -> price.activate())
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("Cannot activate an archived price")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.CANNOT_ACTIVATE_ARCHIVED));
    }

    @Test
    void shouldArchiveDraftPrice() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.clearDomainEvents();

        price.archive();

        assertThat(price.getStatus()).isEqualTo(PriceStatus.ARCHIVED);
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceArchivedEvent.class);
    }

    @Test
    void shouldArchiveActivePrice() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.activate();
        price.clearDomainEvents();

        price.archive();

        assertThat(price.getStatus()).isEqualTo(PriceStatus.ARCHIVED);
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceArchivedEvent.class);
    }

    @Test
    void shouldArchiveScheduledPrice() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.schedule(Instant.now().plusSeconds(86400));
        price.clearDomainEvents();

        price.archive();

        assertThat(price.getStatus()).isEqualTo(PriceStatus.ARCHIVED);
        assertThat(price.getDomainEvents()).hasSize(1);
        assertThat(price.getDomainEvents().get(0)).isInstanceOf(PriceArchivedEvent.class);
    }

    @Test
    void shouldArchiveArchivedPriceBeIdempotent() {
        var price = Price.create(PRODUCT_ID, MRP, SELLING_PRICE, CURRENCY, EffectiveDate.immediate());
        price.archive();
        price.clearDomainEvents();

        price.archive();

        assertThat(price.getStatus()).isEqualTo(PriceStatus.ARCHIVED);
        assertThat(price.getDomainEvents()).isEmpty();
    }

    @Test
    void shouldRejectNegativeMrp() {
        assertThatThrownBy(() -> Money.from(-100))
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("Money value must not be negative")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.INVALID_PRICE));
    }

    @Test
    void shouldRejectInvalidCurrency() {
        assertThatThrownBy(() -> Currency.from("XYZ"))
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("Unsupported currency")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.INVALID_CURRENCY));
    }

    @Test
    void shouldRejectEffectiveToBeforeEffectiveFrom() {
        var now = Instant.now();
        assertThatThrownBy(() -> EffectiveDate.from(now, now.minusSeconds(3600)))
                .isInstanceOf(PricingDomainException.class)
                .hasMessageContaining("effectiveTo must be after effectiveFrom")
                .satisfies(e -> assertThat(((PricingDomainException) e).getErrorCode())
                        .isEqualTo(PricingErrorCode.INVALID_EFFECTIVE_DATE));
    }
}
