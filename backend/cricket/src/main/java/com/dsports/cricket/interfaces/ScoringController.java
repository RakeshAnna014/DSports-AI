package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.EngineState;
import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.application.usecase.ScoringUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Ball-by-ball scoring for a match's creator and scorers. Every change carries a client event id (a retry is harmless) and the seq the scorer last saw
 * (a stale one is a conflict). A rule the engine refuses is a 422 with the rule's code; nothing is written then.
 */
@RestController
@RequestMapping(path = "/api/v1/matches/{matchId}/scoring", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket scoring")
public class ScoringController {

    private final ScoringUseCase scoring;

    public ScoringController(ScoringUseCase scoring) {
        this.scoring = scoring;
    }

    @GetMapping
    @Operation(summary = "The match as the scorer needs it", description = "Score, batters, bowlers, what is needed next, what the next ball may be, eligible players, what an undo would do and the correction window.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Current view"), @ApiResponse(responseCode = "403", description = "You have a team in the match but cannot score it"),
        @ApiResponse(responseCode = "404", description = "No such match, or it is not yours to see")})
    public Mono<ScoringView> state(Authentication authentication, @PathVariable UUID matchId) {
        return scoring.state(Principals.actor(authentication), matchId);
    }

    @GetMapping("/engine-state")
    @Operation(summary = "Everything a phone needs to score offline", description = "The match's setup and the whole log (commands, undos, unlocks, each with its seq), the version of the scoring rules and the fingerprint "
        + "of the state that log gives. The phone folds the log with its own copy of the rules and compares the fingerprint before it predicts anything offline. Ids only; another scorer's "
        + "events carry no client event id. Same access as the scoring view.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The engine state"), @ApiResponse(responseCode = "403", description = "You have a team in the match but cannot score it"),
        @ApiResponse(responseCode = "404", description = "No such match, or it is not yours to see")})
    public Mono<EngineState> engineState(Authentication authentication, @PathVariable UUID matchId) {
        return scoring.engineState(Principals.actor(authentication), matchId);
    }

    @PostMapping(path = "/commands", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Score one command", description = "Start an innings, a ball, a new batter or bowler, a retirement, a bowler change, a revised target, end the innings or the match. "
        + "Send the seq you last saw and a client event id. A repeat of the same id with the same content answers 200 with replayed=true, even if another scorer has scored since. "
        + "tappedAt (optional) is when an offline ball was tapped: shown on the timeline only, never used by any rule, never later than the time the server received it.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Accepted (or a retry of an accepted command)"),
        @ApiResponse(responseCode = "409", description = "SCORE_CONFLICT (someone scored since), CLIENT_EVENT_REUSED, MATCH_LOCKED or MATCH_NOT_READY"),
        @ApiResponse(responseCode = "422", description = "SCORING_REJECTED: a rule of cricket or of this match forbids it")})
    public Mono<ScoringView.Outcome> command(Authentication authentication, @PathVariable UUID matchId, @Valid @RequestBody ScoringRequests.CommandRequest request) {
        return scoring.submit(Principals.actor(authentication), matchId, request.clientEventId(), request.expectedSeq(), request.command().toCommand(), request.tappedAt());
    }

    @PostMapping(path = "/undo", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Undo", description = "Voids the last ball and what was chosen after it (BALL, the default) or just the latest command (COMMAND). Nothing is deleted. "
        + "Allowed for 24 hours after the match first finished.")
    public Mono<ScoringView.Outcome> undo(Authentication authentication, @PathVariable UUID matchId, @Valid @RequestBody ScoringRequests.UndoRequest request) {
        return scoring.undo(Principals.actor(authentication), matchId, request.clientEventId(), request.expectedSeq(), request.modeOrDefault(), request.tappedAt());
    }
}
