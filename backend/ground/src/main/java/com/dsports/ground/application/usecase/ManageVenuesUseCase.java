package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.OwnerEligibilityPort;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.ManagedVenueResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Venue owners (and administrators, on an owner's behalf) creating, listing and editing venues. The caller's id always comes from the verified
 * token, never from the request. Handing a venue to another owner is an administrator's action, and the new owner must currently hold the
 * ground-owner role; that is also how a venue whose owner lost the role becomes bookable again.
 */
public class ManageVenuesUseCase {

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final OwnerEligibilityPort owners;
    private final VenueAssembler assembler;
    private final Clock clock;

    public ManageVenuesUseCase(VenueRepository venues, CourtRepository courts, OwnerEligibilityPort owners, VenueAssembler assembler, Clock clock) {
        this.venues = venues;
        this.courts = courts;
        this.owners = owners;
        this.assembler = assembler;
        this.clock = clock;
    }

    /**
     * An owner creates a venue for themselves. An administrator names the owner ({@code ownerId}), who must currently hold the ground-owner role.
     */
    public Mono<ManagedVenueResult> create(Caller caller, UUID ownerId, Venue.Spec spec) {
        UUID target = caller.admin() ? ownerId : caller.id();
        if (target == null) {
            return Mono.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "An owner is required"));
        }
        var eligible = caller.admin() ? requireEligible(target) : Mono.<Void>empty();
        return eligible.then(Mono.fromSupplier(() -> Venue.create(target, spec, clock.instant())))
            .flatMap(venues::save)
            .flatMap(assembler::managed);
    }

    /** An owner's own venues; every venue for an administrator. */
    public Flux<ManagedVenueResult> list(Caller caller) {
        var found = caller.admin() ? venues.findAll() : venues.findByOwner(caller.id());
        return found.collectList().flatMap(assembler::managedAll).flatMapMany(Flux::fromIterable);
    }

    public Mono<ManagedVenueResult> get(Caller caller, UUID venueId) {
        return VenueAccess.accessible(venues, caller, venueId).flatMap(assembler::managed);
    }

    /**
     * Replaces a venue's details and active flag, and (administrators only) its owner. {@code expectedVersion} must be the version the caller last
     * saw: an edit made on a stale copy is refused with a conflict instead of silently overwriting a newer one. Existing bookings are untouched.
     */
    public Mono<ManagedVenueResult> update(Caller caller, UUID venueId, Venue.Spec spec, boolean active, int expectedVersion, UUID newOwnerId) {
        return VenueAccess.accessible(venues, caller, venueId).flatMap(venue -> {
            if (venue.getVersion() != expectedVersion) {
                return Mono.<ManagedVenueResult>error(new GroundDomainException(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                    "This venue was changed since you opened it. Reload and try again."));
            }
            boolean reassign = newOwnerId != null && !newOwnerId.equals(venue.getOwnerId());
            if (reassign && !caller.admin()) {
                return Mono.<ManagedVenueResult>error(new GroundDomainException(GroundErrorCode.VENUE_NOT_OWNED_BY_USER, "Only an administrator can reassign a venue"));
            }
            var now = clock.instant();
            return courts.findByVenue(venueId).collectList()
                .flatMap(list -> {
                    long openMinutes = spec.opensAt() == null || spec.closesAt() == null ? 0 : Duration.between(spec.opensAt(), spec.closesAt()).toMinutes();
                    boolean fits = list.stream().noneMatch(c -> c.getSlotMinutes() > openMinutes);
                    return fits ? Mono.<Void>empty() : Mono.<Void>error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR,
                        "The opening hours must fit at least one slot of every court"));
                })
                .then(reassign ? requireEligible(newOwnerId) : Mono.<Void>empty())
                .then(Mono.defer(() -> {
                    venue.update(spec, now);
                    if (active) {
                        venue.activate(now);
                    } else {
                        venue.deactivate(now);
                    }
                    if (reassign) {
                        venue.reassignTo(newOwnerId, now);
                    }
                    return venues.save(venue);
                }))
                .flatMap(assembler::managed);
        });
    }

    private Mono<Void> requireEligible(UUID ownerId) {
        return owners.isOwner(ownerId).flatMap(isOwner -> isOwner
            ? Mono.<Void>empty()
            : Mono.<Void>error(new GroundDomainException(GroundErrorCode.OWNER_NOT_ELIGIBLE, "That user does not hold the ground-owner role")));
    }
}
