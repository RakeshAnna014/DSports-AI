package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.SportCatalogPort;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.CourtResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.HashSet;
import java.util.UUID;

/**
 * Courts of a venue and the sports each one supports. A court can only offer sports the catalogue marks bookable. Editing is version-checked like
 * venues; existing bookings keep their price snapshot, court and sport.
 */
public class ManageCourtsUseCase {

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final SportCatalogPort sports;
    private final VenueAssembler assembler;
    private final Clock clock;

    public ManageCourtsUseCase(VenueRepository venues, CourtRepository courts, SportCatalogPort sports, VenueAssembler assembler, Clock clock) {
        this.venues = venues;
        this.courts = courts;
        this.sports = sports;
        this.assembler = assembler;
        this.clock = clock;
    }

    public Mono<CourtResult> create(Caller caller, UUID venueId, Court.Spec spec) {
        return VenueAccess.accessible(venues, caller, venueId).flatMap(venue ->
            requireBookableSports(spec)
                .then(Mono.fromSupplier(() -> Court.create(venue, spec, clock.instant())))
                .flatMap(courts::save)
                .flatMap(court -> assembler.courtResults(java.util.List.of(court)).map(list -> list.get(0))));
    }

    public Mono<CourtResult> update(Caller caller, UUID venueId, UUID courtId, Court.Spec spec, boolean active, int expectedVersion) {
        return VenueAccess.accessible(venues, caller, venueId)
            .then(Mono.defer(() -> VenueAccess.courtOf(courts, venueId, courtId)))
            .flatMap(court -> {
                if (court.getVersion() != expectedVersion) {
                    return Mono.<CourtResult>error(new GroundDomainException(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                        "This court was changed since you opened it. Reload and try again."));
                }
                return requireBookableSports(spec).then(Mono.defer(() -> {
                    var now = clock.instant();
                    court.update(spec, now);
                    if (active) {
                        court.activate(now);
                    } else {
                        court.deactivate(now);
                    }
                    return courts.save(court);
                })).flatMap(saved -> assembler.courtResults(java.util.List.of(saved)).map(list -> list.get(0)));
            });
    }

    private Mono<Void> requireBookableSports(Court.Spec spec) {
        if (spec == null || spec.sportIds() == null || spec.sportIds().isEmpty()) {
            return Mono.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A court needs at least one sport"));
        }
        var wanted = new HashSet<>(spec.sportIds());
        return sports.findByIds(wanted).filter(SportCatalogPort.SportRef::bookable).map(SportCatalogPort.SportRef::id).collectList()
            .flatMap(found -> found.containsAll(wanted)
                ? Mono.<Void>empty()
                : Mono.<Void>error(new GroundDomainException(GroundErrorCode.SPORT_NOT_BOOKABLE, "Every sport of a court must be a bookable sport from the catalogue")));
    }
}
