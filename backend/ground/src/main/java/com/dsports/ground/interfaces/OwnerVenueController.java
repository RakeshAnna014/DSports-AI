package com.dsports.ground.interfaces;

import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.application.usecase.CancelBookingUseCase;
import com.dsports.ground.application.usecase.ListBookingsUseCase;
import com.dsports.ground.application.usecase.ManageBlackoutsUseCase;
import com.dsports.ground.application.usecase.ManageCourtsUseCase;
import com.dsports.ground.application.usecase.ManageVenuesUseCase;
import com.dsports.ground.domain.model.CancelledBy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Venue-owner API. The path rule in the security config requires the GROUND_OWNER role for everything under {@code /api/v1/owner}; each use case
 * additionally checks that the venue belongs to the caller. Besides the shared venue, court and blackout management it serves the owner's bookings.
 */
@RestController
@RequestMapping(path = "/api/v1/owner", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Venue owner")
public class OwnerVenueController extends VenueManagementController {

    private final ListBookingsUseCase listBookings;
    private final CancelBookingUseCase cancelBooking;

    public OwnerVenueController(ManageVenuesUseCase manageVenues, ManageCourtsUseCase manageCourts, ManageBlackoutsUseCase manageBlackouts,
                                ListBookingsUseCase listBookings, CancelBookingUseCase cancelBooking) {
        super(manageVenues, manageCourts, manageBlackouts);
        this.listBookings = listBookings;
        this.cancelBooking = cancelBooking;
    }

    @Override
    protected boolean administrator() {
        return false;
    }

    @GetMapping("/venues/{venueId}/bookings")
    @Operation(summary = "Bookings at one of my venues", description = "Any court, or one with courtId; on one local day, or (without a date) from now for 30 days. "
        + "Shows the bookings, not the customers: no customer identity is exposed.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The bookings, in time order"), @ApiResponse(responseCode = "403", description = "Not your venue")})
    public Mono<List<BookingResult>> bookings(Authentication authentication, @PathVariable UUID venueId,
                                              @RequestParam(required = false) UUID courtId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return listBookings.atVenue(Principals.callerId(authentication), venueId, courtId, date);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking at my venue", description = "Always refunds a paid booking in full, whatever the cancellation policy, and pays the "
        + "refund out automatically. The customer is notified. Not possible once the booking has started.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Cancelled"), @ApiResponse(responseCode = "403", description = "Not your venue"),
        @ApiResponse(responseCode = "409", description = "Already started or not cancellable in its status")})
    public Mono<CancelBookingUseCase.Result> cancelBooking(Authentication authentication, @PathVariable UUID bookingId) {
        return cancelBooking.execute(new CancelBookingUseCase.Command(Principals.callerId(authentication), bookingId, CancelledBy.OWNER));
    }

    @PostMapping("/bookings/{bookingId}/no-show")
    @Operation(summary = "Mark a booking as a no-show", description = "For a confirmed booking whose time has ended and whose customer never came.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Marked"), @ApiResponse(responseCode = "403", description = "Not your venue"),
        @ApiResponse(responseCode = "409", description = "Not confirmed, or not ended yet")})
    public Mono<BookingResult> noShow(Authentication authentication, @PathVariable UUID bookingId) {
        return listBookings.markNoShow(Principals.callerId(authentication), bookingId);
    }
}
