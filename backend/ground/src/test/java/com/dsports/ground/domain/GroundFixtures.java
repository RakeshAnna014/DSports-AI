package com.dsports.ground.domain;

import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;

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
    public static final UUID CRICKET = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    public static final UUID FOOTBALL = UUID.fromString("00000000-0000-0000-0000-00000000f001");
    public static final UUID BADMINTON = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    public static final Instant NOW = Instant.parse("2026-11-16T04:30:00Z");

    private GroundFixtures() {
    }

    /** Open 06:00-22:00 IST. */
    public static Venue.Spec venueSpec() {
        return new Venue.Spec("Green Park Sports Hub", "12 Park Road", "Bengaluru", new BigDecimal("12.971599"), new BigDecimal("77.594566"),
            List.of("Floodlights", "Parking"), List.of("https://example.com/p1.jpg"), LocalTime.of(6, 0), LocalTime.of(22, 0), IST);
    }

    /** 60 minute slots, base 1000, weekend 1500, peak 2000 between 18:00 and 21:00; offers cricket and football. */
    public static Court.Spec courtSpec() {
        return new Court.Spec("Court 1", "turf", List.of(CRICKET, FOOTBALL), 60, new BigDecimal("1000.00"), new BigDecimal("1500.00"),
            new BigDecimal("2000.00"), LocalTime.of(18, 0), LocalTime.of(21, 0));
    }

    public static Venue venue() {
        return Venue.create(OWNER, venueSpec(), NOW);
    }

    public static Court court(Venue venue) {
        return Court.create(venue, courtSpec(), NOW);
    }

    /** A court of a fresh venue (use when the venue itself does not matter). */
    public static Court court() {
        return court(venue());
    }

    public static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(IST).toInstant();
    }
}
