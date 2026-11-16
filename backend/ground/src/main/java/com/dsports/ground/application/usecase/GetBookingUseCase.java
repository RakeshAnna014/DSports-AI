package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** One booking, for the customer who made it. */
public class GetBookingUseCase {

    private final BookingRepository bookings;
    private final GroundRepository grounds;

    public GetBookingUseCase(BookingRepository bookings, GroundRepository grounds) {
        this.bookings = bookings;
        this.grounds = grounds;
    }

    public Mono<BookingResult> execute(UUID userId, UUID bookingId) {
        return bookings.findById(bookingId)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_FOUND, "Booking not found: " + bookingId)))
            .flatMap(booking -> {
                if (!booking.getUserId().equals(userId)) {
                    return Mono.<BookingResult>error(new GroundDomainException(GroundErrorCode.BOOKING_NOT_OWNED_BY_USER, "This booking does not belong to you"));
                }
                return grounds.findById(booking.getGroundId())
                    .map(g -> BookingResult.from(booking, g.getName(), g.getCity()))
                    .defaultIfEmpty(BookingResult.from(booking, null, null));
            });
    }
}
