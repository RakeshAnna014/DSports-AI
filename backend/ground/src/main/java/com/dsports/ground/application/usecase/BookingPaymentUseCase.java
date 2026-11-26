package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BookingPaymentPort;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The booking side of paying. The payment module asks two things of it, both under the booking's row lock:
 * <ul>
 *   <li>{@link #payable}: is this booking the caller's, and what does it cost? The amount is the booking's own price snapshot: the payment
 *       is always charged that amount, whatever the request said. A booking whose hold has lapsed is reported as {@code HOLD_EXPIRED}, not payable.</li>
 *   <li>{@link #confirmPaid}: the payment was captured. Re-checks that the booking is still HELD and confirms it. If it no longer is (it was
 *       expired or cancelled while the money moved) the payment is refunded in full in the same transaction and the caller is told it failed.</li>
 * </ul>
 * Together with the expiry job taking the same lock and skipping bookings whose payment is being captured, a payment can never be silently
 * taken for a slot that has been given away.
 */
public class BookingPaymentUseCase {
    private static final Logger log = LoggerFactory.getLogger(BookingPaymentUseCase.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);

    /** {@code status} uses the payment module's vocabulary: PENDING_PAYMENT means "can be paid now". */
    public record PayableBooking(UUID id, UUID userId, String bookingNumber, BigDecimal price, String currency, String status) {
    }

    private final BookingRepository bookings;
    private final BookingPresenter presenter;
    private final BookingPaymentPort payments;
    private final OutboxWriter outbox;
    private final Clock clock;

    public BookingPaymentUseCase(BookingRepository bookings, BookingPresenter presenter, BookingPaymentPort payments, OutboxWriter outbox, Clock clock) {
        this.bookings = bookings;
        this.presenter = presenter;
        this.payments = payments;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Empty when there is no such booking. A booking of someone else is an error, never a quiet empty. */
    public Mono<PayableBooking> payable(UUID bookingId, UUID userId) {
        return bookings.findByIdForUpdate(bookingId).flatMap(booking -> {
            if (!booking.getUserId().equals(userId)) {
                return Mono.<PayableBooking>error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_OWNED_BY_USER, "This booking does not belong to you"));
            }
            var now = clock.instant();
            var status = booking.getStatus() == BookingStatus.HELD ? (booking.isPayable(now) ? "PENDING_PAYMENT" : "HOLD_EXPIRED") : booking.getStatus().name();
            return Mono.just(new PayableBooking(booking.getId(), booking.getUserId(), booking.getBookingNumber(), booking.getPrice(), booking.getCurrency(), status));
        });
    }

    /** @return true when the booking was confirmed; false when it no longer could be and the payment is being refunded. Runs inside the capture transaction. */
    public Mono<Boolean> confirmPaid(UUID bookingId, UUID paymentId) {
        return bookings.findByIdForUpdate(bookingId)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + bookingId)))
            .flatMap(booking -> presenter.context(booking)
                .flatMap(context -> booking.getStatus() == BookingStatus.HELD
                    ? confirm(booking, paymentId, context)
                    : abandon(booking, paymentId, context)));
    }

    private Mono<Boolean> confirm(Booking booking, UUID paymentId, BookingPresenter.Context context) {
        booking.confirm(paymentId, clock.instant());
        return bookings.save(booking)
            .then(Mono.defer(() -> outbox.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_BOOKING_CONFIRMED, booking.getId(), message(booking, context, null)))))
            .doOnSuccess(v -> log.info("Booking {} confirmed by payment {}", booking.getBookingNumber(), paymentId))
            .thenReturn(true);
    }

    private Mono<Boolean> abandon(Booking booking, UUID paymentId, BookingPresenter.Context context) {
        log.warn("Payment {} was captured for booking {} which is {}: refunding in full", paymentId, booking.getBookingNumber(), booking.getStatus());
        var reason = "Booking " + booking.getBookingNumber() + " was " + booking.getStatus() + " when the payment was captured";
        return payments.refundCapturedPayment(paymentId, reason)
            .then(Mono.defer(() -> outbox.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_BOOKING_CANCELLED, booking.getId(),
                message(booking, context, "We could not confirm this slot because it was no longer held when your payment arrived. A full refund has been started.")))))
            .thenReturn(false);
    }

    private static Map<String, String> message(Booking booking, BookingPresenter.Context context, String note) {
        var zone = context.zone();
        var payload = new java.util.HashMap<String, String>();
        payload.put("userId", booking.getUserId().toString());
        payload.put("bookingNumber", booking.getBookingNumber());
        payload.put("groundName", context.label());
        payload.put("when", WHEN.format(booking.getStartAt().atZone(zone)) + "-" + DateTimeFormatter.ofPattern("HH:mm").format(booking.getEndAt().atZone(zone)));
        payload.put("note", note == null ? "" : note);
        return payload;
    }
}
