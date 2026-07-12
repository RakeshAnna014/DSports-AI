package com.dsports.identity.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.UUID;

public final class UserId implements ValueObject {

    private final UUID value;

    private UserId(UUID value) {
        this.value = Objects.requireNonNull(value, "userId must not be null");
    }

    public static UserId generate() {
        return new UserId(UUID.randomUUID());
    }

    public static UserId fromString(String uuid) {
        return new UserId(UUID.fromString(uuid));
    }

    public static UserId fromUUID(UUID uuid) {
        return new UserId(uuid);
    }

    public UUID value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserId other)) return false;
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
