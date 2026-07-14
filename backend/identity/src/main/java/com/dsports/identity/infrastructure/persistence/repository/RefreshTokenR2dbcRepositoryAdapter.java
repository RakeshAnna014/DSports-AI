package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.RefreshTokenId;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.infrastructure.persistence.entity.RefreshTokenEntity;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Optional;

public class RefreshTokenR2dbcRepositoryAdapter implements RefreshTokenRepository {

    private final DatabaseClient databaseClient;

    public RefreshTokenR2dbcRepositoryAdapter(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Optional<RefreshToken> findByToken(String token) {
        return databaseClient.sql("""
                        SELECT * FROM refresh_tokens WHERE token = :token
                        """)
                .bind("token", token)
                .map((row, meta) -> mapEntity(row))
                .one()
                .blockOptional()
                .map(this::toDomain);
    }

    @Override
    public void save(RefreshToken refreshToken) {
        databaseClient.sql("""
                        INSERT INTO refresh_tokens (id, user_id, token, expires_at, created_at, revoked)
                        VALUES (:id, :userId, :token, :expiresAt, :createdAt, :revoked)
                        ON CONFLICT (id) DO UPDATE SET
                            revoked = EXCLUDED.revoked
                        """)
                .bind("id", refreshToken.getId().value())
                .bind("userId", refreshToken.getUserId().value())
                .bind("token", refreshToken.getToken())
                .bind("expiresAt", refreshToken.getExpiresAt())
                .bind("createdAt", refreshToken.getCreatedAt())
                .bind("revoked", refreshToken.isRevoked())
                .then()
                .block();
    }

    @Override
    public void revokeByUserId(UserId userId) {
        databaseClient.sql("""
                        UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = :userId
                        """)
                .bind("userId", userId.value())
                .then()
                .block();
    }

    private RefreshTokenEntity mapEntity(io.r2dbc.spi.Row row) {
        var entity = new RefreshTokenEntity();
        entity.setId(row.get("id", java.util.UUID.class));
        entity.setUserId(row.get("user_id", java.util.UUID.class));
        entity.setToken(row.get("token", String.class));
        entity.setExpiresAt(row.get("expires_at", java.time.Instant.class));
        entity.setCreatedAt(row.get("created_at", java.time.Instant.class));
        entity.setRevoked(row.get("revoked", Boolean.class));
        return entity;
    }

    private RefreshToken toDomain(RefreshTokenEntity entity) {
        return RefreshToken.reconstitute(
                RefreshTokenId.fromUUID(entity.getId()),
                UserId.fromUUID(entity.getUserId()),
                entity.getToken(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.isRevoked()
        );
    }
}
