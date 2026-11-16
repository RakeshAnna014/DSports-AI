package com.dsports.ground.interfaces;

import com.dsports.ground.application.result.GroundResult;
import com.dsports.ground.application.result.GroundSummaryResult;
import com.dsports.ground.application.result.SlotResult;
import com.dsports.ground.application.usecase.GetGroundSlotsUseCase;
import com.dsports.ground.application.usecase.GetGroundUseCase;
import com.dsports.ground.application.usecase.SearchGroundsUseCase;
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

/** Public, read-only: anyone can browse grounds and see which slots are free. Booking itself needs an account. */
@RestController
@RequestMapping(path = "/api/v1/grounds", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Grounds")
public class GroundController {

    private final SearchGroundsUseCase searchGrounds;
    private final GetGroundUseCase getGround;
    private final GetGroundSlotsUseCase getSlots;

    public GroundController(SearchGroundsUseCase searchGrounds, GetGroundUseCase getGround, GetGroundSlotsUseCase getSlots) {
        this.searchGrounds = searchGrounds;
        this.getGround = getGround;
        this.getSlots = getSlots;
    }

    @GetMapping
    @Operation(summary = "Search grounds",
        description = "Active grounds, optionally in one city. With a date, each hit reports its free slots that day (within the optional "
            + "from/to window, read in the ground's own time zone) and the cheapest one; grounds with no free slot are left out.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Matching grounds"),
        @ApiResponse(responseCode = "400", description = "Invalid date, time or window")})
    public Flux<GroundSummaryResult> search(@RequestParam(required = false) String city,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime to) {
        return searchGrounds.execute(city, date, from, to);
    }

    @GetMapping("/{groundId}")
    @Operation(summary = "Get a ground")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The ground"),
        @ApiResponse(responseCode = "404", description = "No such active ground")})
    public Mono<GroundResult> get(@PathVariable UUID groundId) {
        return getGround.execute(groundId);
    }

    @GetMapping("/{groundId}/slots")
    @Operation(summary = "Slots of a ground on a day",
        description = "The day's slot grid with each slot's price and whether it can be booked now. An unavailable slot carries its reason: "
            + "BOOKED, BLACKED_OUT, TOO_SOON or TOO_FAR. Slots are computed, not stored.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The slots, in time order"),
        @ApiResponse(responseCode = "400", description = "Invalid date"),
        @ApiResponse(responseCode = "404", description = "No such active ground")})
    public Mono<List<SlotResult>> slots(@PathVariable UUID groundId,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return getSlots.execute(groundId, date);
    }
}
