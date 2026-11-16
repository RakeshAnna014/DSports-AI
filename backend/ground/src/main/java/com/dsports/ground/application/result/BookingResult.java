package com.dsports.ground.application.result;

import com.dsports.ground.domain.model.Booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A booking as its customer sees it. {@code heldUntil} is the payment deadline while the status is HELD. */
public record BookingResult(UUID id, String bookingNumber, UUID groundId, String groundName, String groundCity, Instant startAt, Instant endAt,
                            BigDecimal price, String currency, String status, Instant heldUntil, UUID paymentId, Instant cancelledAt,
                            String cancelledBy, Instant createdAt) {

    public static BookingResult from(Booking b, String groundName, String groundCity) {
        return new BookingResult(b.getId(), b.getBookingNumber(), b.getGroundId(), groundName, groundCity, b.getStartAt(), b.getEndAt(), b.getPrice(),
            b.getCurrency(), b.getStatus().name(), b.getHeldUntil(), b.getPaymentId(), b.getCancelledAt(),
            b.getCancelledBy() == null ? null : b.getCancelledBy().name(), b.getCreatedAt());
    }
}
