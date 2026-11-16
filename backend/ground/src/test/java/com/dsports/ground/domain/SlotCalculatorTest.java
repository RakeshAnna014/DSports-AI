package com.dsports.ground.domain;

import com.dsports.ground.domain.model.Slot;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.ground.domain.policy.SlotCalculator;
import com.dsports.ground.domain.policy.SlotCalculator.Reason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.ground;
import static org.assertj.core.api.Assertions.assertThat;

class SlotCalculatorTest {

    private static final BookingPolicy POLICY = BookingPolicy.defaults();

    private List<SlotCalculator.SlotAvailability> day(List<Slot> bookings, List<Slot> blackouts, Instant now) {
        return SlotCalculator.availability(ground(), WEDNESDAY, bookings, blackouts, now, POLICY);
    }

    private static SlotCalculator.SlotAvailability at(List<SlotCalculator.SlotAvailability> day, int hour) {
        return day.stream().filter(s -> s.slot().start().equals(GroundFixtures.at(WEDNESDAY, hour, 0))).findFirst().orElseThrow();
    }

    @Test
    void anEmptyDayHasEveryGridSlotAvailableWithItsPrice() {
        var day = day(List.of(), List.of(), NOW);

        assertThat(day).hasSize(16).allMatch(SlotCalculator.SlotAvailability::available);
        assertThat(at(day, 9).price()).isEqualByComparingTo("1000");
        assertThat(at(day, 18).price()).isEqualByComparingTo("2000");
    }

    @Test
    void aMultiSlotBookingBlocksEverySlotItCoversButNotTheNeighbours() {
        var day = day(List.of(new Slot(GroundFixtures.at(WEDNESDAY, 10, 0), GroundFixtures.at(WEDNESDAY, 12, 0))), List.of(), NOW);

        assertThat(at(day, 9).available()).isTrue();
        assertThat(at(day, 10).reason()).isEqualTo(Reason.BOOKED);
        assertThat(at(day, 11).reason()).isEqualTo(Reason.BOOKED);
        assertThat(at(day, 12).available()).isTrue();
    }

    @Test
    void aBlackoutBlocksTheSlotsItOverlapsEvenPartially() {
        var day = day(List.of(), List.of(new Slot(GroundFixtures.at(WEDNESDAY, 13, 30), GroundFixtures.at(WEDNESDAY, 14, 30))), NOW);

        assertThat(at(day, 13).reason()).isEqualTo(Reason.BLACKED_OUT);
        assertThat(at(day, 14).reason()).isEqualTo(Reason.BLACKED_OUT);
        assertThat(at(day, 15).available()).isTrue();
    }

    @Test
    void slotsInsideTheMinimumLeadTimeAreTooSoon() {
        var now = GroundFixtures.at(WEDNESDAY, 9, 30);
        var day = day(List.of(), List.of(), now);

        assertThat(at(day, 9).reason()).isEqualTo(Reason.TOO_SOON);
        assertThat(at(day, 10).reason()).isEqualTo(Reason.TOO_SOON);
        assertThat(at(day, 11).available()).isTrue();
    }

    @Test
    void slotsBeyondTheBookingWindowAreTooFar() {
        var day = day(List.of(), List.of(), GroundFixtures.at(WEDNESDAY, 9, 0).minus(Duration.ofDays(31)));

        assertThat(day).allMatch(s -> s.reason() == Reason.TOO_FAR);
    }

    @Test
    void aBackToBackBookingDoesNotBlockTheNextSlot() {
        var day = day(List.of(new Slot(GroundFixtures.at(WEDNESDAY, 8, 0), GroundFixtures.at(WEDNESDAY, 9, 0))), List.of(), NOW);

        assertThat(at(day, 8).reason()).isEqualTo(Reason.BOOKED);
        assertThat(at(day, 9).available()).isTrue();
    }
}
