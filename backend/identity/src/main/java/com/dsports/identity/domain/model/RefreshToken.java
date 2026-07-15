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
    private String deviceName;
    private String userAgent;
    private String ipAddress;
    private Instant lastUsedAt;

    private RefreshToken(RefreshTokenId id, UserId userId, String token, Instant expiresAt,
                         Instant createdAt, boolean revoked,
                         String deviceName, String userAgent, String ipAddress, Instant lastUsedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.token = Objects.requireNonNull(token, "token must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.revoked = revoked;
        this.deviceName = deviceName;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
        this.lastUsedAt = Objects.requireNonNull(lastUsedAt, "lastUsedAt must not be null");
    }

    public static RefreshToken create(UserId userId, String token, Instant expiresAt) {
        return create(userId, token, expiresAt, null, null, null);
    }

    public static RefreshToken create(UserId userId, String token, Instant expiresAt,
                                       String deviceName, String userAgent, String ipAddress) {
        if (expiresAt.isBefore(Instant.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        var now = Instant.now();
        return new RefreshToken(
                RefreshTokenId.generate(),
                userId,
                token,
                expiresAt,
                now,
                false,
                deviceName,
                userAgent,
                ipAddress,
                now
        );
    }

    public static RefreshToken reconstitute(RefreshTokenId id, UserId userId, String token,
                                             Instant expiresAt, Instant createdAt, boolean revoked) {
        return reconstitute(id, userId, token, expiresAt, createdAt, revoked, null, null, null, createdAt);
    }

    public static RefreshToken reconstitute(RefreshTokenId id, UserId userId, String token,
                                             Instant expiresAt, Instant createdAt, boolean revoked,
                                             String deviceName, String userAgent, String ipAddress, Instant lastUsedAt) {
        return new RefreshToken(id, userId, token, expiresAt, createdAt, revoked,
                deviceName, userAgent, ipAddress, lastUsedAt);
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

    public String getDeviceName() {
        return deviceName;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
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
