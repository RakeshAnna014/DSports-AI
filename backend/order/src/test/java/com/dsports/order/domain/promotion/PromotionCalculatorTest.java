package com.dsports.order.domain.promotion;

import com.dsports.order.domain.promotion.model.Promotion;
import com.dsports.order.domain.promotion.model.Promotion.DiscountType;
import com.dsports.order.domain.promotion.model.Promotion.ScopeType;
import com.dsports.order.domain.promotion.model.PromotionCalculator;
import com.dsports.order.domain.promotion.model.PromotionCalculator.Line;
import com.dsports.order.domain.promotion.model.PromotionCalculator.Rejection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final UUID CRICKET = UUID.randomUUID();
    private static final UUID FOOTBALL = UUID.randomUUID();

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static Line line(String total, UUID sport) { return new Line(UUID.randomUUID(), d(total), UUID.randomUUID(), sport); }

    private static Promotion percent(String pct, String max, String min) {
        return new Promotion(UUID.randomUUID(), "P", "t", "d", DiscountType.PERCENT, d(pct), max == null ? null : d(max), d(min), ScopeType.ALL,
            Set.of(), null, null, null, null, true, true);
    }

    @Test
    void percentOffEligibleSubtotalRoundedHalfUp() {
        var e = PromotionCalculator.evaluate(percent("10", null, "0"), List.of(line("333.35", CRICKET)), NOW, 0, 0);
        assertThat(e.eligible()).isTrue();
        assertThat(e.discount()).isEqualByComparingTo("33.34");           // 33.335 -> 33.34
    }

    @Test
    void maximumDiscountCapsAPercentOffer() {
        var e = PromotionCalculator.evaluate(percent("10", "300", "500"), List.of(line("9000.00", CRICKET)), NOW, 0, 0);
        assertThat(e.discount()).isEqualByComparingTo("300.00");
    }

    @Test
    void fixedOfferIsNeverMoreThanTheEligibleSubtotal() {
        var fixed = new Promotion(UUID.randomUUID(), "F", "t", "d", DiscountType.FIXED, d("200"), null, d("0"), ScopeType.ALL, Set.of(), null, null, null, null, true, true);
        assertThat(PromotionCalculator.evaluate(fixed, List.of(line("150.00", CRICKET)), NOW, 0, 0).discount()).isEqualByComparingTo("150.00");
        assertThat(PromotionCalculator.evaluate(fixed, List.of(line("5000.00", CRICKET)), NOW, 0, 0).discount()).isEqualByComparingTo("200.00");
    }

    @Test
    void minimumOrderIsInclusiveAndTheMessageSaysHowMuchIsMissing() {
        var p = percent("10", null, "500");
        assertThat(PromotionCalculator.evaluate(p, List.of(line("500.00", CRICKET)), NOW, 0, 0).eligible()).isTrue();
        var below = PromotionCalculator.evaluate(p, List.of(line("499.99", CRICKET)), NOW, 0, 0);
        assertThat(below.eligible()).isFalse();
        assertThat(below.rejection()).isEqualTo(Rejection.MIN_ORDER_NOT_MET);
        assertThat(below.reason()).contains("0.01");
        assertThat(below.discount()).isEqualByComparingTo("0");
    }

    @Test
    void scopedOfferOnlyCountsMatchingLinesAndTheMinimumUsesThatSubtotal() {
        var scoped = new Promotion(UUID.randomUUID(), "C", "t", "d", DiscountType.PERCENT, d("15"), d("750"), d("1000"), ScopeType.SPORT, Set.of(CRICKET), null, null, null, null, true, true);
        var mixed = List.of(line("800.00", CRICKET), line("5000.00", FOOTBALL));
        var e = PromotionCalculator.evaluate(scoped, mixed, NOW, 0, 0);
        assertThat(e.eligible()).isFalse();                                      // 800 of cricket < 1000 even though the cart is 5800
        assertThat(e.rejection()).isEqualTo(Rejection.MIN_ORDER_NOT_MET);
        assertThat(e.eligibleSubtotal()).isEqualByComparingTo("800.00");
        var ok = PromotionCalculator.evaluate(scoped, List.of(line("1200.00", CRICKET), line("5000.00", FOOTBALL)), NOW, 0, 0);
        assertThat(ok.discount()).isEqualByComparingTo("180.00");                // 15% of the cricket line only
        var none = PromotionCalculator.evaluate(scoped, List.of(line("5000.00", FOOTBALL)), NOW, 0, 0);
        assertThat(none.rejection()).isEqualTo(Rejection.NOT_APPLICABLE);
    }

    @Test
    void validityWindowIsEnforcedWithHalfOpenEnd() {
        var windowed = new Promotion(UUID.randomUUID(), "W", "t", "d", DiscountType.PERCENT, d("10"), null, d("0"), ScopeType.ALL, Set.of(),
            Instant.parse("2026-10-14T09:00:00Z"), Instant.parse("2026-10-14T11:00:00Z"), null, null, true, true);
        assertThat(PromotionCalculator.evaluate(windowed, List.of(line("100", CRICKET)), Instant.parse("2026-10-14T08:59:59Z"), 0, 0).rejection()).isEqualTo(Rejection.NOT_STARTED);
        assertThat(PromotionCalculator.evaluate(windowed, List.of(line("100", CRICKET)), Instant.parse("2026-10-14T09:00:00Z"), 0, 0).eligible()).isTrue();
        assertThat(PromotionCalculator.evaluate(windowed, List.of(line("100", CRICKET)), Instant.parse("2026-10-14T11:00:00Z"), 0, 0).rejection()).isEqualTo(Rejection.EXPIRED);
    }

    @Test
    void inactiveOfferIsRejected() {
        var off = new Promotion(UUID.randomUUID(), "X", "t", "d", DiscountType.PERCENT, d("10"), null, d("0"), ScopeType.ALL, Set.of(), null, null, null, null, false, true);
        assertThat(PromotionCalculator.evaluate(off, List.of(line("100", CRICKET)), NOW, 0, 0).rejection()).isEqualTo(Rejection.NOT_ACTIVE);
    }

    @Test
    void perCustomerAndOverallLimitsAreBoundariesNotJustSoftWarnings() {
        var limited = new Promotion(UUID.randomUUID(), "L", "t", "d", DiscountType.PERCENT, d("10"), null, d("0"), ScopeType.ALL, Set.of(), null, null, 2, 5, true, true);
        assertThat(PromotionCalculator.evaluate(limited, List.of(line("100", CRICKET)), NOW, 1, 4).eligible()).isTrue();
        assertThat(PromotionCalculator.evaluate(limited, List.of(line("100", CRICKET)), NOW, 2, 4).rejection()).isEqualTo(Rejection.CUSTOMER_LIMIT_REACHED);
        assertThat(PromotionCalculator.evaluate(limited, List.of(line("100", CRICKET)), NOW, 1, 5).rejection()).isEqualTo(Rejection.USAGE_LIMIT_REACHED);
    }

    @Test
    void emptyCartOrZeroValueLinesNeverProduceADiscount() {
        assertThat(PromotionCalculator.evaluate(percent("10", null, "0"), List.of(), NOW, 0, 0).rejection()).isEqualTo(Rejection.NOT_APPLICABLE);
        assertThat(PromotionCalculator.evaluate(percent("10", null, "0"), List.of(line("0.00", CRICKET)), NOW, 0, 0).eligible()).isFalse();
    }
}
