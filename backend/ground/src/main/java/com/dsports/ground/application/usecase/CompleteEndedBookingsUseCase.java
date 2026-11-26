package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.shared.application.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Marks confirmed bookings COMPLETED once they ended and a grace period passed. The grace period (default 24 hours) is the owner's window to record
 * a no-show first: a COMPLETED booking can no longer become NO_SHOW. Each booking is handled in its own transaction under its row lock.
 */
public class CompleteEndedBookingsUseCase {
    private static final Logger log = LoggerFactory.getLogger(CompleteEndedBookingsUseCase.class);
    private static final int BATCH = 100;

    private final BookingRepository bookings;
    private final TransactionRunner transactions;
    private final Duration grace;
    private final Clock clock;

    public CompleteEndedBookingsUseCase(BookingRepository bookings, TransactionRunner transactions, Duration grace, Clock clock) {
        this.bookings = bookings;
        this.transactions = transactions;
        this.grace = grace;
        this.clock = clock;
    }

    /** @return the number of bookings completed in this run */
    public Mono<Long> execute() {
        return bookings.findEndedConfirmedIds(clock.instant().minus(grace), BATCH)
            .concatMap(id -> complete(id).onErrorResume(e -> {
                log.warn("Could not complete booking {}: {}", id, e.getMessage());
                return Mono.just(0L);
            }))
            .reduce(0L, Long::sum);
    }

    private Mono<Long> complete(UUID bookingId) {
        return transactions.inTransaction(() -> bookings.findByIdForUpdate(bookingId)
            .filter(booking -> booking.getStatus() == BookingStatus.CONFIRMED && !clock.instant().isBefore(booking.getEndAt().plus(grace)))
            .flatMap(booking -> {
                booking.complete(clock.instant());
                return bookings.save(booking).thenReturn(1L);
            })
            .defaultIfEmpty(0L));
    }
}
