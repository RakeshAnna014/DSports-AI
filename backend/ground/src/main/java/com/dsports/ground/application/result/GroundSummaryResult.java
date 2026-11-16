package com.dsports.ground.application.result;

import java.math.BigDecimal;

/** A search hit: the ground plus, when the search named a date, how many of its slots are free then and the cheapest one. */
public record GroundSummaryResult(GroundResult ground, Integer availableSlots, BigDecimal lowestAvailablePrice) {
}
