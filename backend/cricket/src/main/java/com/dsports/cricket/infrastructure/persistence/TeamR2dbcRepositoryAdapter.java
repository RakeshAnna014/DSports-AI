package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Team;
import com.dsports.cricket.domain.model.TeamStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * The team row is saved with optimistic locking (every change, including a member change, bumps its version), then its members are replaced. The use
 * case runs this in one transaction; a stale writer fails on the team row before any member row is touched.
 */
public class TeamR2dbcRepositoryAdapter implements TeamRepository {

    private final SpringR2dbcTeamRepository repository;
    private final DatabaseClient db;

    public TeamR2dbcRepositoryAdapter(SpringR2dbcTeamRepository repository, DatabaseClient db) {
        this.repository = repository;
        this.db = db;
    }

    @Override
    public Mono<Team> findById(UUID id) {
        return repository.findById(id).flatMap(this::withMembers);
    }

    @Override
    public Flux<Team> findMine(UUID user) {
        return db.sql("""
                SELECT t.id FROM teams t
                 WHERE t.created_by = :user
                    OR EXISTS (SELECT 1 FROM team_members m JOIN players p ON p.id = m.player_id WHERE m.team_id = t.id AND p.customer_id = :user)
                 ORDER BY lower(t.name)
                """)
            .bind("user", user).map((row, meta) -> row.get("id", UUID.class)).all()
            .concatMap(id -> repository.findById(id).flatMap(this::withMembers));
    }

    @Override
    public Mono<Team> save(Team team) {
        return repository.save(toEntity(team))
            .doOnNext(saved -> team.setVersion(saved.getVersion()))
            .then(Mono.defer(() -> replaceMembers(team)))
            .thenReturn(team)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new CricketDomainException(CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                "Team " + team.getId() + " was modified concurrently. Reload and retry."))
            .onErrorMap(DataIntegrityViolationException.class, e -> String.valueOf(e.getMessage()).contains("uq_teams_creator_name")
                ? new CricketDomainException(CricketErrorCode.DUPLICATE_TEAM_NAME, "You already have a team with that name")
                : new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, "The team could not be saved"));
    }

    private Mono<Void> replaceMembers(Team team) {
        return db.sql("DELETE FROM team_members WHERE team_id = :team").bind("team", team.getId()).fetch().rowsUpdated()
            .thenMany(Flux.fromIterable(team.getMembers()).concatMap(m -> db.sql("INSERT INTO team_members (team_id, player_id, captain) VALUES (:team, :player, :captain)")
                .bind("team", team.getId()).bind("player", m.playerId()).bind("captain", m.captain()).fetch().rowsUpdated()))
            .then();
    }

    private Mono<Team> withMembers(TeamEntity e) {
        return db.sql("SELECT player_id, captain FROM team_members WHERE team_id = :team ORDER BY added_at, player_id")
            .bind("team", e.getId()).map((row, meta) -> new Team.Member(row.get("player_id", UUID.class), Boolean.TRUE.equals(row.get("captain", Boolean.class)))).all()
            .collectList().map((List<Team.Member> members) -> Team.reconstitute(e.getId(), e.getName(), e.getCity(), TeamStatus.valueOf(e.getStatus()), e.getCreatedBy(),
                members, e.getVersion(), e.getCreatedAt(), e.getUpdatedAt()));
    }

    static TeamEntity toEntity(Team t) {
        var e = new TeamEntity();
        e.setId(t.getId());
        e.setName(t.getName());
        e.setCity(t.getCity());
        e.setStatus(t.getStatus().name());
        e.setCreatedBy(t.getCreatedBy());
        e.setVersion(t.getVersion());
        e.setCreatedAt(t.getCreatedAt());
        e.setUpdatedAt(t.getUpdatedAt());
        return e;
    }
}
