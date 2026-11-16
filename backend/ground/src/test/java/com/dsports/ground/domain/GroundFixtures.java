package com.dsports.ground.domain;

import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.PitchType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Shared builders. Wednesday 2026-11-18 is a weekday and Saturday 2026-11-21 a weekend day. */
public final class GroundFixtures {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 18);
    public static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);
    public static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    public static final Instant NOW = Instant.parse("2026-11-16T04:30:00Z");

    private GroundFixtures() {
    }

    /** Open 06:00-22:00 IST, 60 minute slots, base 1000, weekend 1500, peak 2000 between 18:00 and 21:00. */
    public static Ground.Spec spec() {
        return new Ground.Spec("Green Park Turf", "12 Park Road", "Bengaluru", new BigDecimal("12.971599"), new BigDecimal("77.594566"),
            PitchType.TURF, List.of("Floodlights", "Parking"), List.of("https://example.com/p1.jpg"),
            LocalTime.of(6, 0), LocalTime.of(22, 0), IST, 60,
            new BigDecimal("1000.00"), new BigDecimal("1500.00"), new BigDecimal("2000.00"), LocalTime.of(18, 0), LocalTime.of(21, 0));
    }

    public static Ground ground() {
        return Ground.create(OWNER, spec(), NOW);
    }

    public static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(IST).toInstant();
    }
}
