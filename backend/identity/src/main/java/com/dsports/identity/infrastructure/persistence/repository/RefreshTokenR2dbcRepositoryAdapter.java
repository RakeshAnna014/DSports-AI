package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.RefreshTokenRepository;
import com.dsports.identity.domain.model.RefreshToken;
import com.dsports.identity.domain.model.RefreshTokenId;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.infrastructure.persistence.entity.RefreshTokenEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

public class RefreshTokenR2dbcRepositoryAdapter implements RefreshTokenRepository {

    private final DatabaseClient databaseClient;

    public RefreshTokenR2dbcRepositoryAdapter(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<RefreshToken> findByToken(String token) {
        return databaseClient.sql("""
                        SELECT * FROM refresh_tokens WHERE token = :token
                        """)
                .bind("token", token)
                .map((row, meta) -> mapEntity(row))
                .one()
                .map(this::toDomain);
    }

    @Override
    public Mono<Void> save(RefreshToken refreshToken) {
        return databaseClient.sql("""
                        INSERT INTO refresh_tokens (id, user_id, token, expires_at, created_at, revoked,
                                                    device_name, user_agent, ip_address, last_used_at)
                        VALUES (:id, :userId, :token, :expiresAt, :createdAt, :revoked,
                                :deviceName, :userAgent, :ipAddress, :lastUsedAt)
                        ON CONFLICT (id) DO UPDATE SET
                            revoked = EXCLUDED.revoked
                        """)
                .bind("id", refreshToken.getId().value())
                .bind("userId", refreshToken.getUserId().value())
                .bind("token", refreshToken.getToken())
                .bind("expiresAt", refreshToken.getExpiresAt())
                .bind("createdAt", refreshToken.getCreatedAt())
                .bind("revoked", refreshToken.isRevoked())
                .bind("deviceName", refreshToken.getDeviceName() != null ? refreshToken.getDeviceName() : "")
                .bind("userAgent", refreshToken.getUserAgent() != null ? refreshToken.getUserAgent() : "")
                .bind("ipAddress", refreshToken.getIpAddress() != null ? refreshToken.getIpAddress() : "")
                .bind("lastUsedAt", refreshToken.getLastUsedAt() != null ? refreshToken.getLastUsedAt() : Instant.now())
                .then();
    }

    @Override
    public Mono<Long> deleteExpired(Instant now) {
        return databaseClient.sql("DELETE FROM refresh_tokens WHERE expires_at < :now")
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    @Override
    public Mono<Void> revokeByUserId(UserId userId) {
        return databaseClient.sql("""
                        UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = :userId
                        """)
                .bind("userId", userId.value())
                .then();
    }

    private RefreshTokenEntity mapEntity(io.r2dbc.spi.Row row) {
        var entity = new RefreshTokenEntity();
        entity.setId(row.get("id", java.util.UUID.class));
        entity.setUserId(row.get("user_id", java.util.UUID.class));
        entity.setToken(row.get("token", String.class));
        entity.setExpiresAt(row.get("expires_at", java.time.Instant.class));
        entity.setCreatedAt(row.get("created_at", java.time.Instant.class));
        entity.setRevoked(row.get("revoked", Boolean.class));
        entity.setDeviceName(row.get("device_name", String.class));
        entity.setUserAgent(row.get("user_agent", String.class));
        entity.setIpAddress(row.get("ip_address", String.class));
        entity.setLastUsedAt(row.get("last_used_at", java.time.Instant.class));
        return entity;
    }

    private RefreshToken toDomain(RefreshTokenEntity entity) {
        return RefreshToken.reconstitute(
                RefreshTokenId.fromUUID(entity.getId()),
                UserId.fromUUID(entity.getUserId()),
                entity.getToken(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.isRevoked(),
                entity.getDeviceName(),
                entity.getUserAgent(),
                entity.getIpAddress(),
                entity.getLastUsedAt()
        );
    }
}
