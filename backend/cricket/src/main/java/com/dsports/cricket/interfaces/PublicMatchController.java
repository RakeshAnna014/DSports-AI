package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.PublicMatchSummary;
import com.dsports.cricket.application.result.PublicAnalytics;
import com.dsports.cricket.application.result.PublicScorecard;
import com.dsports.cricket.application.usecase.PublicMatchesUseCase;
import com.dsports.cricket.application.usecase.PublicScorecardEtag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The only cricket routes that need no sign-in: a started match's scorecard and analytics (charts and commentary) and the list of matches being played. All are read-only and return names and numbers
 * only (see {@link PublicScorecard}). Everything else under {@code /api/v1/matches} stays behind a sign-in. The security configuration opens exactly these two
 * by path alone (the query string never decides anything), and {@code SecurityPolicyTest} pins both. The list has a path of its own,
 * {@code /api/v1/public/matches/live}, so {@code GET /api/v1/matches} is only ever "my matches".
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket public scorecards")
public class PublicMatchController {

    /** A client keeps nothing without asking the server first; the ETag makes that question cheap (304 with no body when nothing was scored). */
    private static final CacheControl REVALIDATE = CacheControl.noCache();

    private final PublicMatchesUseCase publicMatches;

    public PublicMatchController(PublicMatchesUseCase publicMatches) {
        this.publicMatches = publicMatches;
    }

    @GetMapping("/matches/{matchId}/scorecard")
    @Operation(summary = "A started match's scorecard (no sign-in)", description = "Names and numbers only. The ETag covers the whole response (the seq of the last event plus a digest of everything shown, "
        + "so a rename changes it too): send it back as If-None-Match and an unchanged scorecard answers 304 with no body. A match that has not started is 404, the same as one that does not exist.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The scorecard"), @ApiResponse(responseCode = "304", description = "Nothing scored since the ETag you sent"),
        @ApiResponse(responseCode = "404", description = "No such match, or it has not started")})
    public Mono<ResponseEntity<PublicScorecard>> scorecard(ServerWebExchange exchange, @PathVariable UUID matchId) {
        return publicMatches.scorecard(matchId).map(card -> {
            var etag = PublicScorecardEtag.of(card);
            if (exchange.checkNotModified(etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(REVALIDATE).<PublicScorecard>build();
            }
            return ResponseEntity.ok().eTag(etag).cacheControl(REVALIDATE).body(card);
        });
    }

    @GetMapping("/matches/{matchId}/analytics")
    @Operation(summary = "A started match's charts and commentary (no sign-in)", description = "Runs in each over (the Manhattan chart), the running total after each over (the worm and run rate), the partnership for each wicket "
        + "and a line of commentary for each ball, worked out from the log. Names and numbers only. Same ETag rule and same 404 as the scorecard.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The analytics"), @ApiResponse(responseCode = "304", description = "Nothing new since the ETag you sent"),
        @ApiResponse(responseCode = "404", description = "No such match, or it has not started")})
    public Mono<ResponseEntity<PublicAnalytics>> analytics(ServerWebExchange exchange, @PathVariable UUID matchId) {
        return publicMatches.analytics(matchId).map(result -> {
            var etag = PublicScorecardEtag.of(result.seq(), result);
            if (exchange.checkNotModified(etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(REVALIDATE).<PublicAnalytics>build();
            }
            return ResponseEntity.ok().eTag(etag).cacheControl(REVALIDATE).body(result);
        });
    }

    @GetMapping("/public/matches/live")
    @Operation(summary = "Matches being played (no sign-in)", description = "Started matches that are in an innings or at the innings break, most recently scored first. "
        + "city (optional) is matched without regard to letter case. Paginated: a page is up to 50 matches and hasNext says whether another follows.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "One page"), @ApiResponse(responseCode = "400", description = "Page, size or city out of range")})
    public Mono<PublicMatchSummary.Page<PublicMatchSummary>> live(@RequestParam(required = false) String city, @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        return publicMatches.list(city, page, size);
    }
}
