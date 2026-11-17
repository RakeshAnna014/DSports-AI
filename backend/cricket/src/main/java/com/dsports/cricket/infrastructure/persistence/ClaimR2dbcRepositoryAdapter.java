package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.ClaimRepository;
import com.dsports.cricket.domain.model.ClaimStatus;
import com.dsports.cricket.domain.model.PlayerClaim;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Claims are small and written with explicit SQL: an insert for a new claim, an upsert that only ever changes the decision columns. */
public class ClaimR2dbcRepositoryAdapter implements ClaimRepository {

    private static final String COLUMNS = "c.id, c.player_id, c.claimant_id, c.status, c.decided_by, c.created_at, c.decided_at";

    private final DatabaseClient db;

    public ClaimR2dbcRepositoryAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<PlayerClaim> findById(UUID id) {
        return db.sql("SELECT " + COLUMNS + " FROM player_claims c WHERE c.id = :id").bind("id", id).map((row, meta) -> toDomain(row)).one();
    }

    @Override
    public Flux<PlayerClaim> findPendingForCreator(UUID creator) {
        return db.sql("SELECT " + COLUMNS + " FROM player_claims c JOIN players p ON p.id = c.player_id WHERE p.created_by = :creator AND c.status = 'PENDING' ORDER BY c.created_at")
            .bind("creator", creator).map((row, meta) -> toDomain(row)).all();
    }

    @Override
    public Flux<PlayerClaim> findPending(int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM player_claims c WHERE c.status = 'PENDING' ORDER BY c.created_at LIMIT :limit")
            .bind("limit", limit).map((row, meta) -> toDomain(row)).all();
    }

    @Override
    public Flux<PlayerClaim> findPendingByPlayer(UUID playerId) {
        return db.sql("SELECT " + COLUMNS + " FROM player_claims c WHERE c.player_id = :player AND c.status = 'PENDING' ORDER BY c.created_at")
            .bind("player", playerId).map((row, meta) -> toDomain(row)).all();
    }

    @Override
    public Flux<PlayerClaim> findByClaimant(UUID claimant) {
        return db.sql("SELECT " + COLUMNS + " FROM player_claims c WHERE c.claimant_id = :claimant ORDER BY c.created_at DESC")
            .bind("claimant", claimant).map((row, meta) -> toDomain(row)).all();
    }

    @Override
    public Mono<Boolean> existsPending(UUID playerId, UUID claimant) {
        return db.sql("SELECT 1 AS one FROM player_claims WHERE player_id = :player AND claimant_id = :claimant AND status = 'PENDING'")
            .bind("player", playerId).bind("claimant", claimant).map((row, meta) -> true).one().defaultIfEmpty(false);
    }

    @Override
    public Mono<PlayerClaim> save(PlayerClaim claim) {
        var spec = db.sql("""
                INSERT INTO player_claims (id, player_id, claimant_id, status, decided_by, created_at, decided_at)
                VALUES (:id, :player, :claimant, :status, :decidedBy, :createdAt, :decidedAt)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, decided_by = EXCLUDED.decided_by, decided_at = EXCLUDED.decided_at
                """)
            .bind("id", claim.getId()).bind("player", claim.getPlayerId()).bind("claimant", claim.getClaimantId()).bind("status", claim.getStatus().name())
            .bind("createdAt", claim.getCreatedAt());
        spec = claim.getDecidedBy() == null ? spec.bindNull("decidedBy", UUID.class) : spec.bind("decidedBy", claim.getDecidedBy());
        spec = claim.getDecidedAt() == null ? spec.bindNull("decidedAt", Instant.class) : spec.bind("decidedAt", claim.getDecidedAt());
        return spec.fetch().rowsUpdated().thenReturn(claim);
    }

    private static PlayerClaim toDomain(Row row) {
        return PlayerClaim.reconstitute(row.get("id", UUID.class), row.get("player_id", UUID.class), row.get("claimant_id", UUID.class),
            ClaimStatus.valueOf(row.get("status", String.class)), row.get("decided_by", UUID.class), row.get("created_at", Instant.class), row.get("decided_at", Instant.class));
    }
}
