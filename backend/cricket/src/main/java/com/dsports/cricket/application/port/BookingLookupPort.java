package com.dsports.cricket.application.port;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** A venue booking, seen from the cricket module: enough to check it may host a match and to copy the venue. Implemented in bootstrap over the ground module's data. */
public interface BookingLookupPort {

    record BookingInfo(UUID id, UUID userId, String status, String sportSlug, String venueName, String city, Instant startAt) {
    }

    Mono<BookingInfo> find(UUID bookingId);
}
