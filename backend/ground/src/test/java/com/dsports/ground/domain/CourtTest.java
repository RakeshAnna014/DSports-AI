package com.dsports.ground.domain;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.dsports.ground.domain.GroundFixtures.BADMINTON;
import static com.dsports.ground.domain.GroundFixtures.CRICKET;
import static com.dsports.ground.domain.GroundFixtures.FOOTBALL;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.SATURDAY;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.court;
import static com.dsports.ground.domain.GroundFixtures.courtSpec;
import static com.dsports.ground.domain.GroundFixtures.venue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourtTest {

    @Test
    void fixtureDatesAreTheWeekdaysTheTestsAssume() {
        assertThat(WEDNESDAY.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(SATURDAY.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
    }

    @Test
    void theGridIsTheVenuesOpeningHoursSplitByTheCourtsSlotLengthInTheVenuesOwnZone() {
        var slots = court().slotsOn(WEDNESDAY);

        assertThat(slots).hasSize(16);
        assertThat(slots.get(0).start()).isEqualTo(at(WEDNESDAY, 6, 0));
        assertThat(slots.get(0).start().toString()).isEqualTo("2026-11-18T00:30:00Z");
        assertThat(slots.get(15).end()).isEqualTo(at(WEDNESDAY, 22, 0));
    }

    @Test
    void twoCourtsOfOneVenueShareTheOpeningHoursButKeepTheirOwnSlotLength() {
        var venue = venue();
        var hourly = Court.create(venue, courtSpec(), NOW);
        var ninety = Court.create(venue, withSlot(courtSpec(), 90), NOW);

        assertThat(hourly.slotsOn(WEDNESDAY)).hasSize(16);
        assertThat(ninety.slotsOn(WEDNESDAY)).hasSize(10);
        assertThat(ninety.slotsOn(WEDNESDAY).get(9).end()).isEqualTo(at(WEDNESDAY, 21, 0));
        assertThat(ninety.getVenueId()).isEqualTo(hourly.getVenueId()).isEqualTo(venue.getId());
    }

    @Test
    void openingHoursAreWallClockTimesSoADifferentZoneMovesTheInstants() {
        var london = Venue.create(GroundFixtures.OWNER, new Venue.Spec("V", "A", "London", null, null, List.of(), List.of(),
            LocalTime.of(6, 0), LocalTime.of(22, 0), ZoneId.of("Europe/London")), NOW);

        assertThat(court(london).slotsOn(WEDNESDAY).get(0).start().toString()).isEqualTo("2026-11-18T06:00:00Z");
    }

    @Test
    void baseWeekendAndPeakPricesApplyPerSlotAndPeakWinsOverWeekend() {
        var court = court();

        assertThat(price(court, WEDNESDAY, 9)).isEqualByComparingTo("1000");
        assertThat(price(court, WEDNESDAY, 18)).isEqualByComparingTo("2000");
        assertThat(price(court, WEDNESDAY, 21)).isEqualByComparingTo("1000");
        assertThat(price(court, SATURDAY, 9)).isEqualByComparingTo("1500");
        assertThat(price(court, SATURDAY, 19)).isEqualByComparingTo("2000");
    }

    @Test
    void withoutAWeekendPriceTheWeekendUsesTheBasePrice() {
        var s = courtSpec();
        var court = Court.create(venue(), new Court.Spec(s.name(), s.surface(), s.sportIds(), 60, s.basePrice(), null, null, null, null), NOW);

        assertThat(price(court, SATURDAY, 9)).isEqualByComparingTo("1000");
    }

    @Test
    void aMultiSlotBookingIsPricedAsTheSumOfItsSlots() {
        assertThat(court().priceFor(at(WEDNESDAY, 17, 0), at(WEDNESDAY, 19, 0))).isEqualByComparingTo("3000");
    }

    @Test
    void onlyWholeConsecutiveGridSlotsInsideOpeningHoursAreOnTheGrid() {
        var court = court();

        assertThat(court.slotCount(at(WEDNESDAY, 6, 0), at(WEDNESDAY, 8, 0))).isEqualTo(2);
        assertThat(court.slotCount(at(WEDNESDAY, 21, 0), at(WEDNESDAY, 22, 0))).isEqualTo(1);
        assertThat(court.slotCount(at(WEDNESDAY, 6, 30), at(WEDNESDAY, 7, 30))).isZero();
        assertThat(court.slotCount(at(WEDNESDAY, 6, 0), at(WEDNESDAY, 6, 30))).isZero();
        assertThat(court.slotCount(at(WEDNESDAY, 5, 0), at(WEDNESDAY, 6, 0))).isZero();
        assertThat(court.slotCount(at(WEDNESDAY, 21, 0), at(WEDNESDAY, 23, 0))).isZero();
        assertThat(court.slotCount(at(WEDNESDAY, 8, 0), at(WEDNESDAY, 6, 0))).isZero();
        assertThat(court.slotCount(at(WEDNESDAY, 21, 0), at(SATURDAY, 7, 0))).isZero();
    }

    @Test
    void pricingAnOffGridRangeIsRejected() {
        assertThatThrownBy(() -> court().priceFor(at(WEDNESDAY, 6, 30), at(WEDNESDAY, 7, 30)))
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.INVALID_SLOT));
    }

    @Test
    void aCourtSupportsOnlyTheSportsItWasGiven() {
        var court = court();

        assertThat(court.supports(CRICKET)).isTrue();
        assertThat(court.supports(FOOTBALL)).isTrue();
        assertThat(court.supports(BADMINTON)).isFalse();
        assertThat(court.supports(null)).isFalse();
    }

    @Test
    void duplicateSportsAreCollapsedAndOrderIsKept() {
        var s = courtSpec();
        var court = Court.create(venue(), new Court.Spec(s.name(), s.surface(), List.of(FOOTBALL, CRICKET, FOOTBALL), 60, s.basePrice(), null, null, null, null), NOW);

        assertThat(court.getSportIds()).containsExactly(FOOTBALL, CRICKET);
    }

    @Test
    void invalidCourtDetailsAreRejected() {
        assertInvalid(s -> change(s, " ", s.sportIds(), 60, s.basePrice(), null, null, null));
        assertInvalid(s -> change(s, s.name(), List.of(), 60, s.basePrice(), null, null, null));
        assertInvalid(s -> change(s, s.name(), java.util.Arrays.asList(CRICKET, null), 60, s.basePrice(), null, null, null));
        assertInvalid(s -> change(s, s.name(), s.sportIds(), 10, s.basePrice(), null, null, null));
        assertInvalid(s -> change(s, s.name(), s.sportIds(), 17 * 60, s.basePrice(), null, null, null));
        assertInvalid(s -> change(s, s.name(), s.sportIds(), 60, new BigDecimal("-1"), null, null, null));
        assertInvalid(s -> change(s, s.name(), s.sportIds(), 60, new BigDecimal("10.005"), null, null, null));
        assertInvalid(s -> change(s, s.name(), s.sportIds(), 60, s.basePrice(), new BigDecimal("2000"), null, null));
        assertInvalid(s -> change(s, s.name(), s.sportIds(), 60, s.basePrice(), new BigDecimal("2000"), LocalTime.of(20, 0), LocalTime.of(19, 0)));
        assertInvalid(s -> change(s, s.name(), manySports(13), 60, s.basePrice(), null, null, null));
    }

    @Test
    void updatingReplacesTheDetailsAndRevalidatesThem() {
        var court = court();
        court.update(new Court.Spec("Renamed", null, List.of(BADMINTON), 120, new BigDecimal("3000"), null, null, null, null), NOW.plusSeconds(60));

        assertThat(court.getName()).isEqualTo("Renamed");
        assertThat(court.supports(BADMINTON)).isTrue();
        assertThat(court.supports(CRICKET)).isFalse();
        assertThat(court.slotsOn(WEDNESDAY)).hasSize(8);
        assertThatThrownBy(() -> court.update(new Court.Spec("", null, List.of(BADMINTON), 120, BigDecimal.TEN, null, null, null, null), NOW))
            .isInstanceOf(GroundDomainException.class);
    }

    private static BigDecimal price(Court court, LocalDate date, int hour) {
        return court.priceFor(at(date, hour, 0), at(date, hour + 1, 0));
    }

    private static Court.Spec withSlot(Court.Spec s, int minutes) {
        return new Court.Spec(s.name() + " B", s.surface(), s.sportIds(), minutes, s.basePrice(), null, null, null, null);
    }

    private static List<UUID> manySports(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private static Court.Spec change(Court.Spec s, String name, List<UUID> sports, int slotMinutes, BigDecimal base, BigDecimal peakPrice, LocalTime peakFrom, LocalTime peakTo) {
        return new Court.Spec(name, s.surface(), sports, slotMinutes, base, null, peakPrice, peakFrom, peakTo);
    }

    private static void assertInvalid(UnaryOperator<Court.Spec> change) {
        assertThatThrownBy(() -> Court.create(venue(), change.apply(courtSpec()), NOW))
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.VALIDATION_ERROR));
    }
}
