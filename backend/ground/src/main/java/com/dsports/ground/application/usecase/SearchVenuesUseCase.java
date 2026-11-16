package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.VenueResult;
import com.dsports.ground.application.result.VenueSummaryResult;
import com.dsports.ground.application.result.VenueSummaryResult.CourtAvailability;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.SlotCalculator;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Finds active venues that offer a sport, optionally in one city (case-insensitive). Venues whose owner no longer holds the ground-owner role are
 * hidden. When a date is given, each hit reports, per court that offers the sport, how many slots are free that day (inside the optional
 * {@code from}..{@code to} window, read in the venue's own zone) and the cheapest one; venues and courts with none are left out.
 */
public class SearchVenuesUseCase {

    private static final int MAX_RESULTS = 50;

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final GetCourtSlotsUseCase slots;
    private final VenueAssembler assembler;

    public SearchVenuesUseCase(VenueRepository venues, CourtRepository courts, GetCourtSlotsUseCase slots, VenueAssembler assembler) {
        this.venues = venues;
        this.courts = courts;
        this.slots = slots;
        this.assembler = assembler;
    }

    public Flux<VenueSummaryResult> execute(UUID sportId, String city, LocalDate date, LocalTime from, LocalTime to) {
        if (city != null && city.length() > 80) {
            return Flux.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "City is too long"));
        }
        if ((from != null || to != null) && date == null) {
            return Flux.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A time window needs a date"));
        }
        if (from != null && to != null && !to.isAfter(from)) {
            return Flux.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "The window must end after it starts"));
        }
        var normalisedCity = city == null || city.isBlank() ? null : city.trim();
        return venues.findActive(sportId, normalisedCity).take(MAX_RESULTS).collectList()
            .flatMap(candidates -> assembler.eligibleOwners(candidates.stream().map(Venue::getOwnerId).toList())
                .map(eligible -> candidates.stream().filter(v -> eligible.contains(v.getOwnerId())).toList()))
            .flatMapMany(Flux::fromIterable)
            .concatMap(venue -> courts.findActiveByVenue(venue.getId())
                .filter(court -> sportId == null || court.supports(sportId))
                .concatMap(court -> availabilityOf(venue, court, date, from, to))
                .collectList()
                .flatMapMany(list -> summary(venue, list, date)));
    }

    private reactor.core.publisher.Mono<CourtAvailability> availabilityOf(Venue venue, Court court, LocalDate date, LocalTime from, LocalTime to) {
        if (date == null) {
            return reactor.core.publisher.Mono.just(new CourtAvailability(court.getId(), court.getName(), null, null));
        }
        return slots.availability(court, date).map(list -> {
            var free = list.stream().filter(SlotCalculator.SlotAvailability::available).filter(a -> inWindow(a, venue.getTimeZone(), from, to)).toList();
            var lowest = free.stream().map(SlotCalculator.SlotAvailability::price).min(Comparator.naturalOrder()).orElse(null);
            return new CourtAvailability(court.getId(), court.getName(), free.size(), lowest);
        });
    }

    private Flux<VenueSummaryResult> summary(Venue venue, List<CourtAvailability> list, LocalDate date) {
        if (list.isEmpty()) {
            return Flux.empty();
        }
        if (date == null) {
            return Flux.just(new VenueSummaryResult(VenueResult.from(venue), list, null, null));
        }
        var withFree = list.stream().filter(c -> c.availableSlots() != null && c.availableSlots() > 0).toList();
        if (withFree.isEmpty()) {
            return Flux.empty();
        }
        int total = withFree.stream().mapToInt(CourtAvailability::availableSlots).sum();
        BigDecimal lowest = withFree.stream().map(CourtAvailability::lowestAvailablePrice).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        return Flux.just(new VenueSummaryResult(VenueResult.from(venue), withFree, total, lowest));
    }

    private static boolean inWindow(SlotCalculator.SlotAvailability a, ZoneId zone, LocalTime from, LocalTime to) {
        var start = a.slot().start().atZone(zone).toLocalTime();
        var end = a.slot().end().atZone(zone).toLocalTime();
        boolean afterFrom = from == null || !start.isBefore(from);
        boolean beforeTo = to == null || !end.isAfter(to);
        return afterFrom && beforeTo;
    }
}
