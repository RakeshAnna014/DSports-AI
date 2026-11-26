package com.dsports.ground.application;

import com.dsports.ground.application.port.BookingPaymentPort;
import com.dsports.ground.application.usecase.CancelBookingUseCase;
import com.dsports.ground.application.usecase.CancelBookingUseCase.Command;
import com.dsports.ground.application.usecase.CancelBookingUseCase.Refund;
import com.dsports.ground.application.usecase.CompleteEndedBookingsUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.application.usecase.ListBookingsUseCase;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.ground.domain.policy.CancellationPolicy;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.dsports.ground.domain.GroundFixtures.CUSTOMER;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.CRICKET;
import static com.dsports.ground.domain.GroundFixtures.FOOTBALL;
import static com.dsports.ground.domain.GroundFixtures.court;
import static com.dsports.ground.domain.GroundFixtures.venue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelAndListBookingsTest {

    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Instant START = at(WEDNESDAY, 17, 0);

    private final Fakes.Venues venues = new Fakes.Venues();
    private final Fakes.Courts courts = new Fakes.Courts();
    private final Fakes.Owners owners = new Fakes.Owners();
    private final Fakes.Sports sports = new Fakes.Sports().with(CRICKET, "Cricket", true).with(FOOTBALL, "Football", true);
    private final Fakes.Bookings bookings = new Fakes.Bookings();
    private final Fakes.Blackouts blackouts = new Fakes.Blackouts();
    private final List<OutboxMessage> outbox = new ArrayList<>();
    private final Recorder payments = new Recorder();
    private Venue venue;
    private Court court;

    static final class Recorder implements BookingPaymentPort {
        final List<UUID> voided = new ArrayList<>();
        final List<UUID> autoRefunded = new ArrayList<>();
        final List<Object[]> requested = new ArrayList<>();
        final Set<UUID> captureInProgress = new HashSet<>();

        @Override
        public Mono<Void> voidOpenPayments(UUID bookingId) {
            if (captureInProgress.contains(bookingId)) {
                return Mono.error(new GroundDomainException(GroundErrorCode.PAYMENT_IN_PROGRESS, "capture in progress"));
            }
            return Mono.fromRunnable(() -> voided.add(bookingId));
        }

        @Override
        public Mono<Boolean> hasCapturedPayment(UUID bookingId) {
            return Mono.just(true);
        }

        @Override
        public Mono<Void> refundCapturedPayment(UUID paymentId, String reason) {
            return Mono.fromRunnable(() -> autoRefunded.add(paymentId));
        }

        @Override
        public Mono<Void> requestRefund(UUID paymentId, UUID requesterId, BigDecimal amount, String reason, String key) {
            return Mono.fromRunnable(() -> requested.add(new Object[] {paymentId, requesterId, amount, key}));
        }
    }

    @BeforeEach
    void setUp() {
        venue = venue();
        venues.save(venue).block();
        court = court(venue);
        courts.save(court).block();
        owners.withRole.add(OWNER);
    }

    private Clock clockAt(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
    }

    private CancelBookingUseCase cancelAt(Instant now) {
        return new CancelBookingUseCase(bookings, venues, Fakes.presenter(venues, courts, sports), payments, CancellationPolicy.defaults(), message -> Mono.fromRunnable(() -> outbox.add(message)),
            Fakes.IMMEDIATE, clockAt(now));
    }

    private ListBookingsUseCase listAt(Instant now) {
        return new ListBookingsUseCase(bookings, venues, Fakes.presenter(venues, courts, sports), Fakes.IMMEDIATE, clockAt(now));
    }

    private Booking held(UUID user, int fromHour, int toHour, String key) {
        var hold = new HoldBookingUseCase(venues, courts, bookings, blackouts, owners, sports, Fakes.presenter(venues, courts, sports), BookingPolicy.defaults(),
            Duration.ofMinutes(10), 10, Fakes.IMMEDIATE, clockAt(NOW));
        var id = hold.execute(new HoldBookingUseCase.Command(user, court.getId(), CRICKET, at(WEDNESDAY, fromHour, 0), at(WEDNESDAY, toHour, 0), key)).block().booking().id();
        return bookings.store.get(id);
    }

    private Booking confirmed(UUID user, int fromHour, int toHour, String key) {
        var booking = held(user, fromHour, toHour, key);
        booking.confirm(UUID.randomUUID(), NOW);
        return booking;
    }

    private Command byCustomer(Booking booking) {
        return new Command(CUSTOMER, booking.getId(), CancelledBy.CUSTOMER);
    }

    // ---- unpaid holds ------------------------------------------------------------------------------------------

    @Test
    void cancellingAHeldBookingReleasesTheSlotVoidsItsPaymentsAndNotifiesTheCustomer() {
        var booking = held(CUSTOMER, 17, 19, "a");

        var result = cancelAt(NOW).execute(byCustomer(booking)).block();

        assertThat(result.booking().status()).isEqualTo("CANCELLED");
        assertThat(result.booking().cancelledBy()).isEqualTo("CUSTOMER");
        assertThat(result.refund()).isEqualTo(Refund.NONE);
        assertThat(payments.voided).containsExactly(booking.getId());
        assertThat(payments.requested).isEmpty();
        assertThat(bookings.findOccupiedPeriods(court.getId(), at(WEDNESDAY, 6, 0), at(WEDNESDAY, 22, 0)).collectList().block()).isEmpty();
        assertThat(outbox).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo(OutboxMessageTypes.NOTIFY_BOOKING_CANCELLED);
            assertThat(m.payload()).containsEntry("userId", CUSTOMER.toString()).containsEntry("note", "");
        });
    }

    @Test
    void aHeldBookingWhosePaymentIsBeingCapturedCannotBeCancelled() {
        var booking = held(CUSTOMER, 17, 19, "a");
        payments.captureInProgress.add(booking.getId());

        var versionBefore = booking.getVersion();

        assertCode(() -> cancelAt(NOW).execute(byCustomer(booking)).block(), GroundErrorCode.PAYMENT_IN_PROGRESS);

        // Nothing was saved or queued (a real transaction would also discard the in-memory change).
        assertThat(booking.getVersion()).isEqualTo(versionBefore);
        assertThat(outbox).isEmpty();
        assertThat(payments.voided).isEmpty();
    }

    // ---- paid bookings: customer --------------------------------------------------------------------------------

    @Test
    void aCustomerCancellingMoreThan24HoursAheadFilesAFullRefundRequestForApproval() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");

        var result = cancelAt(NOW).execute(byCustomer(booking)).block();

        assertThat(result.refundPercent()).isEqualTo(100);
        assertThat(result.refundAmount()).isEqualByComparingTo("3000.00");
        assertThat(result.refund()).isEqualTo(Refund.REQUESTED);
        assertThat(payments.autoRefunded).isEmpty();
        assertThat(payments.requested).singleElement().satisfies(r -> {
            assertThat(r[0]).isEqualTo(booking.getPaymentId());
            assertThat(r[1]).isEqualTo(CUSTOMER);
            assertThat((BigDecimal) r[2]).isEqualByComparingTo("3000");
            assertThat(r[3]).isEqualTo("booking-cancel:" + booking.getId());
        });
        assertThat(outbox.get(0).payload().get("note")).contains("100% refund").contains("awaiting approval");
    }

    @Test
    void between6And24HoursAheadHalfIsRequested() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");

        var result = cancelAt(START.minus(Duration.ofHours(12))).execute(byCustomer(booking)).block();

        assertThat(result.refundPercent()).isEqualTo(50);
        assertThat(result.refundAmount()).isEqualByComparingTo("1500.00");
        assertThat(result.refund()).isEqualTo(Refund.REQUESTED);
    }

    @Test
    void under6HoursAheadNothingIsRefundedAndNoRequestIsFiled() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");

        var result = cancelAt(START.minus(Duration.ofHours(3))).execute(byCustomer(booking)).block();

        assertThat(result.refundPercent()).isZero();
        assertThat(result.refundAmount()).isEqualByComparingTo("0");
        assertThat(result.refund()).isEqualTo(Refund.NONE);
        assertThat(payments.requested).isEmpty();
        assertThat(payments.autoRefunded).isEmpty();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(outbox.get(0).payload().get("note")).contains("No refund applies");
    }

    // ---- paid bookings: owner -----------------------------------------------------------------------------------

    @Test
    void anOwnerCancellationIsRefundedInFullAutomaticallyEvenAtTheLastMinute() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");

        var result = cancelAt(START.minus(Duration.ofMinutes(10))).execute(new Command(OWNER, booking.getId(), CancelledBy.OWNER)).block();

        assertThat(result.refundPercent()).isEqualTo(100);
        assertThat(result.refundAmount()).isEqualByComparingTo("3000.00");
        assertThat(result.refund()).isEqualTo(Refund.STARTED);
        assertThat(payments.autoRefunded).containsExactly(booking.getPaymentId());
        assertThat(payments.requested).isEmpty();
        assertThat(booking.getCancelledBy()).isEqualTo(CancelledBy.OWNER);
        assertThat(outbox).singleElement().satisfies(m -> {
            assertThat(m.payload()).containsEntry("userId", CUSTOMER.toString());
            assertThat(m.payload().get("note")).contains("full refund").contains("automatically");
        });
    }

    @Test
    void anOwnerCancellingAHeldBookingJustReleasesIt() {
        var booking = held(CUSTOMER, 17, 19, "a");

        var result = cancelAt(NOW).execute(new Command(OWNER, booking.getId(), CancelledBy.OWNER)).block();

        assertThat(result.refund()).isEqualTo(Refund.NONE);
        assertThat(payments.voided).containsExactly(booking.getId());
    }

    // ---- who and when -------------------------------------------------------------------------------------------

    @Test
    void onlyTheBookingsCustomerOrTheVenuesOwnerMayCancel() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");

        assertCode(() -> cancelAt(NOW).execute(new Command(OTHER, booking.getId(), CancelledBy.CUSTOMER)).block(), GroundErrorCode.BOOKING_NOT_OWNED_BY_USER);
        assertCode(() -> cancelAt(NOW).execute(new Command(OTHER_OWNER, booking.getId(), CancelledBy.OWNER)).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertCode(() -> cancelAt(NOW).execute(new Command(CUSTOMER, UUID.randomUUID(), CancelledBy.CUSTOMER)).block(), GroundErrorCode.BOOKING_NOT_FOUND);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(payments.requested).isEmpty();
        assertThat(outbox).isEmpty();
    }

    @Test
    void aBookingThatHasStartedOrIsAlreadyCancelledCannotBeCancelled() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");
        assertCode(() -> cancelAt(START).execute(byCustomer(booking)).block(), GroundErrorCode.INVALID_STATUS_TRANSITION);

        cancelAt(NOW).execute(byCustomer(booking)).block();
        assertCode(() -> cancelAt(NOW).execute(byCustomer(booking)).block(), GroundErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(payments.requested).hasSize(1);
        assertThat(outbox).hasSize(1);
    }

    @Test
    void halfOfAnOddAmountKeepsItsPaise() {
        var booking = confirmed(CUSTOMER, 17, 18, "a");
        var priced = Booking.reconstitute(booking.getId(), booking.getBookingNumber(), booking.getVenueId(), booking.getCourtId(), booking.getSportId(), CUSTOMER, booking.getStartAt(), booking.getEndAt(),
            new BigDecimal("1001.00"), "INR", BookingStatus.CONFIRMED, booking.getPaymentId(), "a", booking.getHeldUntil(), null, null, 1, NOW, NOW);
        bookings.store.put(priced.getId(), priced);

        var result = cancelAt(START.minus(Duration.ofHours(12))).execute(byCustomer(priced)).block();

        assertThat(result.refundAmount()).isEqualByComparingTo("500.50");
    }

    // ---- listings and no-show -----------------------------------------------------------------------------------

    @Test
    void aCustomerSeesOnlyTheirOwnBookingsNewestFirstWithVenueCourtAndSportNames() {
        var first = held(CUSTOMER, 9, 10, "a");
        var second = Booking.reconstitute(UUID.randomUUID(), "GB-000099", venue.getId(), court.getId(), CRICKET, CUSTOMER, at(WEDNESDAY, 11, 0), at(WEDNESDAY, 12, 0), BigDecimal.TEN, "INR",
            BookingStatus.HELD, null, "b", NOW.plusSeconds(600), null, null, 1, NOW.plusSeconds(5), NOW);
        bookings.store.put(second.getId(), second);
        held(OTHER, 13, 14, "c");

        var mine = listAt(NOW).mine(CUSTOMER).block();

        assertThat(mine).extracting("bookingNumber").containsExactly("GB-000099", first.getBookingNumber());
        assertThat(mine).allSatisfy(b -> {
            assertThat(b.venueName()).isEqualTo("Green Park Sports Hub");
            assertThat(b.courtName()).isEqualTo("Court 1");
            assertThat(b.sportName()).isEqualTo("Cricket");
        });
    }

    @Test
    void anOwnerSeesBookingsOfTheirOwnVenueOnOneDayAndNoCustomerIdentity() {
        held(CUSTOMER, 9, 10, "a");
        held(OTHER, 11, 12, "b");

        var day = listAt(NOW).atVenue(OWNER, venue.getId(), null, WEDNESDAY).block();
        var other = listAt(NOW).atVenue(OWNER, venue.getId(), null, WEDNESDAY.plusDays(1)).block();

        assertThat(day).hasSize(2);
        assertThat(other).isEmpty();
        assertThat(BookingResultFields.names()).doesNotContain("userId");
        assertCode(() -> listAt(NOW).atVenue(OTHER_OWNER, venue.getId(), null, WEDNESDAY).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
    }

    @Test
    void anOwnerMarksANoShowOnlyAfterTheBookingEndedAndOnlyForAConfirmedBooking() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");
        var end = booking.getEndAt();

        assertCode(() -> listAt(end.minusSeconds(1)).markNoShow(OWNER, booking.getId()).block(), GroundErrorCode.INVALID_STATUS_TRANSITION);
        assertCode(() -> listAt(end).markNoShow(OTHER_OWNER, booking.getId()).block(), GroundErrorCode.VENUE_NOT_OWNED_BY_USER);
        assertThat(listAt(end).markNoShow(OWNER, booking.getId()).block().status()).isEqualTo("NO_SHOW");

        var unpaid = held(OTHER, 9, 10, "b");
        assertCode(() -> listAt(end).markNoShow(OWNER, unpaid.getId()).block(), GroundErrorCode.INVALID_STATUS_TRANSITION);
    }

    // ---- completion --------------------------------------------------------------------------------------------

    @Test
    void aConfirmedBookingIsCompletedOnlyAfterTheOwnersNoShowGracePeriod() {
        var booking = confirmed(CUSTOMER, 17, 19, "a");
        var job = new CompleteEndedBookingsUseCase(bookings, Fakes.IMMEDIATE, Duration.ofHours(24), clockAt(booking.getEndAt().plus(Duration.ofHours(2))));
        assertThat(job.execute().block()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        var later = new CompleteEndedBookingsUseCase(bookings, Fakes.IMMEDIATE, Duration.ofHours(24), clockAt(booking.getEndAt().plus(Duration.ofHours(24))));
        assertThat(later.execute().block()).isEqualTo(1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(later.execute().block()).isZero();
    }

    @Test
    void heldAndNoShowBookingsAreNeverCompleted() {
        var unpaid = held(OTHER, 9, 10, "b");
        var missed = confirmed(CUSTOMER, 17, 19, "a");
        listAt(missed.getEndAt()).markNoShow(OWNER, missed.getId()).block();
        var job = new CompleteEndedBookingsUseCase(bookings, Fakes.IMMEDIATE, Duration.ofHours(24), clockAt(missed.getEndAt().plus(Duration.ofDays(3))));

        assertThat(job.execute().block()).isZero();
        assertThat(unpaid.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(missed.getStatus()).isEqualTo(BookingStatus.NO_SHOW);
    }

    /** The owner view's shape, checked by reflection so a customer identifier cannot slip into it unnoticed. */
    private static final class BookingResultFields {
        static List<String> names() {
            return java.util.Arrays.stream(com.dsports.ground.application.result.BookingResult.class.getRecordComponents()).map(c -> c.getName()).toList();
        }
    }

    private static void assertCode(Callable<?> action, GroundErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
