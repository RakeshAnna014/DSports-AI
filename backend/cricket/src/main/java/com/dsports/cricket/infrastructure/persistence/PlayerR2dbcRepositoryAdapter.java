package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BattingHand;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayingRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public class PlayerR2dbcRepositoryAdapter implements PlayerRepository {

    private final SpringR2dbcPlayerRepository repository;

    public PlayerR2dbcRepositoryAdapter(SpringR2dbcPlayerRepository repository) {
        this.repository = repository;
    }

    @Override
    public Mono<Player> findById(UUID id) {
        return repository.findById(id).map(PlayerR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Player> findAllById(Collection<UUID> ids) {
        return ids.isEmpty() ? Flux.empty() : repository.findAllById(ids).map(PlayerR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Player> findUsableBy(UUID user) {
        return repository.findByCreatedByOrCustomerIdOrderByNameAsc(user, user).map(PlayerR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Player> findByCustomer(UUID customerId) {
        return repository.findByCustomerId(customerId).map(PlayerR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Player> findUnlinkedByPhone(String normalisedPhone, int limit) {
        return repository.findByPhoneAndCustomerIdIsNullOrderByCreatedAtAsc(normalisedPhone).take(limit).map(PlayerR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Player> save(Player player) {
        return repository.save(toEntity(player))
            .doOnNext(saved -> player.setVersion(saved.getVersion()))
            .thenReturn(player)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new CricketDomainException(CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                "Player " + player.getId() + " was modified concurrently. Reload and retry."))
            .onErrorMap(DataIntegrityViolationException.class, PlayerR2dbcRepositoryAdapter::mapViolation);
    }

    private static CricketDomainException mapViolation(DataIntegrityViolationException e) {
        var message = String.valueOf(e.getMessage());
        if (message.contains("uq_players_creator_phone")) {
            return new CricketDomainException(CricketErrorCode.DUPLICATE_PLAYER_PHONE, "You already have a player with that phone number");
        }
        if (message.contains("uq_players_customer")) {
            return new CricketDomainException(CricketErrorCode.CLAIMANT_ALREADY_LINKED, "That account already has a player profile");
        }
        return new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "The player could not be saved");
    }

    static Player toDomain(PlayerEntity e) {
        var details = new Player.Details(e.getName(), e.getPhone(), e.getPlayingRole() == null ? null : PlayingRole.valueOf(e.getPlayingRole()),
            e.getBattingHand() == null ? null : BattingHand.valueOf(e.getBattingHand()), e.getBowlingStyle(), e.getPhotoUrl());
        return Player.reconstitute(e.getId(), details, e.getCustomerId(), e.isPublicProfile(), e.getCreatedBy(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    static PlayerEntity toEntity(Player p) {
        var d = p.getDetails();
        var e = new PlayerEntity();
        e.setId(p.getId());
        e.setName(d.name());
        e.setPhone(d.phone());
        e.setPlayingRole(d.role() == null ? null : d.role().name());
        e.setBattingHand(d.battingHand() == null ? null : d.battingHand().name());
        e.setBowlingStyle(d.bowlingStyle());
        e.setPhotoUrl(d.photoUrl());
        e.setCustomerId(p.getCustomerId());
        e.setPublicProfile(p.isPublicProfile());
        e.setCreatedBy(p.getCreatedBy());
        e.setVersion(p.getVersion());
        e.setCreatedAt(p.getCreatedAt());
        e.setUpdatedAt(p.getUpdatedAt());
        return e;
    }
}
