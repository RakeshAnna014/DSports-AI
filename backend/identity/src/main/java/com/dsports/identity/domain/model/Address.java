package com.dsports.identity.domain.model;

import java.time.Instant;
import java.util.Objects;

public final class Address {

    private final AddressId id;
    private AddressType type;
    private AddressLine line1;
    private AddressLine line2;
    private String city;
    private State state;
    private Country country;
    private PostalCode postalCode;
    private boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;

    private Address(AddressId id, AddressType type, AddressLine line1, AddressLine line2,
                    String city, State state, Country country, PostalCode postalCode,
                    boolean isDefault, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.line1 = Objects.requireNonNull(line1, "line1 must not be null");
        this.line2 = line2;
        this.city = Objects.requireNonNull(city, "city must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.country = Objects.requireNonNull(country, "country must not be null");
        this.postalCode = Objects.requireNonNull(postalCode, "postalCode must not be null");
        this.isDefault = isDefault;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static Address create(AddressType type, AddressLine line1, AddressLine line2,
                                  String city, State state, Country country, PostalCode postalCode) {
        Instant now = Instant.now();
        return new Address(AddressId.generate(), type, line1, line2, city, state, country,
                postalCode, false, now, now);
    }

    public static Address reconstitute(AddressId id, AddressType type, AddressLine line1,
                                        AddressLine line2, String city, State state,
                                        Country country, PostalCode postalCode,
                                        boolean isDefault, Instant createdAt, Instant updatedAt) {
        return new Address(id, type, line1, line2, city, state, country, postalCode,
                isDefault, createdAt, updatedAt);
    }

    public void update(AddressLine line1, AddressLine line2, String city, State state,
                        Country country, PostalCode postalCode, AddressType type) {
        this.line1 = Objects.requireNonNull(line1, "line1 must not be null");
        this.line2 = line2;
        this.city = Objects.requireNonNull(city, "city must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.country = Objects.requireNonNull(country, "country must not be null");
        this.postalCode = Objects.requireNonNull(postalCode, "postalCode must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.updatedAt = Instant.now();
    }

    public void markDefault() {
        this.isDefault = true;
        this.updatedAt = Instant.now();
    }

    public void unmarkDefault() {
        this.isDefault = false;
        this.updatedAt = Instant.now();
    }

    public AddressId getId() {
        return id;
    }

    public AddressType getType() {
        return type;
    }

    public AddressLine getLine1() {
        return line1;
    }

    public AddressLine getLine2() {
        return line2;
    }

    public String getCity() {
        return city;
    }

    public State getState() {
        return state;
    }

    public Country getCountry() {
        return country;
    }

    public PostalCode getPostalCode() {
        return postalCode;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Address address)) return false;
        return Objects.equals(id, address.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Address{id=" + id + ", type=" + type + ", city=" + city + ", country=" + country + "}";
    }
}
