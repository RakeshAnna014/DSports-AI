package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.PublicMatchSummary;
import com.dsports.cricket.application.result.PublicScorecard;
import com.dsports.cricket.application.usecase.PublicMatchesUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * The only cricket routes that need no sign-in: a started match's scorecard and the list of matches being played. Both are read-only and return names and numbers
 * only (see {@link PublicScorecard}). Everything else under {@code /api/v1/matches} stays behind a sign-in. The security configuration opens exactly these two
 * (the list only when the {@code status} parameter is present, which is also what selects this handler over "my matches"), and {@code SecurityPolicyTest} pins both.
 */
@RestController
@RequestMapping(path = "/api/v1/matches", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket public scorecards")
public class PublicMatchController {

    /** A client keeps nothing without asking the server first; the ETag makes that question cheap (304 with no body when nothing was scored). */
    private static final CacheControl REVALIDATE = CacheControl.noCache();

    private final PublicMatchesUseCase publicMatches;

    public PublicMatchController(PublicMatchesUseCase publicMatches) {
        this.publicMatches = publicMatches;
    }

    @GetMapping("/{matchId}/scorecard")
    @Operation(summary = "A started match's scorecard (no sign-in)", description = "Names and numbers only. The ETag is the seq of the last scored event: send it back as If-None-Match "
        + "and an unchanged match answers 304 with no body. A match that has not started is 404, the same as one that does not exist.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The scorecard"), @ApiResponse(responseCode = "304", description = "Nothing scored since the ETag you sent"),
        @ApiResponse(responseCode = "404", description = "No such match, or it has not started")})
    public Mono<ResponseEntity<PublicScorecard>> scorecard(ServerWebExchange exchange, @PathVariable UUID matchId) {
        return publicMatches.version(matchId).flatMap(seq -> {
            if (exchange.checkNotModified(etag(seq))) {
                return Mono.just(ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag(seq)).cacheControl(REVALIDATE).<PublicScorecard>build());
            }
            return publicMatches.scorecard(matchId).map(card -> ResponseEntity.ok().eTag(etag(card.seq())).cacheControl(REVALIDATE).body(card));
        });
    }

    @GetMapping(params = "status")
    @Operation(summary = "Matches being played (no sign-in)", description = "Started matches that are in an innings or at the innings break, most recently scored first. "
        + "status must be LIVE; city (optional) is matched without regard to letter case. Paginated: a page is up to 50 matches and hasNext says whether another follows.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "One page"), @ApiResponse(responseCode = "400", description = "Unknown status, or page or size out of range")})
    public Mono<PublicMatchSummary.Page<PublicMatchSummary>> live(@Parameter(description = "Only LIVE is supported") @RequestParam String status, @RequestParam(required = false) String city,
                                                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return publicMatches.list(status, city, page, size);
    }

    private static String etag(int seq) {
        return "\"" + seq + "\"";
    }
}
