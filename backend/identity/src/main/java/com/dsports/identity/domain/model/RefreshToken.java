package com.dsports.identity.domain.model;

import java.time.Instant;
import java.util.Objects;

public final class RefreshToken {

    private final RefreshTokenId id;
    private final UserId userId;
    private final String token;
    private final Instant expiresAt;
    private final Instant createdAt;
    private boolean revoked;

    private RefreshToken(RefreshTokenId id, UserId userId, String token, Instant expiresAt, Instant createdAt, boolean revoked) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.token = Objects.requireNonNull(token, "token must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.revoked = revoked;
    }

    public static RefreshToken create(UserId userId, String token, Instant expiresAt) {
        return new RefreshToken(
                RefreshTokenId.generate(),
                userId,
                token,
                expiresAt,
                Instant.now(),
                false
        );
    }

    public static RefreshToken reconstitute(RefreshTokenId id, UserId userId, String token,
                                             Instant expiresAt, Instant createdAt, boolean revoked) {
        return new RefreshToken(id, userId, token, expiresAt, createdAt, revoked);
    }

    public void revoke() {
        this.revoked = true;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean belongsTo(UserId userId) {
        return this.userId.equals(userId);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }

    public RefreshTokenId getId() {
        return id;
    }

    public UserId getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "RefreshToken{id=" + id + ", userId=" + userId + ", revoked=" + revoked + ", expired=" + isExpired() + "}";
    }
}
