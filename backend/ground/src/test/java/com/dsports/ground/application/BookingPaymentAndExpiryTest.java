package com.dsports.ground.application;

import com.dsports.ground.application.port.BookingPaymentPort;
import com.dsports.ground.application.usecase.BookingPaymentUseCase;
import com.dsports.ground.application.usecase.ExpireHeldBookingsUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

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
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.ground;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingPaymentAndExpiryTest {

    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final Duration HOLD = Duration.ofMinutes(10);

    private final Fakes.Grounds grounds = new Fakes.Grounds();
    private final Fakes.Bookings bookings = new Fakes.Bookings();
    private final Fakes.Blackouts blackouts = new Fakes.Blackouts();
    private final RecordingPayments payments = new RecordingPayments();
    private final List<OutboxMessage> outbox = new ArrayList<>();
    private final OutboxWriter outboxWriter = message -> Mono.fromRunnable(() -> outbox.add(message));
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private Ground ground;

    static final class RecordingPayments implements BookingPaymentPort {
        final List<UUID> voided = new ArrayList<>();
        final List<UUID> refunded = new ArrayList<>();
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
            return Mono.just(false);
        }

        @Override
        public Mono<Void> refundCapturedPayment(UUID paymentId, String reason) {
            return Mono.fromRunnable(() -> refunded.add(paymentId));
        }

        @Override
        public Mono<Void> requestRefund(UUID paymentId, UUID requesterId, java.math.BigDecimal amount, String reason, String idempotencyKey) {
            return Mono.empty();
        }
    }

    @BeforeEach
    void setUp() {
        ground = ground();
        grounds.save(ground).block();
    }

    private BookingPaymentUseCase payments() {
        return new BookingPaymentUseCase(bookings, grounds, payments, outboxWriter, clock);
    }

    private ExpireHeldBookingsUseCase expiry() {
        return new ExpireHeldBookingsUseCase(bookings, payments, Fakes.IMMEDIATE, clock);
    }

    private Booking held(UUID user, int fromHour, int toHour, String key) {
        var hold = new HoldBookingUseCase(grounds, bookings, blackouts, BookingPolicy.defaults(), HOLD, 10, Fakes.IMMEDIATE, Clock.fixed(NOW, ZoneOffset.UTC));
        var id = hold.execute(new HoldBookingUseCase.Command(user, ground.getId(), at(WEDNESDAY, fromHour, 0), at(WEDNESDAY, toHour, 0), key)).block().booking().id();
        return bookings.store.get(id);
    }

    private void passTime(Duration d) {
        clock = Clock.fixed(NOW.plus(d), ZoneOffset.UTC);
    }

    // ---- payable -----------------------------------------------------------------------------------------------

    @Test
    void aHeldBookingIsPayableAtItsOwnPriceSnapshotUnderItsRowLock() {
        var booking = held(CUSTOMER, 17, 19, "a");
        bookings.calls.clear();

        var payable = payments().payable(booking.getId(), CUSTOMER).block();

        assertThat(payable.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(payable.price()).isEqualByComparingTo("3000");
        assertThat(payable.currency()).isEqualTo("INR");
        assertThat(payable.bookingNumber()).isEqualTo(booking.getBookingNumber());
        assertThat(bookings.calls).containsExactly("row-lock");
    }

    @Test
    void aHeldBookingPastItsDeadlineIsNotPayable() {
        var booking = held(CUSTOMER, 17, 19, "a");
        passTime(HOLD.plusSeconds(1));

        assertThat(payments().payable(booking.getId(), CUSTOMER).block().status()).isEqualTo("HOLD_EXPIRED");
    }

    @Test
    void aBookingInAnyOtherStatusReportsThatStatus() {
        var booking = held(CUSTOMER, 17, 19, "a");
        booking.confirm(UUID.randomUUID(), NOW);
        assertThat(payments().payable(booking.getId(), CUSTOMER).block().status()).isEqualTo("CONFIRMED");

        booking.cancel(CancelledBy.OWNER, NOW);
        assertThat(payments().payable(booking.getId(), CUSTOMER).block().status()).isEqualTo("CANCELLED");
    }

    @Test
    void anotherCustomersBookingIsRefusedAndAnUnknownOneIsEmpty() {
        var booking = held(CUSTOMER, 17, 19, "a");

        assertCode(() -> payments().payable(booking.getId(), OTHER).block(), GroundErrorCode.BOOKING_NOT_OWNED_BY_USER);
        assertThat(payments().payable(UUID.randomUUID(), CUSTOMER).blockOptional()).isEmpty();
    }

    // ---- confirmPaid -------------------------------------------------------------------------------------------

    @Test
    void aCapturedPaymentConfirmsAHeldBookingAndQueuesTheNotification() {
        var booking = held(CUSTOMER, 17, 19, "a");
        var paymentId = UUID.randomUUID();

        var confirmed = payments().confirmPaid(booking.getId(), paymentId).block();

        assertThat(confirmed).isTrue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getPaymentId()).isEqualTo(paymentId);
        assertThat(outbox).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo(OutboxMessageTypes.NOTIFY_BOOKING_CONFIRMED);
            assertThat(m.aggregateId()).isEqualTo(booking.getId().toString());
            assertThat(m.payload()).containsEntry("userId", CUSTOMER.toString()).containsEntry("groundName", "Green Park Turf").containsEntry("when", "Wed 18 Nov, 17:00-19:00");
        });
        assertThat(payments.refunded).isEmpty();
    }

    @Test
    void aPaymentCapturedJustAfterTheDeadlineButBeforeExpiryStillConfirms() {
        var booking = held(CUSTOMER, 17, 19, "a");
        passTime(HOLD.plusSeconds(20));

        assertThat(payments().confirmPaid(booking.getId(), UUID.randomUUID()).block()).isTrue();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void ifTheBookingWasExpiredWhileTheMoneyMovedThePaymentIsRefundedInFullAndTheCallerIsToldItFailed() {
        var booking = held(CUSTOMER, 17, 19, "a");
        booking.expire(NOW);
        var paymentId = UUID.randomUUID();

        var confirmed = payments().confirmPaid(booking.getId(), paymentId).block();

        assertThat(confirmed).isFalse();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(booking.getPaymentId()).isNull();
        assertThat(payments.refunded).containsExactly(paymentId);
        assertThat(outbox).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo(OutboxMessageTypes.NOTIFY_BOOKING_CANCELLED);
            assertThat(m.payload().get("note")).contains("full refund");
        });
    }

    @Test
    void aCancelledBookingIsAlsoRefundedNotConfirmed() {
        var booking = held(CUSTOMER, 17, 19, "a");
        booking.cancel(CancelledBy.CUSTOMER, NOW);
        var paymentId = UUID.randomUUID();

        assertThat(payments().confirmPaid(booking.getId(), paymentId).block()).isFalse();
        assertThat(payments.refunded).containsExactly(paymentId);
    }

    // ---- expiry ------------------------------------------------------------------------------------------------

    @Test
    void aLapsedHoldIsExpiredItsPaymentsVoidedAndItsSlotFreed() {
        var booking = held(CUSTOMER, 17, 19, "a");
        passTime(HOLD.plusSeconds(1));

        assertThat(expiry().execute().block()).isEqualTo(1);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(payments.voided).containsExactly(booking.getId());
        assertThat(bookings.findOccupiedPeriods(ground.getId(), at(WEDNESDAY, 6, 0), at(WEDNESDAY, 22, 0)).collectList().block()).isEmpty();
    }

    @Test
    void aHoldThatHasNotLapsedYetIsLeftAlone() {
        var booking = held(CUSTOMER, 17, 19, "a");
        passTime(HOLD.minusSeconds(1));

        assertThat(expiry().execute().block()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(payments.voided).isEmpty();
    }

    @Test
    void aBookingWhosePaymentIsBeingCapturedIsSkippedAndTheOthersStillExpire() {
        var beingPaid = held(CUSTOMER, 9, 10, "a");
        var abandoned = held(OTHER, 11, 12, "b");
        payments.captureInProgress.add(beingPaid.getId());
        passTime(HOLD.plusSeconds(5));

        assertThat(expiry().execute().block()).isEqualTo(1);

        assertThat(beingPaid.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(abandoned.getStatus()).isEqualTo(BookingStatus.EXPIRED);

        payments.captureInProgress.clear();
        assertThat(expiry().execute().block()).isEqualTo(1);
        assertThat(beingPaid.getStatus()).isEqualTo(BookingStatus.EXPIRED);
    }

    @Test
    void aConfirmedBookingIsNeverExpired() {
        var booking = held(CUSTOMER, 17, 19, "a");
        booking.confirm(UUID.randomUUID(), NOW);
        passTime(Duration.ofHours(1));

        assertThat(expiry().execute().block()).isZero();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void everyBookingIsReCheckedUnderItsRowLockAndASecondRunDoesNothing() {
        held(CUSTOMER, 9, 10, "a");
        held(OTHER, 11, 12, "b");
        passTime(HOLD.plusSeconds(1));
        bookings.calls.clear();

        assertThat(expiry().execute().block()).isEqualTo(2);
        assertThat(bookings.calls).containsExactly("row-lock", "row-lock");
        assertThat(expiry().execute().block()).isZero();
    }

    private static void assertCode(Callable<?> action, GroundErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
