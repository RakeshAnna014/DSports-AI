package com.dsports.ground.domain;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.policy.BookingPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.dsports.ground.domain.GroundFixtures.CUSTOMER;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.ground;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingTest {

    private static final Duration HOLD = Duration.ofMinutes(10);
    private static final BookingPolicy POLICY = BookingPolicy.defaults();

    private final Instant start = at(WEDNESDAY, 17, 0);
    private final Instant end = at(WEDNESDAY, 19, 0);

    private Booking held() {
        return Booking.hold(ground(), CUSTOMER, start, end, "GB-000001", "key-1", NOW, HOLD, POLICY);
    }

    @Test
    void holdingReservesTheSlotWithAPriceSnapshotAndADeadline() {
        var booking = held();

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(booking.getPrice()).isEqualByComparingTo("3000");
        assertThat(booking.getCurrency()).isEqualTo("INR");
        assertThat(booking.getHeldUntil()).isEqualTo(NOW.plus(HOLD));
        assertThat(booking.getPaymentId()).isNull();
        assertThat(booking.isPayable(NOW)).isTrue();
    }

    @Test
    void theHoldIsRejectedWhenTheRequestBreaksARule() {
        var ground = ground();
        assertCode(() -> Booking.hold(ground, CUSTOMER, NOW.plus(Duration.ofMinutes(30)), NOW.plus(Duration.ofMinutes(90)), "GB-1", "k", NOW, HOLD, POLICY),
            GroundErrorCode.INVALID_SLOT);
        assertCode(() -> Booking.hold(ground, CUSTOMER, at(WEDNESDAY, 6, 30), at(WEDNESDAY, 7, 30), "GB-1", "k", NOW, HOLD, POLICY), GroundErrorCode.INVALID_SLOT);
        assertCode(() -> Booking.hold(ground, CUSTOMER, at(WEDNESDAY, 6, 0), at(WEDNESDAY, 11, 0), "GB-1", "k", NOW, HOLD, POLICY), GroundErrorCode.INVALID_SLOT);
        assertCode(() -> Booking.hold(ground, CUSTOMER, start, end, "GB-1", " ", NOW, HOLD, POLICY), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> Booking.hold(ground, CUSTOMER, start, end, "GB-1", "k", start.minus(Duration.ofMinutes(59)), HOLD, POLICY), GroundErrorCode.BOOKING_TOO_SOON);
        assertCode(() -> Booking.hold(ground, CUSTOMER, start, end, "GB-1", "k", start.minus(Duration.ofDays(31)), HOLD, POLICY), GroundErrorCode.BOOKING_TOO_FAR);
    }

    @Test
    void anInactiveGroundCannotBeBooked() {
        var ground = ground();
        ground.deactivate(NOW);

        assertCode(() -> Booking.hold(ground, CUSTOMER, start, end, "GB-1", "k", NOW, HOLD, POLICY), GroundErrorCode.GROUND_NOT_FOUND);
    }

    @Test
    void aLaterPriceChangeNeverChangesAnExistingBooking() {
        var ground = ground();
        var booking = Booking.hold(ground, CUSTOMER, start, end, "GB-1", "k", NOW, HOLD, POLICY);
        ground.update(new Ground.Spec("Green Park Turf", "12 Park Road", "Bengaluru", null, null, ground.getSpec().pitchType(), java.util.List.of(), java.util.List.of(),
            ground.getSpec().opensAt(), ground.getSpec().closesAt(), ground.getTimeZone(), 60, new java.math.BigDecimal("9999"), null, null, null, null), NOW);

        assertThat(booking.getPrice()).isEqualByComparingTo("3000");
    }

    @Test
    void aHeldBookingConfirmsOnceWithItsPayment() {
        var booking = held();
        var paymentId = UUID.randomUUID();

        booking.confirm(paymentId, NOW.plusSeconds(60));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPaymentId()).isEqualTo(paymentId);
        assertCode(() -> booking.confirm(UUID.randomUUID(), NOW), GroundErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void anExpiredOrCancelledBookingCanNoLongerBeConfirmed() {
        var expired = held();
        expired.expire(NOW.plus(HOLD));
        assertCode(() -> expired.confirm(UUID.randomUUID(), NOW), GroundErrorCode.INVALID_STATUS_TRANSITION);

        var cancelled = held();
        cancelled.cancel(CancelledBy.CUSTOMER, NOW);
        assertCode(() -> cancelled.confirm(UUID.randomUUID(), NOW), GroundErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void aPaymentCapturedAfterTheDeadlineButBeforeExpiryStillConfirms() {
        var booking = held();
        var afterDeadline = NOW.plus(HOLD).plusSeconds(30);

        assertThat(booking.isHoldLapsed(afterDeadline)).isTrue();
        assertThat(booking.isPayable(afterDeadline)).isFalse();
        booking.confirm(UUID.randomUUID(), afterDeadline);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void theHoldLapsesExactlyAtItsDeadline() {
        var booking = held();

        assertThat(booking.isHoldLapsed(booking.getHeldUntil().minusMillis(1))).isFalse();
        assertThat(booking.isHoldLapsed(booking.getHeldUntil())).isTrue();
    }

    @Test
    void onlyAHeldBookingCanExpire() {
        var booking = held();
        booking.confirm(UUID.randomUUID(), NOW);

        assertCode(() -> booking.expire(NOW), GroundErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void cancellingRecordsWhoAndWhenAndIsAllowedUntilTheStart() {
        var booking = held();
        booking.confirm(UUID.randomUUID(), NOW);

        booking.cancel(CancelledBy.OWNER, start.minusSeconds(1));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancelledBy()).isEqualTo(CancelledBy.OWNER);
        assertThat(booking.getCancelledAt()).isEqualTo(start.minusSeconds(1));
        assertThat(booking.getStatus().occupiesSlot()).isFalse();
    }

    @Test
    void aBookingThatHasStartedCannotBeCancelled() {
        var booking = held();
        booking.confirm(UUID.randomUUID(), NOW);

        assertCode(() -> booking.cancel(CancelledBy.CUSTOMER, start), GroundErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void aConfirmedBookingIsCompletedOrNoShowOnlyAfterItEnds() {
        var done = held();
        done.confirm(UUID.randomUUID(), NOW);
        assertCode(() -> done.complete(end.minusSeconds(1)), GroundErrorCode.INVALID_STATUS_TRANSITION);
        done.complete(end);
        assertThat(done.getStatus()).isEqualTo(BookingStatus.COMPLETED);

        var missed = held();
        missed.confirm(UUID.randomUUID(), NOW);
        missed.markNoShow(end.plusSeconds(1));
        assertThat(missed.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
    }

    @Test
    void aHeldBookingCannotBeCompleted() {
        assertCode(() -> held().complete(end.plusSeconds(1)), GroundErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void onlyHeldAndConfirmedBookingsOccupyTheSlot() {
        assertThat(BookingStatus.HELD.occupiesSlot()).isTrue();
        assertThat(BookingStatus.CONFIRMED.occupiesSlot()).isTrue();
        assertThat(BookingStatus.EXPIRED.occupiesSlot()).isFalse();
        assertThat(BookingStatus.CANCELLED.occupiesSlot()).isFalse();
        assertThat(BookingStatus.COMPLETED.occupiesSlot()).isFalse();
        assertThat(BookingStatus.NO_SHOW.occupiesSlot()).isFalse();
    }

    private static void assertCode(Runnable action, GroundErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
