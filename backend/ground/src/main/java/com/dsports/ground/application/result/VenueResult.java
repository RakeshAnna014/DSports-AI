package com.dsports.ground.application.result;

import com.dsports.ground.domain.model.Venue;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** A venue as customers see it. The owner's id is deliberately not part of it. */
public record VenueResult(UUID id, String name, String address, String city, BigDecimal latitude, BigDecimal longitude, List<String> facilities,
                          List<String> photoUrls, LocalTime opensAt, LocalTime closesAt, String timeZone, String currency, boolean active) {

    public static VenueResult from(Venue v) {
        var s = v.getSpec();
        return new VenueResult(v.getId(), s.name(), s.address(), s.city(), s.latitude(), s.longitude(), s.facilities(), s.photoUrls(), s.opensAt(),
            s.closesAt(), s.timeZone().getId(), v.getCurrency(), v.isActive());
    }
}
