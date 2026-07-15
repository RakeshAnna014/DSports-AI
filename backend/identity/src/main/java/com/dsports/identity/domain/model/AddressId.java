package com.dsports.identity.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.UUID;

public final class AddressId implements ValueObject {

    private final UUID value;

    private AddressId(UUID value) {
        this.value = Objects.requireNonNull(value, "addressId must not be null");
    }

    public static AddressId generate() {
        return new AddressId(UUID.randomUUID());
    }

    public static AddressId fromString(String uuid) {
        return new AddressId(UUID.fromString(uuid));
    }

    public static AddressId fromUUID(UUID uuid) {
        return new AddressId(uuid);
    }

    public UUID value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AddressId other)) return false;
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
