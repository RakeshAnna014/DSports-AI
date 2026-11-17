package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.ClaimRepository;
import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.result.ClaimResult;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayerClaim;
import com.dsports.shared.application.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A registered customer claims a guest profile so the history becomes theirs. There is no phone verification, so a claim is never granted
 * automatically: the person who created the guest (or an administrator) approves it. A customer can be linked to only one player; deciding a claim
 * runs in one transaction, and approving one rejects the other pending claims on the same guest.
 */
public class PlayerClaimsUseCase {
    private static final Logger log = LoggerFactory.getLogger(PlayerClaimsUseCase.class);
    private static final int ADMIN_QUEUE_LIMIT = 100;

    private final PlayerRepository players;
    private final ClaimRepository claims;
    private final CustomerDirectoryPort directory;
    private final TransactionRunner transactions;
    private final Clock clock;

    public PlayerClaimsUseCase(PlayerRepository players, ClaimRepository claims, CustomerDirectoryPort directory, TransactionRunner transactions, Clock clock) {
        this.players = players;
        this.claims = claims;
        this.directory = directory;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Mono<ClaimResult> request(Actor actor, UUID playerId) {
        return transactions.inTransaction(() -> players.findById(playerId)
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.PLAYER_NOT_FOUND, "Player not found: " + playerId)))
            .flatMap(player -> {
                if (player.isLinked()) {
                    return Mono.<ClaimResult>error(new CricketDomainException(CricketErrorCode.PLAYER_ALREADY_LINKED, "That profile already belongs to an account"));
                }
                return players.findByCustomer(actor.id())
                    .flatMap(own -> Mono.<ClaimResult>error(new CricketDomainException(CricketErrorCode.CLAIMANT_ALREADY_LINKED,
                        "Your account already has a player profile, and two profiles cannot be merged yet")))
                    .switchIfEmpty(Mono.defer(() -> claims.existsPending(playerId, actor.id()).flatMap(pending -> pending
                        ? Mono.<ClaimResult>error(new CricketDomainException(CricketErrorCode.CLAIM_ALREADY_PENDING, "You have already asked to claim this profile"))
                        : claims.save(PlayerClaim.request(playerId, actor.id(), clock.instant())).map(saved -> present(saved, player, null)))));
            }));
    }

    /** Pending claims waiting for the caller to decide (an administrator sees every pending claim), with who is asking. */
    public Flux<ClaimResult> incoming(Actor actor) {
        var pending = actor.admin() ? claims.findPending(ADMIN_QUEUE_LIMIT) : claims.findPendingForCreator(actor.id());
        return pending.collectList().flatMapMany(list -> {
            if (list.isEmpty()) {
                return Flux.<ClaimResult>empty();
            }
            var playerIds = list.stream().map(PlayerClaim::getPlayerId).collect(Collectors.toSet());
            var claimantIds = list.stream().map(PlayerClaim::getClaimantId).collect(Collectors.toSet());
            return Mono.zip(players.findAllById(playerIds).collectMap(Player::getId), directory.describe(claimantIds))
                .flatMapMany(t -> Flux.fromIterable(list).map(c -> present(c, t.getT1().get(c.getPlayerId()), t.getT2().get(c.getClaimantId()))));
        });
    }

    /** The caller's own claims and what became of them. */
    public Flux<ClaimResult> mine(Actor actor) {
        return claims.findByClaimant(actor.id()).collectList().flatMapMany(list -> {
            var playerIds = list.stream().map(PlayerClaim::getPlayerId).collect(Collectors.toSet());
            return players.findAllById(playerIds).collectMap(Player::getId)
                .flatMapMany(byId -> Flux.fromIterable(list).map(c -> present(c, byId.get(c.getPlayerId()), null)));
        });
    }

    public Mono<ClaimResult> approve(Actor actor, UUID claimId) {
        return transactions.inTransaction(() -> decidable(actor, claimId).flatMap(t -> {
            var claim = t.claim();
            var player = t.player();
            return players.findByCustomer(claim.getClaimantId())
                .flatMap(own -> Mono.<ClaimResult>error(new CricketDomainException(CricketErrorCode.CLAIMANT_ALREADY_LINKED,
                    "That account already has a player profile, so this claim cannot be approved")))
                .switchIfEmpty(Mono.defer(() -> {
                    var now = clock.instant();
                    claim.approve(actor.id(), now);
                    player.linkTo(claim.getClaimantId(), now);
                    return players.save(player)
                        .then(claims.save(claim))
                        .then(rejectOthers(player.getId(), claim.getId(), actor.id()))
                        .doOnSuccess(v -> log.info("Claim {} approved by {}: player {} is now linked to customer {}", claim.getId(), actor.id(), player.getId(), claim.getClaimantId()))
                        .thenReturn(present(claim, player, null));
                }));
        }));
    }

    public Mono<ClaimResult> reject(Actor actor, UUID claimId) {
        return transactions.inTransaction(() -> decidable(actor, claimId).flatMap(t -> {
            t.claim().reject(actor.id(), clock.instant());
            return claims.save(t.claim()).map(saved -> present(saved, t.player(), null));
        }));
    }

    private record Decidable(PlayerClaim claim, Player player) {
    }

    /** Loads a claim for its decider: the guest's creator or an administrator. Anyone else sees "not found". */
    private Mono<Decidable> decidable(Actor actor, UUID claimId) {
        return claims.findById(claimId)
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.CLAIM_NOT_FOUND, "Claim not found: " + claimId)))
            .flatMap(claim -> players.findById(claim.getPlayerId())
                .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.PLAYER_NOT_FOUND, "Player not found")))
                .flatMap(player -> actor.admin() || player.getCreatedBy().equals(actor.id())
                    ? Mono.just(new Decidable(claim, player))
                    : Mono.<Decidable>error(new CricketDomainException(CricketErrorCode.CLAIM_NOT_FOUND, "Claim not found: " + claimId))));
    }

    private Mono<Void> rejectOthers(UUID playerId, UUID approvedClaim, UUID by) {
        return claims.findPendingByPlayer(playerId).filter(c -> !c.getId().equals(approvedClaim))
            .concatMap(other -> {
                other.reject(by, clock.instant());
                return claims.save(other);
            }).then();
    }

    private static ClaimResult present(PlayerClaim c, Player player, CustomerDirectoryPort.Person claimant) {
        return new ClaimResult(c.getId(), c.getPlayerId(), player == null ? null : player.getName(), c.getStatus().name(),
            claimant == null ? null : claimant.name(), claimant == null ? null : claimant.email(), c.getCreatedAt(), c.getDecidedAt());
    }
}
