package com.dsports.ground.application.result;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A search hit: the venue plus its matching courts. When the search named a date, each court reports how many of its slots are free then and
 * the cheapest one, and venues (and courts) with none are left out; without a date those figures are null.
 */
public record VenueSummaryResult(VenueResult venue, List<CourtAvailability> courts, Integer availableSlots, BigDecimal lowestAvailablePrice) {

    public record CourtAvailability(UUID courtId, String name, Integer availableSlots, BigDecimal lowestAvailablePrice) {
    }
}
