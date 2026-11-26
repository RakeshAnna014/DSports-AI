package com.dsports.cricket.interfaces;

import com.dsports.cricket.application.result.ClaimResult;
import com.dsports.cricket.application.result.ClaimableResult;
import com.dsports.cricket.application.result.PlayerResult;
import com.dsports.cricket.application.usecase.ManagePlayersUseCase;
import com.dsports.cricket.application.usecase.PlayerClaimsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Players (guests and a customer's own profile) and the claims that link a guest to an account. Every call needs a signed-in user; the use cases decide who may see what. */
@RestController
@RequestMapping(path = "/api/v1/players", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Cricket players")
public class PlayerController {

    private final ManagePlayersUseCase players;
    private final PlayerClaimsUseCase claims;

    public PlayerController(ManagePlayersUseCase players, PlayerClaimsUseCase claims) {
        this.players = players;
        this.claims = claims;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a guest player", description = "A player without an account, created by you. The phone number is optional, unique among your own players, "
        + "and only you can ever see it.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Created"), @ApiResponse(responseCode = "400", description = "Invalid details"),
        @ApiResponse(responseCode = "409", description = "You already have a player with that phone number")})
    public Mono<PlayerResult> createGuest(Authentication authentication, @Valid @RequestBody CricketRequests.PlayerRequest request) {
        return players.createGuest(Principals.actor(authentication), request.toDetails());
    }

    @PostMapping("/me")
    @Operation(summary = "Create my own player profile", description = "Linked to your account from the start. Idempotent: if you already have one it is returned unchanged.")
    public Mono<PlayerResult> createOwn(Authentication authentication, @Valid @RequestBody CricketRequests.PlayerRequest request) {
        return players.createOwn(Principals.actor(authentication), request.toDetails());
    }

    @PutMapping("/me/public-profile")
    @Operation(summary = "Show or hide my public statistics page", description = "Only for the player linked to your account (on by default). Hidden means the career page answers 404 and public scorecards "
        + "show your name without a link. Takes effect at once. 404 if you have no player profile.")
    public Mono<PlayerResult> setPublicProfile(Authentication authentication, @Valid @RequestBody CricketRequests.PublicProfileRequest request) {
        return players.setPublicProfile(Principals.actor(authentication), request.visible());
    }

    @GetMapping
    @Operation(summary = "My players", description = "The players you created and the one linked to your account, by name.")
    public Flux<PlayerResult> mine(Authentication authentication) {
        return players.listMine(Principals.actor(authentication));
    }

    @GetMapping("/{playerId}")
    @Operation(summary = "Get a player", description = "Only for whoever created the player or the account it is linked to; anyone else gets 404.")
    public Mono<PlayerResult> get(Authentication authentication, @PathVariable UUID playerId) {
        return players.get(Principals.actor(authentication), playerId);
    }

    @PutMapping("/{playerId}")
    @Operation(summary = "Edit a player", description = "Send the version you last saw; a stale edit is refused with 409.")
    public Mono<PlayerResult> update(Authentication authentication, @PathVariable UUID playerId, @Valid @RequestBody CricketRequests.UpdatePlayerRequest request) {
        return players.update(Principals.actor(authentication), playerId, request.player().toDetails(), request.expectedVersion());
    }

    @GetMapping("/claimable")
    @Operation(summary = "Guest profiles that might be me", description = "Finds guests (not linked to any account) with this phone number and returns only a shortened name, at most 5. "
        + "It is a hint: you still have to ask for the profile, and its creator still has to agree.")
    public Flux<ClaimableResult> claimable(Authentication authentication, @RequestParam String phone) {
        return players.findClaimable(Principals.actor(authentication), phone);
    }

    @PostMapping("/{playerId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ask to claim a guest profile", description = "Never granted automatically (a phone number is not proof): the person who created the guest, or an administrator, approves it. "
        + "Refused if you already have a linked player (two profiles cannot be merged yet) or the profile already belongs to an account.")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Requested"), @ApiResponse(responseCode = "404", description = "No such player"),
        @ApiResponse(responseCode = "409", description = "Already pending, already linked, or you already have a profile")})
    public Mono<ClaimResult> claim(Authentication authentication, @PathVariable UUID playerId) {
        return claims.request(Principals.actor(authentication), playerId);
    }

    @GetMapping("/claims/incoming")
    @Operation(summary = "Claims waiting for my decision", description = "Pending claims on guests you created, with who is asking (name and email). An administrator sees all pending claims.")
    public Flux<ClaimResult> incoming(Authentication authentication) {
        return claims.incoming(Principals.actor(authentication));
    }

    @GetMapping("/claims/mine")
    @Operation(summary = "My claims", description = "The claims you made and what became of them, newest first.")
    public Flux<ClaimResult> mineClaims(Authentication authentication) {
        return claims.mine(Principals.actor(authentication));
    }

    @PostMapping("/claims/{claimId}/approve")
    @Operation(summary = "Approve a claim", description = "Links the guest to the claimant's account. Other pending claims on the same guest are rejected. Only the guest's creator or an administrator; anyone else gets 404.")
    public Mono<ClaimResult> approve(Authentication authentication, @PathVariable UUID claimId) {
        return claims.approve(Principals.actor(authentication), claimId);
    }

    @PostMapping("/claims/{claimId}/reject")
    @Operation(summary = "Reject a claim")
    public Mono<ClaimResult> reject(Authentication authentication, @PathVariable UUID claimId) {
        return claims.reject(Principals.actor(authentication), claimId);
    }
}
