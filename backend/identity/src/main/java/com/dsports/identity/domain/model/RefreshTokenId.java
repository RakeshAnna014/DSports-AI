package com.dsports.identity.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.UUID;

public final class RefreshTokenId implements ValueObject {

    private final UUID value;

    private RefreshTokenId(UUID value) {
        this.value = Objects.requireNonNull(value, "refreshTokenId must not be null");
    }

    public static RefreshTokenId generate() {
        return new RefreshTokenId(UUID.randomUUID());
    }

    public static RefreshTokenId fromString(String uuid) {
        return new RefreshTokenId(UUID.fromString(uuid));
    }

    public static RefreshTokenId fromUUID(UUID uuid) {
        return new RefreshTokenId(uuid);
    }

    public UUID value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshTokenId other)) return false;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
