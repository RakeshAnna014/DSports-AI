package com.dsports.ground.application.result;

import com.dsports.ground.application.port.SportCatalogPort.SportRef;
import com.dsports.ground.domain.model.Court;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A court with the sports it offers (names resolved from the catalogue). {@code version} is for owners: send it back with an edit. */
public record CourtResult(UUID id, UUID venueId, String name, String surface, List<SportRef> sports, int slotMinutes, BigDecimal basePrice,
                          BigDecimal weekendPrice, BigDecimal peakPrice, LocalTime peakFrom, LocalTime peakTo, boolean active, int version) {

    public static CourtResult from(Court c, Map<UUID, SportRef> sportsById) {
        var s = c.getSpec();
        var sports = s.sportIds().stream().map(sportsById::get).filter(java.util.Objects::nonNull).toList();
        return new CourtResult(c.getId(), c.getVenueId(), s.name(), s.surface(), sports, s.slotMinutes(), s.basePrice(), s.weekendPrice(), s.peakPrice(),
            s.peakFrom(), s.peakTo(), c.isActive(), c.getVersion());
    }
}
