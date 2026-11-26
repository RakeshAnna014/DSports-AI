package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.PublicPlayerStats;
import com.dsports.cricket.application.usecase.PlayerStatsUseCase;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/** A player's career statistics (public, names and numbers only) and the administrators' rebuild of the stored rows from the logs. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket career statistics")
public class PlayerStatsController {

    private static final CacheControl REVALIDATE = CacheControl.noCache();

    private final PlayerStatsUseCase stats;

    public PlayerStatsController(PlayerStatsUseCase stats) {
        this.stats = stats;
    }

    @GetMapping("/players/{playerId}/stats")
    @Operation(summary = "A player's career statistics (no sign-in)", description = "Batting, bowling and fielding from the player's finished (completed) matches, one line for each ball type "
        + "(tennis and leather are never added together). Names and numbers only. A ratio that cannot be worked out is null. An unknown player and a player with no finished match are the same 404.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The career"), @ApiResponse(responseCode = "304", description = "Unchanged since the ETag you sent"),
        @ApiResponse(responseCode = "404", description = "No such player, or no finished match yet")})
    public Mono<ResponseEntity<PublicPlayerStats>> career(ServerWebExchange exchange, @PathVariable UUID playerId) {
        return stats.career(playerId).map(career -> {
            var etag = PublicScorecardEtag.of(0, career);
            if (exchange.checkNotModified(etag)) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(REVALIDATE).<PublicPlayerStats>build();
            }
            return ResponseEntity.ok().eTag(etag).cacheControl(REVALIDATE).body(career);
        });
    }

    @PostMapping("/admin/cricket/stats/rebuild")
    @Operation(summary = "Rebuild every career row from the match logs (ADMIN)", description = "Deletes and recomputes the stored statistics rows of every completed match from its log. "
        + "It gives the same numbers as the incremental updates; use it after a rules change or to prove the projection. Returns how many matches it visited.")
    public Mono<Map<String, Integer>> rebuild() {
        return stats.rebuild().map(n -> Map.of("matchesRebuilt", n));
    }
}
