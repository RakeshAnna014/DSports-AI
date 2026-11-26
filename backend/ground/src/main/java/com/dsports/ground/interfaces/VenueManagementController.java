package com.dsports.ground.interfaces;

import com.dsports.ground.application.result.CourtResult;
import com.dsports.ground.application.result.ManagedVenueResult;
import com.dsports.ground.application.usecase.Caller;
import com.dsports.ground.application.usecase.ManageBlackoutsUseCase;
import com.dsports.ground.application.usecase.ManageCourtsUseCase;
import com.dsports.ground.application.usecase.ManageVenuesUseCase;
import com.dsports.ground.domain.model.Blackout;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * The venue, court and blackout management API, served twice: for an owner under {@code /api/v1/owner/venues} (their own venues only) and for an
 * administrator under {@code /api/v1/admin/venues} (any venue, on an owner's behalf). Which one it is comes from the route, and the security config
 * requires the matching role on each path; every use case additionally checks ownership, so the UI is never the control.
 */
public abstract class VenueManagementController {

    private final ManageVenuesUseCase manageVenues;
    private final ManageCourtsUseCase manageCourts;
    private final ManageBlackoutsUseCase manageBlackouts;

    protected VenueManagementController(ManageVenuesUseCase manageVenues, ManageCourtsUseCase manageCourts, ManageBlackoutsUseCase manageBlackouts) {
        this.manageVenues = manageVenues;
        this.manageCourts = manageCourts;
        this.manageBlackouts = manageBlackouts;
    }

    /** True on the administrator route. */
    protected abstract boolean administrator();

    private Caller caller(Authentication authentication) {
        var id = Principals.callerId(authentication);
        return administrator() ? Caller.administrator(id) : Caller.owner(id);
    }

    @GetMapping("/venues")
    @Operation(summary = "List venues", description = "The signed-in owner's venues, or every venue for an administrator, with their courts (inactive ones too) "
        + "and the version to send back when editing. ownerEligible is false when the owner no longer holds the role.")
    public Flux<ManagedVenueResult> list(Authentication authentication) {
        return manageVenues.list(caller(authentication));
    }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a venue", description = "An owner creates a venue for themselves. An administrator must give ownerId: the user who will own it, "
        + "who must currently hold the ground-owner role (409 OWNER_NOT_ELIGIBLE otherwise).")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid details"),
        @ApiResponse(responseCode = "403", description = "Not allowed"), @ApiResponse(responseCode = "409", description = "The given owner does not hold the role")})
    public Mono<ManagedVenueResult> create(Authentication authentication, @Valid @RequestBody VenueRequests.CreateVenueRequest request) {
        return manageVenues.create(caller(authentication), request.ownerId(), request.venue().toSpec());
    }

    @GetMapping("/venues/{venueId}")
    @Operation(summary = "Get a venue I manage")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The venue"), @ApiResponse(responseCode = "403", description = "Not your venue"),
        @ApiResponse(responseCode = "404", description = "No such venue")})
    public Mono<ManagedVenueResult> get(Authentication authentication, @PathVariable UUID venueId) {
        return manageVenues.get(caller(authentication), venueId);
    }

    @PutMapping("/venues/{venueId}")
    @Operation(summary = "Edit a venue", description = "Replaces the venue's details and active flag. Send the version you last saw; a stale edit is refused with 409. "
        + "An administrator may also send ownerId to hand the venue to another ground owner (the new owner must hold the role), which makes a venue whose owner lost "
        + "the role bookable again. Existing bookings are untouched.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated"), @ApiResponse(responseCode = "403", description = "Not your venue"),
        @ApiResponse(responseCode = "404", description = "No such venue"), @ApiResponse(responseCode = "409", description = "Edited since you opened it, or the new owner lacks the role")})
    public Mono<ManagedVenueResult> update(Authentication authentication, @PathVariable UUID venueId, @Valid @RequestBody VenueRequests.UpdateVenueRequest request) {
        return manageVenues.update(caller(authentication), venueId, request.venue().toSpec(), request.active(), request.expectedVersion(), request.ownerId());
    }

    @PostMapping("/venues/{venueId}/courts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a court", description = "A court has its own slot length and prices and offers one or more bookable catalogue sports. "
        + "The court name must be unique at the venue (any case).")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid details"),
        @ApiResponse(responseCode = "403", description = "Not your venue"), @ApiResponse(responseCode = "409", description = "Duplicate name"),
        @ApiResponse(responseCode = "422", description = "A sport is not bookable")})
    public Mono<CourtResult> addCourt(Authentication authentication, @PathVariable UUID venueId, @Valid @RequestBody VenueRequests.CourtRequest request) {
        return manageCourts.create(caller(authentication), venueId, request.toSpec());
    }

    @PutMapping("/venues/{venueId}/courts/{courtId}")
    @Operation(summary = "Edit a court", description = "Replaces the court's details, sports and active flag. Send the version you last saw. Existing bookings keep their price, court and sport.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated"), @ApiResponse(responseCode = "403", description = "Not your venue"),
        @ApiResponse(responseCode = "404", description = "No such court"), @ApiResponse(responseCode = "409", description = "Edited since you opened it"),
        @ApiResponse(responseCode = "422", description = "A sport is not bookable")})
    public Mono<CourtResult> updateCourt(Authentication authentication, @PathVariable UUID venueId, @PathVariable UUID courtId,
                                         @Valid @RequestBody VenueRequests.UpdateCourtRequest request) {
        return manageCourts.update(caller(authentication), venueId, courtId, request.court().toSpec(), request.active(), request.expectedVersion());
    }

    @PostMapping("/venues/{venueId}/blackouts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a blackout", description = "Closes the whole venue, or one court when courtId is given, for a period (maintenance, a private event). "
        + "Refused with 409 BLACKOUT_CONFLICT when a held or confirmed booking overlaps: cancel those bookings first.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "403", description = "Not your venue"),
        @ApiResponse(responseCode = "409", description = "A booking overlaps the period")})
    public Mono<Blackout> addBlackout(Authentication authentication, @PathVariable UUID venueId, @Valid @RequestBody VenueRequests.BlackoutRequest request) {
        return manageBlackouts.add(caller(authentication), venueId, request.courtId(), request.startAt(), request.endAt(), request.reason());
    }

    @GetMapping("/venues/{venueId}/blackouts")
    @Operation(summary = "A venue's blackouts", description = "Blackouts overlapping the range; by default from now for 90 days.")
    public Flux<Blackout> blackouts(Authentication authentication, @PathVariable UUID venueId,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return manageBlackouts.list(caller(authentication), venueId, from, to);
    }
}
