package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.shared.application.TransactionRunner;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reading and marking bookings: a customer's own bookings, a venue owner's bookings at one of their own venues, and the owner marking a
 * confirmed booking as a no-show once it has ended. The owner view shows the booking, not the customer: no customer identity is exposed.
 */
public class ListBookingsUseCase {

    private static final Duration DEFAULT_LOOKAHEAD = Duration.ofDays(30);

    private final BookingRepository bookings;
    private final VenueRepository venues;
    private final BookingPresenter presenter;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ListBookingsUseCase(BookingRepository bookings, VenueRepository venues, BookingPresenter presenter, TransactionRunner transactions, Clock clock) {
        this.bookings = bookings;
        this.venues = venues;
        this.presenter = presenter;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The customer's bookings, newest first, each with its venue, court and sport. */
    public Mono<List<BookingResult>> mine(UUID userId) {
        return bookings.findByUser(userId).collectList().flatMap(presenter::presentAll);
    }

    /** Bookings at an owner's venue (any court, or just {@code courtId}) on one local day, or (without a date) from now for 30 days. */
    public Mono<List<BookingResult>> atVenue(UUID ownerId, UUID venueId, UUID courtId, LocalDate date) {
        return VenueAccess.accessible(venues, Caller.owner(ownerId), venueId).flatMap(venue -> {
            Instant from;
            Instant to;
            if (date == null) {
                from = clock.instant();
                to = from.plus(DEFAULT_LOOKAHEAD);
            } else {
                from = date.atStartOfDay(venue.getTimeZone()).toInstant();
                to = date.plusDays(1).atStartOfDay(venue.getTimeZone()).toInstant();
            }
            return bookings.findByVenue(venueId, from, to)
                .filter(b -> courtId == null || b.getCourtId().equals(courtId))
                .collectList().flatMap(presenter::presentAll);
        });
    }

    /** The owner records that a confirmed booking's customer never came. Only after the booking has ended. */
    public Mono<BookingResult> markNoShow(UUID ownerId, UUID bookingId) {
        return transactions.inTransaction(() -> bookings.findByIdForUpdate(bookingId)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + bookingId)))
            .flatMap(booking -> VenueAccess.accessible(venues, Caller.owner(ownerId), booking.getVenueId()).flatMap(venue -> {
                booking.markNoShow(clock.instant());
                return bookings.save(booking).flatMap(presenter::present);
            })));
    }
}
