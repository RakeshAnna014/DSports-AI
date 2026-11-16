package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Ground;
import com.dsports.shared.application.TransactionRunner;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reading and marking bookings: a customer's own bookings, a ground owner's bookings at one of their own grounds, and the owner marking a
 * confirmed booking as a no-show once it has ended. The owner view shows the booking, not the customer: no customer identity is exposed.
 */
public class ListBookingsUseCase {

    private static final Duration DEFAULT_LOOKAHEAD = Duration.ofDays(30);

    private final BookingRepository bookings;
    private final GroundRepository grounds;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ListBookingsUseCase(BookingRepository bookings, GroundRepository grounds, TransactionRunner transactions, Clock clock) {
        this.bookings = bookings;
        this.grounds = grounds;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The customer's bookings, newest first, each with its ground's name and city. */
    public Mono<List<BookingResult>> mine(UUID userId) {
        return bookings.findByUser(userId).collectList().flatMap(list -> {
            var ids = list.stream().map(Booking::getGroundId).collect(Collectors.toSet());
            return grounds.findAllById(ids).collectMap(Ground::getId, Function.identity())
                .map(byId -> list.stream().map(b -> present(b, byId.get(b.getGroundId()))).toList());
        });
    }

    /** Bookings at an owner's ground on one local day, or (without a date) from now for 30 days. */
    public Mono<List<BookingResult>> atGround(UUID ownerId, UUID groundId, LocalDate date) {
        return OwnerGrounds.owned(grounds, ownerId, groundId).flatMap(ground -> {
            Instant from;
            Instant to;
            if (date == null) {
                from = clock.instant();
                to = from.plus(DEFAULT_LOOKAHEAD);
            } else {
                from = date.atStartOfDay(ground.getTimeZone()).toInstant();
                to = date.plusDays(1).atStartOfDay(ground.getTimeZone()).toInstant();
            }
            return bookings.findByGround(groundId, from, to).map(b -> present(b, ground)).collectList();
        });
    }

    /** The owner records that a confirmed booking's customer never came. Only after the booking has ended. */
    public Mono<BookingResult> markNoShow(UUID ownerId, UUID bookingId) {
        return transactions.inTransaction(() -> bookings.findByIdForUpdate(bookingId)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + bookingId)))
            .flatMap(booking -> OwnerGrounds.owned(grounds, ownerId, booking.getGroundId()).flatMap(ground -> {
                booking.markNoShow(clock.instant());
                return bookings.save(booking).map(saved -> present(saved, ground));
            })));
    }

    private static BookingResult present(Booking booking, Ground ground) {
        return BookingResult.from(booking, ground == null ? null : ground.getName(), ground == null ? null : ground.getCity());
    }
}
