package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.TeamResult;
import com.dsports.cricket.application.usecase.ManageTeamsUseCase;
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

/** Teams. A team is edited by its creator or its captain's own account and seen by them and by members linked to an account; anyone else gets 404. */
@RestController
@RequestMapping(path = "/api/v1/teams", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket teams")
public class TeamController {

    private final ManageTeamsUseCase teams;

    public TeamController(ManageTeamsUseCase teams) {
        this.teams = teams;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a team", description = "The team name is unique among your own teams (any case).")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "409", description = "You already have a team with that name")})
    public Mono<TeamResult> create(Authentication authentication, @Valid @RequestBody CricketRequests.TeamRequest request) {
        return teams.create(Principals.actor(authentication), request.name(), request.city());
    }

    @GetMapping
    @Operation(summary = "My teams", description = "Teams you created or have a linked player in.")
    public Flux<TeamResult> mine(Authentication authentication) {
        return teams.listMine(Principals.actor(authentication));
    }

    @GetMapping("/{teamId}")
    @Operation(summary = "Get a team with its members", description = "A member's phone number is shown only to whoever created that player.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "The team"), @ApiResponse(responseCode = "404", description = "No such team, or not yours to see")})
    public Mono<TeamResult> get(Authentication authentication, @PathVariable UUID teamId) {
        return teams.get(Principals.actor(authentication), teamId);
    }

    @PutMapping("/{teamId}")
    @Operation(summary = "Rename a team or change its city", description = "Send the version you last saw; a stale edit is refused with 409.")
    public Mono<TeamResult> update(Authentication authentication, @PathVariable UUID teamId, @Valid @RequestBody CricketRequests.UpdateTeamRequest request) {
        return teams.update(Principals.actor(authentication), teamId, request.name(), request.city(), request.expectedVersion());
    }

    @PostMapping("/{teamId}/archive")
    @Operation(summary = "Archive a team", description = "An archived team keeps its history but cannot change any more.")
    public Mono<TeamResult> archive(Authentication authentication, @PathVariable UUID teamId) {
        return teams.archive(Principals.actor(authentication), teamId);
    }

    @PostMapping("/{teamId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a player to a team", description = "You can add players you created and your own profile. At most 30 per team.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Added"), @ApiResponse(responseCode = "404", description = "No such team or player"),
        @ApiResponse(responseCode = "409", description = "Already a member, or the team is full or archived")})
    public Mono<TeamResult> addMember(Authentication authentication, @PathVariable UUID teamId, @Valid @RequestBody CricketRequests.PlayerIdRequest request) {
        return teams.addMember(Principals.actor(authentication), teamId, request.playerId());
    }

    @DeleteMapping("/{teamId}/members/{playerId}")
    @Operation(summary = "Remove a player from a team", description = "The captain cannot be removed: name another captain first.")
    public Mono<TeamResult> removeMember(Authentication authentication, @PathVariable UUID teamId, @PathVariable UUID playerId) {
        return teams.removeMember(Principals.actor(authentication), teamId, playerId);
    }

    @PutMapping("/{teamId}/captain")
    @Operation(summary = "Choose the captain", description = "The captain must already be a member; a team has at most one captain.")
    public Mono<TeamResult> setCaptain(Authentication authentication, @PathVariable UUID teamId, @Valid @RequestBody CricketRequests.PlayerIdRequest request) {
        return teams.setCaptain(Principals.actor(authentication), teamId, request.playerId());
    }
}
