package com.dsports.ground.domain;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.dsports.ground.domain.GroundFixtures.IST;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.SATURDAY;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.ground;
import static com.dsports.ground.domain.GroundFixtures.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundTest {

    @Test
    void fixtureDatesAreTheWeekdaysTheTestsAssume() {
        assertThat(WEDNESDAY.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(SATURDAY.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
    }

    @Test
    void theGridIsOpeningHoursSplitByTheSlotLengthInTheGroundsOwnZone() {
        var slots = ground().slotsOn(WEDNESDAY);

        assertThat(slots).hasSize(16);
        assertThat(slots.get(0).start()).isEqualTo(at(WEDNESDAY, 6, 0));
        assertThat(slots.get(0).start().toString()).isEqualTo("2026-11-18T00:30:00Z");
        assertThat(slots.get(15).end()).isEqualTo(at(WEDNESDAY, 22, 0));
    }

    @Test
    void aTrailingPartSlotIsDropped() {
        var ground = Ground.create(OWNER, plain(90, IST), NOW);

        assertThat(ground.slotsOn(WEDNESDAY)).hasSize(10);
        assertThat(ground.slotsOn(WEDNESDAY).get(9).end()).isEqualTo(at(WEDNESDAY, 21, 0));
    }

    @Test
    void openingHoursAreWallClockTimesSoADifferentZoneMovesTheInstants() {
        var london = Ground.create(OWNER, plain(60, ZoneId.of("Europe/London")), NOW);

        assertThat(london.slotsOn(WEDNESDAY).get(0).start().toString()).isEqualTo("2026-11-18T06:00:00Z");
    }

    @Test
    void baseWeekendAndPeakPricesApplyPerSlotAndPeakWinsOverWeekend() {
        var ground = ground();

        assertThat(price(ground, WEDNESDAY, 9)).isEqualByComparingTo("1000");
        assertThat(price(ground, WEDNESDAY, 18)).isEqualByComparingTo("2000");
        assertThat(price(ground, WEDNESDAY, 21)).isEqualByComparingTo("1000");
        assertThat(price(ground, SATURDAY, 9)).isEqualByComparingTo("1500");
        assertThat(price(ground, SATURDAY, 19)).isEqualByComparingTo("2000");
    }

    @Test
    void withoutAWeekendPriceTheWeekendUsesTheBasePrice() {
        var ground = Ground.create(OWNER, plain(60, IST), NOW);

        assertThat(price(ground, SATURDAY, 9)).isEqualByComparingTo("1000");
    }

    @Test
    void aMultiSlotBookingIsPricedAsTheSumOfItsSlots() {
        var total = ground().priceFor(at(WEDNESDAY, 17, 0), at(WEDNESDAY, 19, 0));

        assertThat(total).isEqualByComparingTo("3000");
    }

    @Test
    void onlyWholeConsecutiveGridSlotsInsideOpeningHoursAreOnTheGrid() {
        var ground = ground();

        assertThat(ground.slotCount(at(WEDNESDAY, 6, 0), at(WEDNESDAY, 8, 0))).isEqualTo(2);
        assertThat(ground.slotCount(at(WEDNESDAY, 21, 0), at(WEDNESDAY, 22, 0))).isEqualTo(1);
        assertThat(ground.slotCount(at(WEDNESDAY, 6, 30), at(WEDNESDAY, 7, 30))).isZero();
        assertThat(ground.slotCount(at(WEDNESDAY, 6, 0), at(WEDNESDAY, 6, 30))).isZero();
        assertThat(ground.slotCount(at(WEDNESDAY, 5, 0), at(WEDNESDAY, 6, 0))).isZero();
        assertThat(ground.slotCount(at(WEDNESDAY, 21, 0), at(WEDNESDAY, 23, 0))).isZero();
        assertThat(ground.slotCount(at(WEDNESDAY, 8, 0), at(WEDNESDAY, 6, 0))).isZero();
        assertThat(ground.slotCount(at(WEDNESDAY, 21, 0), at(SATURDAY, 7, 0))).isZero();
    }

    @Test
    void pricingAnOffGridRangeIsRejected() {
        assertThatThrownBy(() -> ground().priceFor(at(WEDNESDAY, 6, 30), at(WEDNESDAY, 7, 30)))
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.INVALID_SLOT));
    }

    @Test
    void invalidGroundDetailsAreRejected() {
        assertInvalid(s -> detail(s, " ", s.opensAt(), s.closesAt(), 60, s.basePrice(), null, null, null));
        assertInvalid(s -> detail(s, s.name(), LocalTime.of(22, 0), LocalTime.of(6, 0), 60, s.basePrice(), null, null, null));
        assertInvalid(s -> detail(s, s.name(), s.opensAt(), s.closesAt(), 10, s.basePrice(), null, null, null));
        assertInvalid(s -> detail(s, s.name(), LocalTime.of(6, 0), LocalTime.of(6, 30), 60, s.basePrice(), null, null, null));
        assertInvalid(s -> detail(s, s.name(), s.opensAt(), s.closesAt(), 60, new BigDecimal("-1"), null, null, null));
        assertInvalid(s -> detail(s, s.name(), s.opensAt(), s.closesAt(), 60, new BigDecimal("10.005"), null, null, null));
        assertInvalid(s -> detail(s, s.name(), s.opensAt(), s.closesAt(), 60, s.basePrice(), new BigDecimal("2000"), null, null));
        assertInvalid(s -> detail(s, s.name(), s.opensAt(), s.closesAt(), 60, s.basePrice(), new BigDecimal("2000"), LocalTime.of(20, 0), LocalTime.of(19, 0)));
        assertInvalid(s -> new Ground.Spec(s.name(), s.address(), s.city(), new BigDecimal("12.9"), null, s.pitchType(), List.of(), List.of(),
            s.opensAt(), s.closesAt(), s.timeZone(), 60, s.basePrice(), null, null, null, null));
        assertInvalid(s -> new Ground.Spec(s.name(), s.address(), s.city(), null, null, s.pitchType(), List.of(), List.of("javascript:alert(1)"),
            s.opensAt(), s.closesAt(), s.timeZone(), 60, s.basePrice(), null, null, null, null));
    }

    @Test
    void updatingReplacesTheDetailsAndRevalidatesThem() {
        var ground = ground();
        var changed = new Ground.Spec("Renamed", "New Road", "Mysuru", null, null, ground.getSpec().pitchType(), List.of(), List.of(),
            LocalTime.of(7, 0), LocalTime.of(21, 0), IST, 120, new BigDecimal("3000"), null, null, null, null);

        ground.update(changed, NOW.plusSeconds(60));

        assertThat(ground.getName()).isEqualTo("Renamed");
        assertThat(ground.slotsOn(WEDNESDAY)).hasSize(7);
        var invalid = new Ground.Spec("", "x", "y", null, null, ground.getSpec().pitchType(), List.of(), List.of(),
            LocalTime.of(7, 0), LocalTime.of(21, 0), IST, 120, BigDecimal.TEN, null, null, null, null);
        assertThatThrownBy(() -> ground.update(invalid, NOW)).isInstanceOf(GroundDomainException.class);
    }

    private static BigDecimal price(Ground ground, LocalDate date, int hour) {
        return ground.priceFor(at(date, hour, 0), at(date, hour + 1, 0));
    }

    /** The standard opening hours with a single flat price (no weekend or peak rule). */
    private static Ground.Spec plain(int slotMinutes, ZoneId zone) {
        var s = spec();
        return new Ground.Spec(s.name(), s.address(), s.city(), null, null, s.pitchType(), List.of(), List.of(),
            s.opensAt(), s.closesAt(), zone, slotMinutes, s.basePrice(), null, null, null, null);
    }

    private static Ground.Spec detail(Ground.Spec s, String name, LocalTime opens, LocalTime closes, int slotMinutes, BigDecimal base,
                                      BigDecimal peakPrice, LocalTime peakFrom, LocalTime peakTo) {
        return new Ground.Spec(name, s.address(), s.city(), null, null, s.pitchType(), List.of(), List.of(), opens, closes, s.timeZone(),
            slotMinutes, base, null, peakPrice, peakFrom, peakTo);
    }

    private static void assertInvalid(UnaryOperator<Ground.Spec> change) {
        assertThatThrownBy(() -> Ground.create(OWNER, change.apply(spec()), NOW))
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.VALIDATION_ERROR));
    }
}
