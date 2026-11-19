package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.MatchResult;
import com.dsports.cricket.application.usecase.ManageMatchesUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Match setup: teams, settings, toss, playing XIs and scorers. Signed-in users only; a match is visible to its creator, its scorers and people with a team in it (anyone else gets 404). */
@RestController
@RequestMapping(path = "/api/v1/matches", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket matches")
public class MatchController {

    private final ManageMatchesUseCase matches;

    public MatchController(ManageMatchesUseCase matches) {
        this.matches = matches;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a match", description = "Two different teams you can see (you created them, or you play in them), the overs, the ball type and optional rule overrides. "
        + "An optional bookingId must be your own paid, confirmed cricket booking not used by another match; its venue is copied onto the match.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid settings"),
        @ApiResponse(responseCode = "404", description = "A team is not yours to use"), @ApiResponse(responseCode = "409", description = "Archived team, or the booking is used")})
    public Mono<MatchResult> create(Authentication authentication, @Valid @RequestBody CricketRequests.CreateMatchRequest request) {
        return matches.create(Principals.actor(authentication),
            new ManageMatchesUseCase.CreateCommand(request.teamAId(), request.teamBId(), request.toSettings(), request.bookingId()));
    }

    @GetMapping(params = "!status")
    @Operation(summary = "My matches", description = "Matches you created, may score, or have a team in, newest first. (With a status parameter the same path is the public live list.)")
    public Flux<MatchResult> mine(Authentication authentication) {
        return matches.listMine(Principals.actor(authentication));
    }

    @GetMapping("/{matchId}")
    @Operation(summary = "Get a match for setup", description = "Settings, toss, playing XIs and (for scorers) the scorer list.")
    public Mono<MatchResult> get(Authentication authentication, @PathVariable UUID matchId) {
        return matches.get(Principals.actor(authentication), matchId);
    }

    @PutMapping("/{matchId}")
    @Operation(summary = "Change a match's settings", description = "Creator only, before the match starts. Send the version you last saw; a stale edit is a 409.")
    public Mono<MatchResult> update(Authentication authentication, @PathVariable UUID matchId, @Valid @RequestBody CricketRequests.UpdateMatchRequest request) {
        return matches.updateSettings(Principals.actor(authentication), matchId, request.toSettings(), request.expectedVersion());
    }

    @PostMapping("/{matchId}/toss")
    @Operation(summary = "Record the toss", description = "The creator or a scorer, before the match starts. The winner must be one of the two teams. Can be corrected until the match starts.")
    public Mono<MatchResult> toss(Authentication authentication, @PathVariable UUID matchId, @Valid @RequestBody CricketRequests.TossRequest request) {
        return matches.recordToss(Principals.actor(authentication), matchId, request.winnerTeamId(), request.decision());
    }

    @PutMapping("/{matchId}/playing-xi")
    @Operation(summary = "Set one side's playing XI", description = "In batting order. 2 to the match's players per side, all members of that team, nobody listed twice or on both sides.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Saved"), @ApiResponse(responseCode = "409", description = "Invalid XI, or the match has started")})
    public Mono<MatchResult> playingXi(Authentication authentication, @PathVariable UUID matchId, @Valid @RequestBody CricketRequests.PlayingXiRequest request) {
        return matches.setPlayingXi(Principals.actor(authentication), matchId, request.teamId(), request.playerIds());
    }

    @PostMapping("/{matchId}/scorers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Let a registered user score this match", description = "Creator only. The user is found by email and must have registered (404 USER_NOT_REGISTERED otherwise).")
    public Mono<MatchResult> addScorer(Authentication authentication, @PathVariable UUID matchId, @Valid @RequestBody CricketRequests.ScorerRequest request) {
        return matches.addScorer(Principals.actor(authentication), matchId, request.email());
    }

    @DeleteMapping("/{matchId}/scorers/{userId}")
    @Operation(summary = "Remove a scorer", description = "Creator only.")
    public Mono<MatchResult> removeScorer(Authentication authentication, @PathVariable UUID matchId, @PathVariable UUID userId) {
        return matches.removeScorer(Principals.actor(authentication), matchId, userId);
    }
}
