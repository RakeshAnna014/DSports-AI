package com.dsports.ground.interfaces;

import com.dsports.ground.application.result.SlotResult;
import com.dsports.ground.application.result.VenueDetailResult;
import com.dsports.ground.application.result.VenueSummaryResult;
import com.dsports.ground.application.usecase.GetCourtSlotsUseCase;
import com.dsports.ground.application.usecase.GetVenueUseCase;
import com.dsports.ground.application.usecase.SearchVenuesUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Public, read-only: anyone can browse venues and see which slots of which court are free. Booking itself needs an account. */
@RestController
@RequestMapping(path = "/api/v1/venues", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Venues")
public class VenueController {

    private final SearchVenuesUseCase searchVenues;
    private final GetVenueUseCase getVenue;
    private final GetCourtSlotsUseCase getSlots;

    public VenueController(SearchVenuesUseCase searchVenues, GetVenueUseCase getVenue, GetCourtSlotsUseCase getSlots) {
        this.searchVenues = searchVenues;
        this.getVenue = getVenue;
        this.getSlots = getSlots;
    }

    @GetMapping
    @Operation(summary = "Search venues",
        description = "Active venues with a court that offers the sport (a catalogue sport id), optionally in one city (matched ignoring case). With a date, "
            + "each hit reports, per court, its free slots that day (within the optional from/to window, read in the venue's own time zone) and the "
            + "cheapest one; venues and courts with no free slot are left out. Venues whose owner no longer holds the ground-owner role are hidden.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Matching venues"),
        @ApiResponse(responseCode = "400", description = "Invalid sport id, date, time or window")})
    public Flux<VenueSummaryResult> search(@RequestParam(required = false) UUID sport,
                                           @RequestParam(required = false) String city,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime to) {
        return searchVenues.execute(sport, city, date, from, to);
    }

    @GetMapping("/{venueId}")
    @Operation(summary = "Get a venue", description = "The venue with its active courts and the sports each offers. acceptingBookings is false while its owner no longer holds the role.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The venue"),
        @ApiResponse(responseCode = "404", description = "No such active venue")})
    public Mono<VenueDetailResult> get(@PathVariable UUID venueId) {
        return getVenue.execute(venueId);
    }

    @GetMapping("/{venueId}/courts/{courtId}/slots")
    @Operation(summary = "Slots of a court on a day",
        description = "The day's slot grid of one court with each slot's price and whether it can be booked now. An unavailable slot carries its reason: "
            + "BOOKED, BLACKED_OUT, TOO_SOON, TOO_FAR or NOT_ACCEPTING. Slots are computed, not stored.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The slots, in time order"),
        @ApiResponse(responseCode = "400", description = "Invalid date"),
        @ApiResponse(responseCode = "404", description = "No such active venue or court")})
    public Mono<List<SlotResult>> slots(@PathVariable UUID venueId, @PathVariable UUID courtId,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return getSlots.execute(venueId, courtId, date);
    }
}
