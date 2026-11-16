package com.dsports.ground.interfaces;

import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.application.usecase.CancelBookingUseCase;
import com.dsports.ground.application.usecase.GetBookingUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.application.usecase.ListBookingsUseCase;
import com.dsports.ground.domain.model.CancelledBy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Customer bookings. Every call is for the signed-in customer; the id always comes from the verified token. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Ground bookings")
public class BookingController {

    public record HoldRequest(@NotNull UUID groundId, @NotNull Instant startAt, @NotNull Instant endAt) {
    }

    private final HoldBookingUseCase holdBooking;
    private final GetBookingUseCase getBooking;
    private final CancelBookingUseCase cancelBooking;
    private final ListBookingsUseCase listBookings;

    public BookingController(HoldBookingUseCase holdBooking, GetBookingUseCase getBooking, CancelBookingUseCase cancelBooking, ListBookingsUseCase listBookings) {
        this.holdBooking = holdBooking;
        this.getBooking = getBooking;
        this.cancelBooking = cancelBooking;
        this.listBookings = listBookings;
    }

    @PostMapping("/bookings")
    @Operation(summary = "Hold a slot",
        description = "Holds [startAt, endAt) of a ground for the configured payment window (10 minutes by default) and snapshots its price. "
            + "The range must be whole consecutive slots of the ground. An Idempotency-Key header is required: repeating the identical request "
            + "returns the original booking (200), reusing the key for a different request is 409. Pay for the booking through the payments API "
            + "(orderId = the booking id) before it expires.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Slot held"),
        @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier identical request"),
        @ApiResponse(responseCode = "400", description = "Missing Idempotency-Key, off-grid slot, too soon or too far ahead"),
        @ApiResponse(responseCode = "404", description = "No such active ground"),
        @ApiResponse(responseCode = "409", description = "SLOT_UNAVAILABLE, HOLD_LIMIT_REACHED or IDEMPOTENCY_KEY_REUSED")})
    public Mono<ResponseEntity<BookingResult>> hold(Authentication authentication,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                    @Valid @RequestBody HoldRequest request) {
        var command = new HoldBookingUseCase.Command(OwnerGroundController.callerId(authentication), request.groundId(), request.startAt(), request.endAt(), idempotencyKey);
        return holdBooking.execute(command)
            .map(result -> ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.booking()));
    }

    @GetMapping("/me/bookings")
    @Operation(summary = "My bookings", description = "The signed-in customer's bookings, newest first, with each ground's name and city.")
    public Mono<List<BookingResult>> mine(Authentication authentication) {
        return listBookings.mine(OwnerGroundController.callerId(authentication));
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @Operation(summary = "Cancel my booking",
        description = "Allowed until the booking starts. A held, unpaid booking is simply released. A paid booking files a refund request for the share the "
            + "cancellation policy allows (100% more than 24 hours ahead, 50% from 6 to 24 hours, otherwise none); it is paid out only after an administrator "
            + "approves it. The response says which share applies and whether a refund was requested.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Cancelled"), @ApiResponse(responseCode = "403", description = "Not your booking"),
        @ApiResponse(responseCode = "404", description = "No such booking"),
        @ApiResponse(responseCode = "409", description = "Already started, not cancellable in its status, or a payment is being captured")})
    public Mono<CancelBookingUseCase.Result> cancel(Authentication authentication, @PathVariable UUID bookingId) {
        return cancelBooking.execute(new CancelBookingUseCase.Command(OwnerGroundController.callerId(authentication), bookingId, CancelledBy.CUSTOMER));
    }

    @GetMapping("/bookings/{bookingId}")
    @Operation(summary = "Get one of my bookings")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The booking"), @ApiResponse(responseCode = "403", description = "Not your booking"),
        @ApiResponse(responseCode = "404", description = "No such booking")})
    public Mono<BookingResult> get(Authentication authentication, @PathVariable UUID bookingId) {
        return getBooking.execute(OwnerGroundController.callerId(authentication), bookingId);
    }
}
