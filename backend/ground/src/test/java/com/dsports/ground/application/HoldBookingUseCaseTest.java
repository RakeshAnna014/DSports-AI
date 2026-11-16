package com.dsports.ground.application;

import com.dsports.ground.application.usecase.GetBookingUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase.Command;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.GroundBlackout;
import com.dsports.ground.domain.model.Slot;
import com.dsports.ground.domain.policy.BookingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.dsports.ground.domain.GroundFixtures.CUSTOMER;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.ground;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldBookingUseCaseTest {

    private static final UUID OTHER_CUSTOMER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private final Fakes.Grounds grounds = new Fakes.Grounds();
    private final Fakes.Bookings bookings = new Fakes.Bookings();
    private final Fakes.Blackouts blackouts = new Fakes.Blackouts();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final HoldBookingUseCase hold = new HoldBookingUseCase(grounds, bookings, blackouts, BookingPolicy.defaults(), Duration.ofMinutes(10), 2,
        Fakes.IMMEDIATE, clock);
    private Ground ground;

    @BeforeEach
    void setUp() {
        ground = ground();
        grounds.save(ground).block();
    }

    private Command command(UUID user, int fromHour, int toHour, String key) {
        return new Command(user, ground.getId(), at(WEDNESDAY, fromHour, 0), at(WEDNESDAY, toHour, 0), key);
    }

    @Test
    void holdingASlotCreatesAHeldBookingWithAPriceSnapshotAndADeadline() {
        var result = hold.execute(command(CUSTOMER, 17, 19, "k1")).block();

        assertThat(result.replayed()).isFalse();
        var booking = result.booking();
        assertThat(booking.status()).isEqualTo("HELD");
        assertThat(booking.price()).isEqualByComparingTo("3000");
        assertThat(booking.heldUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(booking.bookingNumber()).isEqualTo("GB-000001");
        assertThat(booking.groundName()).isEqualTo("Green Park Turf");
        assertThat(bookings.store).hasSize(1);
    }

    @Test
    void theCustomerLockIsTakenBeforeTheGroundLockSoTheyCanNeverDeadlock() {
        hold.execute(command(CUSTOMER, 9, 10, "k1")).block();

        assertThat(bookings.calls).containsExactly("user", "ground");
    }

    @Test
    void repeatingTheSameRequestWithTheSameKeyReturnsTheOriginalBooking() {
        var first = hold.execute(command(CUSTOMER, 9, 10, "same")).block();
        var again = hold.execute(command(CUSTOMER, 9, 10, "same")).block();

        assertThat(again.replayed()).isTrue();
        assertThat(again.booking().id()).isEqualTo(first.booking().id());
        assertThat(bookings.store).hasSize(1);
        assertThat(bookings.locks).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        hold.execute(command(CUSTOMER, 9, 10, "same")).block();

        assertCode(() -> hold.execute(command(CUSTOMER, 11, 12, "same")).block(), GroundErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(bookings.store).hasSize(1);
    }

    @Test
    void theSameKeyFromAnotherCustomerIsAnIndependentRequest() {
        hold.execute(command(CUSTOMER, 9, 10, "same")).block();

        var other = hold.execute(command(OTHER_CUSTOMER, 11, 12, "same")).block();

        assertThat(other.replayed()).isFalse();
        assertThat(bookings.store).hasSize(2);
    }

    @Test
    void aMissingKeyIsRejectedBeforeAnyLockIsTaken() {
        assertCode(() -> hold.execute(command(CUSTOMER, 9, 10, null)).block(), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> hold.execute(command(CUSTOMER, 9, 10, "  ")).block(), GroundErrorCode.VALIDATION_ERROR);
        assertThat(bookings.calls).isEmpty();
    }

    @Test
    void aMissingOrInactiveGroundIsNotFound() {
        assertCode(() -> hold.execute(new Command(CUSTOMER, UUID.randomUUID(), at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "k")).block(), GroundErrorCode.GROUND_NOT_FOUND);

        ground.deactivate(NOW);
        assertCode(() -> hold.execute(command(CUSTOMER, 9, 10, "k2")).block(), GroundErrorCode.GROUND_NOT_FOUND);
    }

    @Test
    void aCustomerCanHoldAtMostTwoUnpaidSlotsAtOnce() {
        hold.execute(command(CUSTOMER, 9, 10, "a")).block();
        hold.execute(command(CUSTOMER, 11, 12, "b")).block();

        assertCode(() -> hold.execute(command(CUSTOMER, 13, 14, "c")).block(), GroundErrorCode.HOLD_LIMIT_REACHED);
        assertThat(bookings.store).hasSize(2);
        assertThat(hold.execute(command(OTHER_CUSTOMER, 13, 14, "d")).block().replayed()).isFalse();
    }

    @Test
    void theLimitFreesUpWhenAHoldIsPaidExpiredOrCancelled() {
        var first = hold.execute(command(CUSTOMER, 9, 10, "a")).block().booking();
        var second = hold.execute(command(CUSTOMER, 11, 12, "b")).block().booking();

        bookings.store.get(first.id()).confirm(UUID.randomUUID(), NOW);
        assertThat(hold.execute(command(CUSTOMER, 13, 14, "c")).block().replayed()).isFalse();

        bookings.store.get(second.id()).expire(NOW);
        assertThat(hold.execute(command(CUSTOMER, 15, 16, "d")).block().replayed()).isFalse();
    }

    @Test
    void aHoldWhosePaymentWindowHasPassedNoLongerCountsAgainstTheLimit() {
        for (int i = 0; i < 2; i++) {
            var lapsed = Booking.reconstitute(UUID.randomUUID(), "GB-9" + i, ground.getId(), CUSTOMER, at(WEDNESDAY, 6 + i, 0), at(WEDNESDAY, 7 + i, 0),
                new java.math.BigDecimal("1000"), "INR", BookingStatus.HELD, null, "old" + i, NOW.minusSeconds(60), null, null, 1, NOW.minusSeconds(700), NOW.minusSeconds(700));
            bookings.store.put(lapsed.getId(), lapsed);
        }

        var result = hold.execute(command(CUSTOMER, 14, 15, "fresh")).block();

        assertThat(result.replayed()).isFalse();
    }

    @Test
    void aReplayIsNotBlockedByTheHoldLimit() {
        var first = hold.execute(command(CUSTOMER, 9, 10, "a")).block();
        hold.execute(command(CUSTOMER, 11, 12, "b")).block();

        var replay = hold.execute(command(CUSTOMER, 9, 10, "a")).block();

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.booking().id()).isEqualTo(first.booking().id());
    }

    @Test
    void aTakenSlotIsRefusedWithoutAnythingSaved() {
        hold.execute(command(CUSTOMER, 17, 19, "a")).block();

        assertCode(() -> hold.execute(command(OTHER_CUSTOMER, 18, 20, "b")).block(), GroundErrorCode.SLOT_UNAVAILABLE);
        assertCode(() -> hold.execute(command(OTHER_CUSTOMER, 17, 18, "c")).block(), GroundErrorCode.SLOT_UNAVAILABLE);
        assertThat(bookings.store).hasSize(1);
    }

    @Test
    void aBackToBackSlotIsAvailable() {
        hold.execute(command(CUSTOMER, 17, 18, "a")).block();

        assertThat(hold.execute(command(OTHER_CUSTOMER, 18, 19, "b")).block().replayed()).isFalse();
        assertThat(hold.execute(command(OTHER_CUSTOMER, 16, 17, "c")).block().replayed()).isFalse();
    }

    @Test
    void aBlackoutBlocksTheHold() {
        blackouts.save(GroundBlackout.create(ground.getId(), at(WEDNESDAY, 9, 0), at(WEDNESDAY, 11, 0), "Maintenance", OWNER, NOW)).block();

        assertCode(() -> hold.execute(command(CUSTOMER, 10, 11, "a")).block(), GroundErrorCode.SLOT_UNAVAILABLE);
        assertThat(hold.execute(command(CUSTOMER, 11, 12, "b")).block().replayed()).isFalse();
    }

    @Test
    void anExpiredCancelledOrFinishedBookingDoesNotBlockTheSlot() {
        var first = hold.execute(command(CUSTOMER, 9, 10, "a")).block().booking();
        bookings.store.get(first.id()).expire(NOW);

        assertThat(hold.execute(command(OTHER_CUSTOMER, 9, 10, "b")).block().replayed()).isFalse();
    }

    @Test
    void theBookingRulesApplyToTheHold() {
        assertCode(() -> hold.execute(new Command(CUSTOMER, ground.getId(), NOW.plus(Duration.ofMinutes(30)), NOW.plus(Duration.ofMinutes(90)), "a")).block(),
            GroundErrorCode.INVALID_SLOT);
        assertCode(() -> hold.execute(command(CUSTOMER, 5, 6, "b")).block(), GroundErrorCode.INVALID_SLOT);
        assertCode(() -> hold.execute(command(CUSTOMER, 6, 11, "c")).block(), GroundErrorCode.INVALID_SLOT);
        var tooSoon = new HoldBookingUseCase(grounds, bookings, blackouts, new BookingPolicy(Duration.ofDays(5), Duration.ofDays(30), 4), Duration.ofMinutes(10), 2,
            Fakes.IMMEDIATE, clock);
        assertCode(() -> tooSoon.execute(command(CUSTOMER, 9, 10, "d")).block(), GroundErrorCode.BOOKING_TOO_SOON);
        assertThat(bookings.store).isEmpty();
    }

    @Test
    void aCustomerSeesOnlyTheirOwnBooking() {
        var booking = hold.execute(command(CUSTOMER, 9, 10, "a")).block().booking();
        var get = new GetBookingUseCase(bookings, grounds);

        assertThat(get.execute(CUSTOMER, booking.id()).block().bookingNumber()).isEqualTo(booking.bookingNumber());
        assertCode(() -> get.execute(OTHER_CUSTOMER, booking.id()).block(), GroundErrorCode.BOOKING_NOT_OWNED_BY_USER);
        assertCode(() -> get.execute(CUSTOMER, UUID.randomUUID()).block(), GroundErrorCode.BOOKING_NOT_FOUND);
    }

    private static void assertCode(Callable<?> action, GroundErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
