package com.dsports.ground.application.result;

import com.dsports.ground.domain.model.Booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A booking as its customer sees it. {@code heldUntil} is the payment deadline while the status is HELD. Names are null when the record is gone. */
public record BookingResult(UUID id, String bookingNumber, UUID venueId, String venueName, String venueCity, UUID courtId, String courtName,
                            UUID sportId, String sportName, Instant startAt, Instant endAt, BigDecimal price, String currency, String status,
                            Instant heldUntil, UUID paymentId, Instant cancelledAt, String cancelledBy, Instant createdAt) {

    /** The names a booking shows; any of them may be null if the venue, court or sport no longer resolves. */
    public record Names(String venueName, String venueCity, String courtName, String sportName) {
        public static final Names NONE = new Names(null, null, null, null);
    }

    public static BookingResult from(Booking b, Names n) {
        return new BookingResult(b.getId(), b.getBookingNumber(), b.getVenueId(), n.venueName(), n.venueCity(), b.getCourtId(), n.courtName(),
            b.getSportId(), n.sportName(), b.getStartAt(), b.getEndAt(), b.getPrice(), b.getCurrency(), b.getStatus().name(), b.getHeldUntil(),
            b.getPaymentId(), b.getCancelledAt(), b.getCancelledBy() == null ? null : b.getCancelledBy().name(), b.getCreatedAt());
    }
}
