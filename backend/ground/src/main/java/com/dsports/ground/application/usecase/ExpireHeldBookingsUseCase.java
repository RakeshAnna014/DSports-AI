package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BookingPaymentPort;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.shared.application.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Frees held slots whose payment window ended, the same pattern as the unpaid-order expiry. Each booking is handled in its own transaction:
 * its row is locked, re-read, and only expired if it is still HELD and still lapsed; its unpaid payment attempts are voided first. A booking whose
 * payment is being captured is skipped (the void refuses while a capture is in flight) and looked at again on the next run, so an expiry can
 * never overtake a payment. One booking failing never stops the others.
 */
public class ExpireHeldBookingsUseCase {
    private static final Logger log = LoggerFactory.getLogger(ExpireHeldBookingsUseCase.class);
    private static final int BATCH = 100;

    private final BookingRepository bookings;
    private final BookingPaymentPort payments;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ExpireHeldBookingsUseCase(BookingRepository bookings, BookingPaymentPort payments, TransactionRunner transactions, Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** @return the number of bookings expired in this run */
    public Mono<Long> execute() {
        return bookings.findLapsedHoldIds(clock.instant(), BATCH)
            .concatMap(id -> expire(id)
                .onErrorResume(e -> {
                    log.warn("Could not expire held booking {}: {}", id, e.getMessage());
                    return Mono.just(0L);
                }))
            .reduce(0L, Long::sum);
    }

    private Mono<Long> expire(UUID bookingId) {
        return transactions.inTransaction(() -> bookings.findByIdForUpdate(bookingId)
            .filter(booking -> booking.getStatus() == BookingStatus.HELD && booking.isHoldLapsed(clock.instant()))
            .flatMap(booking -> payments.voidOpenPayments(booking.getId())
                .then(Mono.defer(() -> {
                    booking.expire(clock.instant());
                    return bookings.save(booking);
                }))
                .doOnSuccess(saved -> log.info("Held booking {} expired; slot released", booking.getBookingNumber()))
                .thenReturn(1L))
            .defaultIfEmpty(0L));
    }
}
