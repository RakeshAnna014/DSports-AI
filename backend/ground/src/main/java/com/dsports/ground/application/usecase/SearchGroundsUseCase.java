package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.result.GroundResult;
import com.dsports.ground.application.result.GroundSummaryResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.policy.SlotCalculator;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * Finds active grounds, optionally in one city. When a date is given, each hit reports how many slots are free that day (inside the
 * optional {@code from}..{@code to} window, read in the ground's own zone) and the cheapest one, and grounds with none are left out.
 */
public class SearchGroundsUseCase {

    private static final int MAX_RESULTS = 50;

    private final GroundRepository grounds;
    private final GetGroundSlotsUseCase slots;

    public SearchGroundsUseCase(GroundRepository grounds, GetGroundSlotsUseCase slots) {
        this.grounds = grounds;
        this.slots = slots;
    }

    public Flux<GroundSummaryResult> execute(String city, LocalDate date, LocalTime from, LocalTime to) {
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
        var candidates = grounds.findActive(normalisedCity).take(MAX_RESULTS);
        if (date == null) {
            return candidates.map(g -> new GroundSummaryResult(GroundResult.from(g), null, null));
        }
        return candidates.concatMap(ground -> slots.availability(ground, date).flatMapMany(list -> {
            var free = list.stream().filter(SlotCalculator.SlotAvailability::available)
                .filter(a -> inWindow(a, ground.getTimeZone(), from, to)).toList();
            if (free.isEmpty()) {
                return Flux.empty();
            }
            var lowest = free.stream().map(SlotCalculator.SlotAvailability::price).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            return Flux.just(new GroundSummaryResult(GroundResult.from(ground), free.size(), lowest));
        }));
    }

    private static boolean inWindow(SlotCalculator.SlotAvailability a, java.time.ZoneId zone, LocalTime from, LocalTime to) {
        var start = a.slot().start().atZone(zone).toLocalTime();
        var end = a.slot().end().atZone(zone).toLocalTime();
        boolean afterFrom = from == null || !start.isBefore(from);
        boolean beforeTo = to == null || !end.isAfter(to);
        return afterFrom && beforeTo;
    }
}
