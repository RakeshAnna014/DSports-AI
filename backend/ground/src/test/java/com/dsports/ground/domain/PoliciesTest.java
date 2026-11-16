package com.dsports.ground.domain;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.ground.domain.policy.CancellationPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoliciesTest {

    private static final Instant NOW = Instant.parse("2026-11-16T04:30:00Z");
    private final CancellationPolicy cancellation = CancellationPolicy.defaults();
    private final BookingPolicy booking = BookingPolicy.defaults();

    @Test
    void moreThan24HoursAheadIsRefundedInFull() {
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofHours(24)).plusSeconds(1), NOW, CancelledBy.CUSTOMER)).isEqualTo(100);
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofDays(10)), NOW, CancelledBy.CUSTOMER)).isEqualTo(100);
    }

    @Test
    void between6And24HoursInclusiveIsRefundedByHalf() {
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofHours(24)), NOW, CancelledBy.CUSTOMER)).isEqualTo(50);
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofHours(12)), NOW, CancelledBy.CUSTOMER)).isEqualTo(50);
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofHours(6)), NOW, CancelledBy.CUSTOMER)).isEqualTo(50);
    }

    @Test
    void under6HoursIsNotRefunded() {
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofHours(6)).minusSeconds(1), NOW, CancelledBy.CUSTOMER)).isZero();
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofMinutes(5)), NOW, CancelledBy.CUSTOMER)).isZero();
    }

    @Test
    void anOwnerCancellationIsAlwaysRefundedInFullWhateverThePolicySays() {
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofMinutes(5)), NOW, CancelledBy.OWNER)).isEqualTo(100);
        assertThat(cancellation.refundPercent(NOW.plus(Duration.ofHours(12)), NOW, CancelledBy.OWNER)).isEqualTo(100);
    }

    @Test
    void aBookingMustStartAtLeastOneHourAheadAndAtMost30DaysAhead() {
        assertThatCode(() -> booking.check(NOW.plus(Duration.ofHours(1)), NOW, 1)).doesNotThrowAnyException();
        assertThatCode(() -> booking.check(NOW.plus(Duration.ofDays(30)), NOW, 1)).doesNotThrowAnyException();
        assertCode(() -> booking.check(NOW.plus(Duration.ofMinutes(59)), NOW, 1), GroundErrorCode.BOOKING_TOO_SOON);
        assertCode(() -> booking.check(NOW.plus(Duration.ofDays(30)).plusSeconds(1), NOW, 1), GroundErrorCode.BOOKING_TOO_FAR);
    }

    @Test
    void aBookingIsOneToMaxSlots() {
        assertThatCode(() -> booking.check(NOW.plus(Duration.ofDays(1)), NOW, 4)).doesNotThrowAnyException();
        assertCode(() -> booking.check(NOW.plus(Duration.ofDays(1)), NOW, 5), GroundErrorCode.INVALID_SLOT);
        assertCode(() -> booking.check(NOW.plus(Duration.ofDays(1)), NOW, 0), GroundErrorCode.INVALID_SLOT);
    }

    private static void assertCode(Runnable action, GroundErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
