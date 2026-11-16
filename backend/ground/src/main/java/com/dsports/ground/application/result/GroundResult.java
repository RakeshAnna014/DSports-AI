package com.dsports.ground.application.result;

import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.PitchType;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** A ground as customers see it. The owner's id is deliberately not part of it. */
public record GroundResult(UUID id, String name, String address, String city, BigDecimal latitude, BigDecimal longitude, PitchType pitchType,
                           List<String> facilities, List<String> photoUrls, LocalTime opensAt, LocalTime closesAt, String timeZone,
                           int slotMinutes, BigDecimal basePrice, BigDecimal weekendPrice, BigDecimal peakPrice, LocalTime peakFrom,
                           LocalTime peakTo, String currency, boolean active) {

    public static GroundResult from(Ground g) {
        var s = g.getSpec();
        return new GroundResult(g.getId(), s.name(), s.address(), s.city(), s.latitude(), s.longitude(), s.pitchType(), s.facilities(),
            s.photoUrls(), s.opensAt(), s.closesAt(), s.timeZone().getId(), s.slotMinutes(), s.basePrice(), s.weekendPrice(), s.peakPrice(),
            s.peakFrom(), s.peakTo(), g.getCurrency(), g.isActive());
    }
}
