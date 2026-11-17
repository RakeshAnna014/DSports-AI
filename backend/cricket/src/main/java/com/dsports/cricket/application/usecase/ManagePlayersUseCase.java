package com.dsports.cricket.application.usecase;

import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.result.ClaimableResult;
import com.dsports.cricket.application.result.PlayerResult;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.PhoneNumber;
import com.dsports.cricket.domain.model.Player;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Players: guests created by someone who vouches for them, and a registered customer's own profile. A player is visible to whoever created it and to
 * the customer it is linked to; anyone else gets "not found", so ids and names cannot be probed.
 */
public class ManagePlayersUseCase {

    private static final int MAX_CLAIMABLE = 5;

    private final PlayerRepository players;
    private final Clock clock;

    public ManagePlayersUseCase(PlayerRepository players, Clock clock) {
        this.players = players;
        this.clock = clock;
    }

    public Mono<PlayerResult> createGuest(Actor actor, Player.Details details) {
        return Mono.fromSupplier(() -> Player.guest(actor.id(), details, clock.instant()))
            .flatMap(players::save)
            .map(p -> PlayerResult.from(p, actor.id()));
    }

    /** The caller's own profile, linked to their account. Idempotent: asking again returns the profile that exists. */
    public Mono<PlayerResult> createOwn(Actor actor, Player.Details details) {
        return players.findByCustomer(actor.id())
            .switchIfEmpty(Mono.defer(() -> Mono.fromSupplier(() -> Player.forCustomer(actor.id(), details, clock.instant())).flatMap(players::save)))
            .map(p -> PlayerResult.from(p, actor.id()));
    }

    public Flux<PlayerResult> listMine(Actor actor) {
        return players.findUsableBy(actor.id()).map(p -> PlayerResult.from(p, actor.id()));
    }

    public Mono<PlayerResult> get(Actor actor, UUID playerId) {
        return usable(actor, playerId).map(p -> PlayerResult.from(p, actor.id()));
    }

    /** Edits are version-checked: a stale edit is a conflict, not a silent overwrite. */
    public Mono<PlayerResult> update(Actor actor, UUID playerId, Player.Details details, int expectedVersion) {
        return usable(actor, playerId).flatMap(player -> {
            if (player.getVersion() != expectedVersion) {
                return Mono.<Player>error(new CricketDomainException(CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT, "This player was changed since you opened it. Reload and try again."));
            }
            player.update(details, clock.instant());
            return players.save(player);
        }).map(p -> PlayerResult.from(p, actor.id()));
    }

    /**
     * Guest profiles that might be the caller, found by phone number. Only a shortened name comes back (a phone number must not be a way to look up
     * names), at most {@value #MAX_CLAIMABLE}. The caller still has to ask, and the guest's creator still has to agree.
     */
    public Flux<ClaimableResult> findClaimable(Actor actor, String phone) {
        return Flux.defer(() -> {
            var normalised = PhoneNumber.normalise(phone);
            return normalised == null
                ? Flux.<ClaimableResult>error(new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "A phone number is required"))
                : players.findUnlinkedByPhone(normalised, MAX_CLAIMABLE).map(p -> new ClaimableResult(p.getId(), PhoneNumber.maskedName(p.getName())));
        });
    }

    private Mono<Player> usable(Actor actor, UUID playerId) {
        return players.findById(playerId)
            .filter(p -> p.usableBy(actor.id()))
            .switchIfEmpty(Mono.error(new CricketDomainException(CricketErrorCode.PLAYER_NOT_FOUND, "Player not found: " + playerId)));
    }
}
