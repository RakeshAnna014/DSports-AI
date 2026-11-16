package com.dsports.ground.interfaces;

import com.dsports.ground.application.result.OwnerGroundResult;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.application.usecase.CancelBookingUseCase;
import com.dsports.ground.application.usecase.ListBookingsUseCase;
import com.dsports.ground.application.usecase.ManageBlackoutsUseCase;
import com.dsports.ground.application.usecase.ManageGroundsUseCase;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.model.GroundBlackout;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ground-owner API. The path rule in the security config requires the GROUND_OWNER role for everything under {@code /api/v1/owner};
 * each use case additionally checks that the ground belongs to the caller. The caller id always comes from the verified token.
 */
@RestController
@RequestMapping(path = "/api/v1/owner", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Ground owner")
public class OwnerGroundController {

    private final ManageGroundsUseCase manageGrounds;
    private final ManageBlackoutsUseCase manageBlackouts;
    private final ListBookingsUseCase listBookings;
    private final CancelBookingUseCase cancelBooking;

    public OwnerGroundController(ManageGroundsUseCase manageGrounds, ManageBlackoutsUseCase manageBlackouts, ListBookingsUseCase listBookings,
                                 CancelBookingUseCase cancelBooking) {
        this.manageGrounds = manageGrounds;
        this.manageBlackouts = manageBlackouts;
        this.listBookings = listBookings;
        this.cancelBooking = cancelBooking;
    }

    @GetMapping("/grounds")
    @Operation(summary = "My grounds", description = "All grounds of the signed-in owner, including inactive ones, with the version to send back when editing.")
    public Flux<OwnerGroundResult> myGrounds(Authentication authentication) {
        return manageGrounds.list(callerId(authentication));
    }

    @PostMapping("/grounds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a ground")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid details"),
        @ApiResponse(responseCode = "403", description = "Not a ground owner")})
    public Mono<OwnerGroundResult> create(Authentication authentication, @Valid @RequestBody OwnerRequests.GroundRequest request) {
        return manageGrounds.create(callerId(authentication), request.toSpec());
    }

    @PutMapping("/grounds/{groundId}")
    @Operation(summary = "Edit a ground", description = "Replaces the ground's details and active flag. Send the version you last saw; a stale edit is "
        + "refused with 409. Existing bookings keep the price they were made at.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Updated"), @ApiResponse(responseCode = "403", description = "Not your ground"),
        @ApiResponse(responseCode = "404", description = "No such ground"), @ApiResponse(responseCode = "409", description = "Edited since you opened it")})
    public Mono<OwnerGroundResult> update(Authentication authentication, @PathVariable UUID groundId, @Valid @RequestBody OwnerRequests.UpdateGroundRequest request) {
        return manageGrounds.update(callerId(authentication), groundId, request.ground().toSpec(), request.active(), request.expectedVersion());
    }

    @PostMapping("/grounds/{groundId}/blackouts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a blackout", description = "Closes the ground for a period (maintenance, a private event). Refused with 409 BLACKOUT_CONFLICT "
        + "when a held or confirmed booking overlaps: cancel those bookings first.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "403", description = "Not your ground"),
        @ApiResponse(responseCode = "409", description = "A booking overlaps the period")})
    public Mono<GroundBlackout> addBlackout(Authentication authentication, @PathVariable UUID groundId, @Valid @RequestBody OwnerRequests.BlackoutRequest request) {
        return manageBlackouts.add(callerId(authentication), groundId, request.startAt(), request.endAt(), request.reason());
    }

    @GetMapping("/grounds/{groundId}/blackouts")
    @Operation(summary = "A ground's blackouts", description = "Blackouts overlapping the range; by default from now for 90 days.")
    public Flux<GroundBlackout> blackouts(Authentication authentication, @PathVariable UUID groundId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return manageBlackouts.list(callerId(authentication), groundId, from, to);
    }

    @GetMapping("/bookings")
    @Operation(summary = "Bookings at one of my grounds", description = "On one local day, or (without a date) from now for 30 days. Shows the bookings, "
        + "not the customers: no customer identity is exposed.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The bookings, in time order"), @ApiResponse(responseCode = "403", description = "Not your ground")})
    public Mono<List<BookingResult>> bookings(Authentication authentication, @RequestParam UUID groundId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return listBookings.atGround(callerId(authentication), groundId, date);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking at my ground", description = "Always refunds a paid booking in full, whatever the cancellation policy, and pays the "
        + "refund out automatically. The customer is notified. Not possible once the booking has started.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Cancelled"), @ApiResponse(responseCode = "403", description = "Not your ground"),
        @ApiResponse(responseCode = "409", description = "Already started or not cancellable in its status")})
    public Mono<CancelBookingUseCase.Result> cancelBooking(Authentication authentication, @PathVariable UUID bookingId) {
        return cancelBooking.execute(new CancelBookingUseCase.Command(callerId(authentication), bookingId, CancelledBy.OWNER));
    }

    @PostMapping("/bookings/{bookingId}/no-show")
    @Operation(summary = "Mark a booking as a no-show", description = "For a confirmed booking whose time has ended and whose customer never came.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Marked"), @ApiResponse(responseCode = "403", description = "Not your ground"),
        @ApiResponse(responseCode = "409", description = "Not confirmed, or not ended yet")})
    public Mono<BookingResult> noShow(Authentication authentication, @PathVariable UUID bookingId) {
        return listBookings.markNoShow(callerId(authentication), bookingId);
    }

    static UUID callerId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not authenticated");
        }
        return UUID.fromString(authentication.getPrincipal().toString());
    }
}
