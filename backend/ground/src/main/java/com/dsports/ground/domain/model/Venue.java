package com.dsports.ground.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.dsports.ground.domain.model.Rules.require;
import static com.dsports.ground.domain.model.Rules.requireText;

/**
 * A venue: the place (address, owner, opening hours, photos) that has one or more {@link Court courts}. Opening hours are a wall-clock
 * {@link LocalTime} range in the venue's own {@link ZoneId}, so "open 06:00-22:00" stays true whatever the server's zone is.
 */
public final class Venue {

    private static final int MAX_FACILITIES = 20;
    private static final int MAX_PHOTOS = 10;

    /** Everything an owner (or an administrator) can edit about the place. */
    public record Spec(String name, String address, String city, BigDecimal latitude, BigDecimal longitude, List<String> facilities,
                       List<String> photoUrls, LocalTime opensAt, LocalTime closesAt, ZoneId timeZone) {
    }

    private final UUID id;
    private UUID ownerId;
    private Spec spec;
    private final String currency;
    private boolean active;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Venue(UUID id, UUID ownerId, Spec spec, String currency, boolean active, int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.spec = spec;
        this.currency = currency;
        this.active = active;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Venue create(UUID ownerId, Spec spec, Instant now) {
        validate(spec);
        return new Venue(UUID.randomUUID(), ownerId, normalise(spec), "INR", true, 0, now, now);
    }

    public static Venue reconstitute(UUID id, UUID ownerId, Spec spec, String currency, boolean active, int version, Instant createdAt, Instant updatedAt) {
        return new Venue(id, ownerId, spec, currency, active, version, createdAt, updatedAt);
    }

    public void update(Spec newSpec, Instant now) {
        validate(newSpec);
        this.spec = normalise(newSpec);
        this.updatedAt = now;
    }

    /** Hands the venue to another owner. Existing bookings are untouched; who may be the new owner is checked by the caller. */
    public void reassignTo(UUID newOwnerId, Instant now) {
        this.ownerId = Objects.requireNonNull(newOwnerId);
        this.updatedAt = now;
    }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }

    public void activate(Instant now) {
        this.active = true;
        this.updatedAt = now;
    }

    /** The opening hours as a value that courts carry, so a court can compute its own slot grid. */
    public Court.Hours hours() {
        return new Court.Hours(spec.opensAt(), spec.closesAt(), spec.timeZone());
    }

    private static Spec normalise(Spec s) {
        return new Spec(s.name().trim(), s.address().trim(), s.city().trim(), s.latitude(), s.longitude(),
            List.copyOf(s.facilities() == null ? List.of() : s.facilities()), List.copyOf(s.photoUrls() == null ? List.of() : s.photoUrls()),
            s.opensAt(), s.closesAt(), s.timeZone());
    }

    private static void validate(Spec s) {
        require(s != null, "Venue details are required");
        requireText(s.name(), 120, "Name");
        requireText(s.address(), 300, "Address");
        requireText(s.city(), 80, "City");
        require(s.opensAt() != null && s.closesAt() != null && s.closesAt().isAfter(s.opensAt()), "Closing time must be after opening time");
        require(s.timeZone() != null, "A time zone is required");
        require((s.latitude() == null) == (s.longitude() == null), "Latitude and longitude must be given together");
        if (s.latitude() != null) {
            require(s.latitude().abs().compareTo(BigDecimal.valueOf(90)) <= 0 && s.longitude().abs().compareTo(BigDecimal.valueOf(180)) <= 0,
                "Coordinates are out of range");
        }
        var facilities = s.facilities() == null ? List.<String>of() : s.facilities();
        require(facilities.size() <= MAX_FACILITIES && facilities.stream().allMatch(f -> f != null && !f.isBlank() && f.length() <= 40),
            "At most " + MAX_FACILITIES + " facilities of 1-40 characters");
        var photos = s.photoUrls() == null ? List.<String>of() : s.photoUrls();
        require(photos.size() <= MAX_PHOTOS && photos.stream().allMatch(Venue::isHttpUrl), "At most " + MAX_PHOTOS + " photo links, each a http(s) URL");
    }

    private static boolean isHttpUrl(String url) {
        return url != null && url.length() <= 500 && (url.startsWith("https://") || url.startsWith("http://"));
    }

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public Spec getSpec() { return spec; }
    public String getName() { return spec.name(); }
    public String getCity() { return spec.city(); }
    public ZoneId getTimeZone() { return spec.timeZone(); }
    public String getCurrency() { return currency; }
    public boolean isActive() { return active; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
