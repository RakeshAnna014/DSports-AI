package com.dsports.ground.application;

import com.dsports.ground.application.usecase.GetBookingUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase.Command;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Blackout;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.BookingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.dsports.ground.domain.GroundFixtures.BADMINTON;
import static com.dsports.ground.domain.GroundFixtures.CRICKET;
import static com.dsports.ground.domain.GroundFixtures.CUSTOMER;
import static com.dsports.ground.domain.GroundFixtures.FOOTBALL;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.court;
import static com.dsports.ground.domain.GroundFixtures.courtSpec;
import static com.dsports.ground.domain.GroundFixtures.venue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldBookingUseCaseTest {

    private static final UUID OTHER_CUSTOMER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID SQUASH = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    private final Fakes.Venues venues = new Fakes.Venues();
    private final Fakes.Courts courts = new Fakes.Courts();
    private final Fakes.Bookings bookings = new Fakes.Bookings();
    private final Fakes.Blackouts blackouts = new Fakes.Blackouts();
    private final Fakes.Owners owners = new Fakes.Owners();
    private final Fakes.Sports sports = new Fakes.Sports().with(CRICKET, "Cricket", true).with(FOOTBALL, "Football", true)
        .with(BADMINTON, "Badminton", true).with(SQUASH, "Squash", false);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final HoldBookingUseCase hold = hold(BookingPolicy.defaults());
    private Venue venue;
    private Court court;

    private HoldBookingUseCase hold(BookingPolicy policy) {
        return new HoldBookingUseCase(venues, courts, bookings, blackouts, owners, sports, Fakes.presenter(venues, courts, sports), policy,
            Duration.ofMinutes(10), 2, Fakes.IMMEDIATE, clock);
    }

    @BeforeEach
    void setUp() {
        venues.courts = courts;
        venue = venue();
        venues.save(venue).block();
        court = court(venue);
        courts.save(court).block();
        owners.withRole.add(OWNER);
    }

    private Command command(UUID user, int fromHour, int toHour, String key) {
        return new Command(user, court.getId(), CRICKET, at(WEDNESDAY, fromHour, 0), at(WEDNESDAY, toHour, 0), key);
    }

    private Court secondCourt() {
        var s = courtSpec();
        var second = Court.create(venue, new Court.Spec("Court 2", "wood", s.sportIds(), 60, s.basePrice(), s.weekendPrice(), s.peakPrice(), s.peakFrom(), s.peakTo()), NOW);
        courts.save(second).block();
        return second;
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
        assertThat(booking.venueName()).isEqualTo("Green Park Sports Hub");
        assertThat(booking.courtName()).isEqualTo("Court 1");
        assertThat(booking.sportName()).isEqualTo("Cricket");
        assertThat(booking.sportId()).isEqualTo(CRICKET);
        assertThat(bookings.store).hasSize(1);
    }

    @Test
    void theCustomerLockIsTakenBeforeTheVenueLockSoTheyCanNeverDeadlock() {
        hold.execute(command(CUSTOMER, 9, 10, "k1")).block();

        assertThat(bookings.calls).containsExactly("user", "venue");
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
    void theSameKeyAndTimeForADifferentSportIsADifferentRequest() {
        hold.execute(command(CUSTOMER, 9, 10, "same")).block();

        var otherSport = new Command(CUSTOMER, court.getId(), FOOTBALL, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "same");
        assertCode(() -> hold.execute(otherSport).block(), GroundErrorCode.IDEMPOTENCY_KEY_REUSED);
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
    void aMissingSportOrCourtIsRejectedBeforeAnyLockIsTaken() {
        assertCode(() -> hold.execute(new Command(CUSTOMER, court.getId(), null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "k")).block(), GroundErrorCode.VALIDATION_ERROR);
        assertCode(() -> hold.execute(new Command(CUSTOMER, null, CRICKET, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "k")).block(), GroundErrorCode.VALIDATION_ERROR);
        assertThat(bookings.calls).isEmpty();
    }

    @Test
    void aMissingOrInactiveCourtOrVenueIsNotFound() {
        assertCode(() -> hold.execute(new Command(CUSTOMER, UUID.randomUUID(), CRICKET, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "k")).block(), GroundErrorCode.COURT_NOT_FOUND);

        court.deactivate(NOW);
        assertCode(() -> hold.execute(command(CUSTOMER, 9, 10, "k2")).block(), GroundErrorCode.COURT_NOT_FOUND);

        court.activate(NOW);
        venue.deactivate(NOW);
        assertCode(() -> hold.execute(command(CUSTOMER, 9, 10, "k3")).block(), GroundErrorCode.VENUE_NOT_FOUND);
    }

    @Test
    void aSportTheCourtDoesNotOfferIsRefusedWithoutAnythingSaved() {
        var badminton = new Command(CUSTOMER, court.getId(), BADMINTON, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "k");

        assertCode(() -> hold.execute(badminton).block(), GroundErrorCode.SPORT_NOT_SUPPORTED);
        assertThat(bookings.store).isEmpty();
    }

    @Test
    void aSportThatIsNotBookableInTheCatalogueIsRefusedEvenIfTheCourtStillListsIt() {
        var s = courtSpec();
        court.update(new Court.Spec(s.name(), s.surface(), java.util.List.of(CRICKET, SQUASH), 60, s.basePrice(), null, null, null, null), NOW);
        var squash = new Command(CUSTOMER, court.getId(), SQUASH, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "k");

        assertCode(() -> hold.execute(squash).block(), GroundErrorCode.SPORT_NOT_BOOKABLE);
        assertCode(() -> hold.execute(new Command(CUSTOMER, court.getId(), UUID.randomUUID(), at(WEDNESDAY, 9, 0), at(WEDNESDAY, 10, 0), "k2")).block(),
            GroundErrorCode.SPORT_NOT_BOOKABLE);
        assertThat(bookings.store).isEmpty();
    }

    @Test
    void aVenueWhoseOwnerLostTheRoleTakesNoNewBookings() {
        owners.withRole.remove(OWNER);

        assertCode(() -> hold.execute(command(CUSTOMER, 9, 10, "k")).block(), GroundErrorCode.VENUE_NOT_ACCEPTING_BOOKINGS);
        assertThat(bookings.store).isEmpty();

        owners.withRole.add(OWNER);
        assertThat(hold.execute(command(CUSTOMER, 9, 10, "k2")).block().replayed()).isFalse();
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
            var lapsed = Booking.reconstitute(UUID.randomUUID(), "GB-9" + i, venue.getId(), court.getId(), CRICKET, CUSTOMER, at(WEDNESDAY, 6 + i, 0), at(WEDNESDAY, 7 + i, 0),
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
    void theSameCourtAtTheSameTimeForAnotherSportIsTakenToo() {
        hold.execute(command(CUSTOMER, 17, 18, "a")).block();

        var football = new Command(OTHER_CUSTOMER, court.getId(), FOOTBALL, at(WEDNESDAY, 17, 0), at(WEDNESDAY, 18, 0), "b");
        assertCode(() -> hold.execute(football).block(), GroundErrorCode.SLOT_UNAVAILABLE);
        assertThat(bookings.store).hasSize(1);
    }

    @Test
    void twoCourtsOfOneVenueCanBeBookedAtTheSameTime() {
        var second = secondCourt();

        var first = hold.execute(command(CUSTOMER, 17, 18, "a")).block();
        var other = hold.execute(new Command(OTHER_CUSTOMER, second.getId(), CRICKET, at(WEDNESDAY, 17, 0), at(WEDNESDAY, 18, 0), "b")).block();

        assertThat(first.booking().courtId()).isEqualTo(court.getId());
        assertThat(other.booking().courtId()).isEqualTo(second.getId());
        assertThat(other.booking().venueId()).isEqualTo(first.booking().venueId());
        assertThat(bookings.store).hasSize(2);
    }

    @Test
    void aBackToBackSlotIsAvailable() {
        hold.execute(command(CUSTOMER, 17, 18, "a")).block();

        assertThat(hold.execute(command(OTHER_CUSTOMER, 18, 19, "b")).block().replayed()).isFalse();
        assertThat(hold.execute(command(OTHER_CUSTOMER, 16, 17, "c")).block().replayed()).isFalse();
    }

    @Test
    void aVenueWideBlackoutBlocksEveryCourtButACourtBlackoutOnlyThatCourt() {
        var second = secondCourt();
        blackouts.save(Blackout.create(venue.getId(), null, at(WEDNESDAY, 9, 0), at(WEDNESDAY, 11, 0), "Maintenance", OWNER, NOW)).block();
        blackouts.save(Blackout.create(venue.getId(), second.getId(), at(WEDNESDAY, 13, 0), at(WEDNESDAY, 14, 0), "Re-lining", OWNER, NOW)).block();

        assertCode(() -> hold.execute(command(CUSTOMER, 10, 11, "a")).block(), GroundErrorCode.SLOT_UNAVAILABLE);
        assertCode(() -> hold.execute(new Command(CUSTOMER, second.getId(), CRICKET, at(WEDNESDAY, 10, 0), at(WEDNESDAY, 11, 0), "b")).block(), GroundErrorCode.SLOT_UNAVAILABLE);
        assertThat(hold.execute(command(CUSTOMER, 13, 14, "c")).block().replayed()).isFalse();
        assertCode(() -> hold.execute(new Command(CUSTOMER, second.getId(), CRICKET, at(WEDNESDAY, 13, 0), at(WEDNESDAY, 14, 0), "d")).block(), GroundErrorCode.SLOT_UNAVAILABLE);
    }

    @Test
    void anExpiredCancelledOrFinishedBookingDoesNotBlockTheSlot() {
        var first = hold.execute(command(CUSTOMER, 9, 10, "a")).block().booking();
        bookings.store.get(first.id()).expire(NOW);

        assertThat(hold.execute(command(OTHER_CUSTOMER, 9, 10, "b")).block().replayed()).isFalse();
    }

    @Test
    void theBookingRulesApplyToTheHold() {
        assertCode(() -> hold.execute(new Command(CUSTOMER, court.getId(), CRICKET, NOW.plus(Duration.ofMinutes(30)), NOW.plus(Duration.ofMinutes(90)), "a")).block(),
            GroundErrorCode.INVALID_SLOT);
        assertCode(() -> hold.execute(command(CUSTOMER, 5, 6, "b")).block(), GroundErrorCode.INVALID_SLOT);
        assertCode(() -> hold.execute(command(CUSTOMER, 6, 11, "c")).block(), GroundErrorCode.INVALID_SLOT);
        var tooSoon = hold(new BookingPolicy(Duration.ofDays(5), Duration.ofDays(30), 4));
        assertCode(() -> tooSoon.execute(command(CUSTOMER, 9, 10, "d")).block(), GroundErrorCode.BOOKING_TOO_SOON);
        assertThat(bookings.store).isEmpty();
    }

    @Test
    void aCustomerSeesOnlyTheirOwnBooking() {
        var booking = hold.execute(command(CUSTOMER, 9, 10, "a")).block().booking();
        var get = new GetBookingUseCase(bookings, Fakes.presenter(venues, courts, sports));

        assertThat(get.execute(CUSTOMER, booking.id()).block().bookingNumber()).isEqualTo(booking.bookingNumber());
        assertCode(() -> get.execute(OTHER_CUSTOMER, booking.id()).block(), GroundErrorCode.BOOKING_NOT_OWNED_BY_USER);
        assertCode(() -> get.execute(CUSTOMER, UUID.randomUUID()).block(), GroundErrorCode.BOOKING_NOT_FOUND);
    }

    private static void assertCode(Callable<?> action, GroundErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
