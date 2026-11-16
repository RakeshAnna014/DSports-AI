package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BookingPaymentPort;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.CancellationPolicy;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

/**
 * Cancels a booking, in one transaction under its row lock (the same lock that orders paying and expiring it).
 * <ul>
 *   <li><b>A held, unpaid booking</b> is cancelled and its unpaid payment attempts are voided; if a capture is in flight this fails with
 *       {@code PAYMENT_IN_PROGRESS} and nothing changes.</li>
 *   <li><b>A paid booking cancelled by its customer</b> gets a refund request for the share the cancellation policy allows (100% / 50% / 0%).
 *       Nothing is paid out until an administrator approves it.</li>
 *   <li><b>A paid booking cancelled by the ground owner</b> is always refunded in full, automatically, by the same path as a capture that
 *       could not be honoured: the refund is recorded here and paid out afterwards by the outbox.</li>
 * </ul>
 * A cancellation is only possible before the booking starts, and the customer is always sent a cancellation notification.
 */
public class CancelBookingUseCase {
    private static final Logger log = LoggerFactory.getLogger(CancelBookingUseCase.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    public enum Refund { NONE, REQUESTED, STARTED }

    public record Command(UUID callerId, UUID bookingId, CancelledBy by) {
    }

    /** {@code refund}: NONE (nothing to refund), REQUESTED (awaiting admin approval) or STARTED (automatic, being paid out). */
    public record Result(BookingResult booking, int refundPercent, BigDecimal refundAmount, Refund refund) {
    }

    private final BookingRepository bookings;
    private final VenueRepository venues;
    private final BookingPresenter presenter;
    private final BookingPaymentPort payments;
    private final CancellationPolicy policy;
    private final OutboxWriter outbox;
    private final TransactionRunner transactions;
    private final Clock clock;

    public CancelBookingUseCase(BookingRepository bookings, VenueRepository venues, BookingPresenter presenter, BookingPaymentPort payments,
                                CancellationPolicy policy, OutboxWriter outbox, TransactionRunner transactions, Clock clock) {
        this.bookings = bookings;
        this.venues = venues;
        this.presenter = presenter;
        this.payments = payments;
        this.policy = policy;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Mono<Result> execute(Command command) {
        return transactions.inTransaction(() -> bookings.findByIdForUpdate(command.bookingId())
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + command.bookingId())))
            .flatMap(booking -> authorise(booking, command).flatMap(venue -> cancel(booking, command))));
    }

    private Mono<Venue> authorise(Booking booking, Command command) {
        if (command.by() == CancelledBy.CUSTOMER) {
            if (!booking.getUserId().equals(command.callerId())) {
                return Mono.error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_OWNED_BY_USER, "This booking does not belong to you"));
            }
            return venues.findById(booking.getVenueId())
                .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.VENUE_NOT_FOUND, "Venue not found: " + booking.getVenueId())));
        }
        return venues.findById(booking.getVenueId())
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.VENUE_NOT_FOUND, "Venue not found: " + booking.getVenueId())))
            .flatMap(venue -> venue.getOwnerId().equals(command.callerId())
                ? Mono.just(venue)
                : Mono.<Venue>error(new GroundDomainException(GroundErrorCode.VENUE_NOT_OWNED_BY_USER, "This booking is not at one of your venues")));
    }

    private Mono<Result> cancel(Booking booking, Command command) {
        var now = clock.instant();
        boolean paid = booking.getStatus() == BookingStatus.CONFIRMED;
        booking.cancel(command.by(), now);
        int percent = paid ? policy.refundPercent(booking.getStartAt(), now, command.by()) : 0;
        var amount = booking.getPrice().multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        var refund = !paid || percent == 0 ? Refund.NONE : (command.by() == CancelledBy.OWNER ? Refund.STARTED : Refund.REQUESTED);
        var reason = "Booking " + booking.getBookingNumber() + " cancelled by the " + command.by().name().toLowerCase(Locale.ROOT);

        Mono<Void> money;
        if (!paid) {
            money = payments.voidOpenPayments(booking.getId());
        } else if (refund == Refund.STARTED) {
            money = payments.refundCapturedPayment(booking.getPaymentId(), reason);
        } else if (refund == Refund.REQUESTED) {
            money = payments.requestRefund(booking.getPaymentId(), booking.getUserId(), amount, reason, "booking-cancel:" + booking.getId());
        } else {
            money = Mono.empty();
        }
        return money
            .then(Mono.defer(() -> bookings.save(booking)))
            .then(Mono.defer(() -> presenter.context(booking)))
            .flatMap(context -> outbox.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_BOOKING_CANCELLED, booking.getId(), message(booking, context, refund, percent, amount)))
                .doOnSuccess(v -> log.info("Booking {} cancelled by {} (refund {} {}% {})", booking.getBookingNumber(), command.by(), refund, percent, amount))
                .thenReturn(new Result(BookingResult.from(booking, context.names()), percent, amount, refund)));
    }

    private static java.util.Map<String, String> message(Booking booking, BookingPresenter.Context context, Refund refund, int percent, BigDecimal amount) {
        var zone = context.zone();
        var note = switch (refund) {
            case STARTED -> "A full refund of " + booking.getCurrency() + " " + amount.toPlainString() + " has been started automatically.";
            case REQUESTED -> "A " + percent + "% refund of " + booking.getCurrency() + " " + amount.toPlainString() + " has been requested and is awaiting approval.";
            case NONE -> booking.getStatus() == BookingStatus.CANCELLED && booking.getPaymentId() != null ? "No refund applies under the cancellation policy." : "";
        };
        var payload = new HashMap<String, String>();
        payload.put("userId", booking.getUserId().toString());
        payload.put("bookingNumber", booking.getBookingNumber());
        payload.put("groundName", context.label());
        payload.put("when", WHEN.format(booking.getStartAt().atZone(zone)) + "-" + DateTimeFormatter.ofPattern("HH:mm").format(booking.getEndAt().atZone(zone)));
        payload.put("note", note);
        return payload;
    }
}
