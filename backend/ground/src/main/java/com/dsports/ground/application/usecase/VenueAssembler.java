package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.OwnerEligibilityPort;
import com.dsports.ground.application.port.SportCatalogPort;
import com.dsports.ground.application.port.SportCatalogPort.SportRef;
import com.dsports.ground.application.result.CourtResult;
import com.dsports.ground.application.result.ManagedVenueResult;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the venue and court views: resolves sport names from the catalogue and whether each owner still holds the role. */
public class VenueAssembler {

    private final CourtRepository courts;
    private final SportCatalogPort sports;
    private final OwnerEligibilityPort owners;

    public VenueAssembler(CourtRepository courts, SportCatalogPort sports, OwnerEligibilityPort owners) {
        this.courts = courts;
        this.sports = sports;
        this.owners = owners;
    }

    /** The courts as results, with their sport names (one catalogue lookup for all of them). */
    public Mono<List<CourtResult>> courtResults(Collection<Court> list) {
        var ids = list.stream().flatMap(c -> c.getSportIds().stream()).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Mono.just(list.stream().map(c -> CourtResult.from(c, Map.of())).toList());
        }
        return sports.findByIds(ids).collectMap(SportRef::id, Function.identity())
            .map(byId -> list.stream().map(c -> CourtResult.from(c, byId)).toList());
    }

    /** A venue with all its courts (inactive too), for its owner or an administrator. */
    public Mono<ManagedVenueResult> managed(Venue venue) {
        return managedAll(List.of(venue)).map(list -> list.get(0));
    }

    public Mono<List<ManagedVenueResult>> managedAll(List<Venue> venues) {
        if (venues.isEmpty()) {
            return Mono.just(List.of());
        }
        var ownerIds = venues.stream().map(Venue::getOwnerId).collect(Collectors.toSet());
        return owners.ownersAmong(ownerIds).flatMap(eligible -> reactor.core.publisher.Flux.fromIterable(venues)
            .concatMap(venue -> courts.findByVenue(venue.getId()).collectList().flatMap(this::courtResults)
                .map(courtResults -> ManagedVenueResult.from(venue, eligible.contains(venue.getOwnerId()), courtResults)))
            .collectList());
    }

    /** Which owners among these ids still hold the ground-owner role right now. */
    public Mono<java.util.Set<UUID>> eligibleOwners(Collection<UUID> ownerIds) {
        return owners.ownersAmong(ownerIds);
    }
}
