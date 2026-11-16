package com.dsports.ground.domain.model;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A bookable ground. Opening hours are a wall-clock {@link LocalTime} range in the ground's own {@link ZoneId}, so
 * "open 06:00-22:00" stays true whatever the server's zone is; every slot is converted to absolute instants here.
 * <p>
 * Price per slot: the peak price when the slot starts inside the peak window, otherwise the weekend price on a
 * Saturday or Sunday (when one is set), otherwise the base price. Peak therefore wins over weekend.
 */
public final class Ground {

    private static final int MAX_FACILITIES = 20;
    private static final int MAX_PHOTOS = 10;

    /** Everything an owner can edit. */
    public record Spec(String name, String address, String city, BigDecimal latitude, BigDecimal longitude, PitchType pitchType,
                       List<String> facilities, List<String> photoUrls, LocalTime opensAt, LocalTime closesAt, ZoneId timeZone,
                       int slotMinutes, BigDecimal basePrice, BigDecimal weekendPrice, BigDecimal peakPrice,
                       LocalTime peakFrom, LocalTime peakTo) {
    }

    private final UUID id;
    private final UUID ownerId;
    private Spec spec;
    private final String currency;
    private boolean active;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Ground(UUID id, UUID ownerId, Spec spec, String currency, boolean active, int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.spec = spec;
        this.currency = currency;
        this.active = active;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Ground create(UUID ownerId, Spec spec, Instant now) {
        validate(spec);
        return new Ground(UUID.randomUUID(), ownerId, normalise(spec), "INR", true, 0, now, now);
    }

    public static Ground reconstitute(UUID id, UUID ownerId, Spec spec, String currency, boolean active, int version,
                                      Instant createdAt, Instant updatedAt) {
        return new Ground(id, ownerId, spec, currency, active, version, createdAt, updatedAt);
    }

    public void update(Spec newSpec, Instant now) {
        validate(newSpec);
        this.spec = normalise(newSpec);
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

    // ---- slots -------------------------------------------------------------------------------------------------

    /** The slot grid of one local calendar day: from opening time in steps of the slot length; a trailing part-slot is dropped. */
    public List<Slot> slotsOn(LocalDate date) {
        var slots = new ArrayList<Slot>();
        var step = Duration.ofMinutes(spec.slotMinutes());
        var cursor = date.atTime(spec.opensAt());
        var close = date.atTime(spec.closesAt());
        while (!cursor.plus(step).isAfter(close)) {
            var next = cursor.plus(step);
            slots.add(new Slot(cursor.atZone(spec.timeZone()).toInstant(), next.atZone(spec.timeZone()).toInstant()));
            cursor = next;
        }
        return slots;
    }

    /** True when [start, end) is made of whole grid slots of a single local day, inside opening hours. */
    public boolean isOnGrid(Instant start, Instant end) {
        return slotCount(start, end) > 0;
    }

    /** The number of grid slots in [start, end), or 0 when the range is not exactly a run of consecutive grid slots. */
    public int slotCount(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return 0;
        }
        var slots = slotsOn(start.atZone(spec.timeZone()).toLocalDate());
        int first = -1;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).start().equals(start)) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            return 0;
        }
        for (int i = first; i < slots.size(); i++) {
            if (slots.get(i).end().equals(end)) {
                return i - first + 1;
            }
        }
        return 0;
    }

    public BigDecimal slotPrice(Slot slot) {
        var local = slot.start().atZone(spec.timeZone());
        var time = local.toLocalTime();
        if (spec.peakPrice() != null && !time.isBefore(spec.peakFrom()) && time.isBefore(spec.peakTo())) {
            return spec.peakPrice();
        }
        var day = local.getDayOfWeek();
        if (spec.weekendPrice() != null && (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY)) {
            return spec.weekendPrice();
        }
        return spec.basePrice();
    }

    /** The price of [start, end): the sum of its slots' prices. Fails if the range is not on the slot grid. */
    public BigDecimal priceFor(Instant start, Instant end) {
        if (!isOnGrid(start, end)) {
            throw new GroundDomainException(GroundErrorCode.INVALID_SLOT,
                "The requested time is not on this ground's slot grid or is outside its opening hours");
        }
        var total = BigDecimal.ZERO;
        for (var slot : slotsOn(start.atZone(spec.timeZone()).toLocalDate())) {
            if (!slot.start().isBefore(start) && !slot.end().isAfter(end)) {
                total = total.add(slotPrice(slot));
            }
        }
        return total;
    }

    // ---- validation --------------------------------------------------------------------------------------------

    private static Spec normalise(Spec s) {
        return new Spec(s.name().trim(), s.address().trim(), s.city().trim(), s.latitude(), s.longitude(), s.pitchType(),
            List.copyOf(s.facilities() == null ? List.of() : s.facilities()), List.copyOf(s.photoUrls() == null ? List.of() : s.photoUrls()),
            s.opensAt(), s.closesAt(), s.timeZone(), s.slotMinutes(), s.basePrice(), s.weekendPrice(), s.peakPrice(), s.peakFrom(), s.peakTo());
    }

    private static void validate(Spec s) {
        require(s != null, "Ground details are required");
        requireText(s.name(), 120, "Name");
        requireText(s.address(), 300, "Address");
        requireText(s.city(), 80, "City");
        require(s.pitchType() != null, "A pitch type is required");
        require(s.opensAt() != null && s.closesAt() != null && s.closesAt().isAfter(s.opensAt()), "Closing time must be after opening time");
        require(s.timeZone() != null, "A time zone is required");
        require(s.slotMinutes() >= 15 && s.slotMinutes() <= 240, "Slot length must be between 15 and 240 minutes");
        require(Duration.between(s.opensAt(), s.closesAt()).toMinutes() >= s.slotMinutes(), "Opening hours must fit at least one slot");
        requireMoney(s.basePrice(), true, "Base price");
        requireMoney(s.weekendPrice(), false, "Weekend price");
        requireMoney(s.peakPrice(), false, "Peak price");
        boolean anyPeak = s.peakPrice() != null || s.peakFrom() != null || s.peakTo() != null;
        if (anyPeak) {
            require(s.peakPrice() != null && s.peakFrom() != null && s.peakTo() != null && s.peakTo().isAfter(s.peakFrom()),
                "A peak window needs a price and a start before its end");
        }
        require((s.latitude() == null) == (s.longitude() == null), "Latitude and longitude must be given together");
        if (s.latitude() != null) {
            require(s.latitude().abs().compareTo(BigDecimal.valueOf(90)) <= 0 && s.longitude().abs().compareTo(BigDecimal.valueOf(180)) <= 0,
                "Coordinates are out of range");
        }
        var facilities = s.facilities() == null ? List.<String>of() : s.facilities();
        require(facilities.size() <= MAX_FACILITIES && facilities.stream().allMatch(f -> f != null && !f.isBlank() && f.length() <= 40),
            "At most " + MAX_FACILITIES + " facilities of 1-40 characters");
        var photos = s.photoUrls() == null ? List.<String>of() : s.photoUrls();
        require(photos.size() <= MAX_PHOTOS && photos.stream().allMatch(Ground::isHttpUrl), "At most " + MAX_PHOTOS + " photo links, each a http(s) URL");
    }

    private static boolean isHttpUrl(String url) {
        return url != null && url.length() <= 500 && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private static void requireText(String value, int max, String label) {
        require(value != null && !value.isBlank() && value.trim().length() <= max, label + " is required (up to " + max + " characters)");
    }

    private static void requireMoney(BigDecimal value, boolean required, String label) {
        if (value == null) {
            require(!required, label + " is required");
            return;
        }
        require(value.signum() >= 0 && value.scale() <= 2 && value.precision() - value.scale() <= 10, label + " must be a non-negative amount with at most 2 decimals");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, message);
        }
    }

    // ---- accessors ---------------------------------------------------------------------------------------------

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public Spec getSpec() { return spec; }
    public String getName() { return spec.name(); }
    public String getCity() { return spec.city(); }
    public ZoneId getTimeZone() { return spec.timeZone(); }
    public int getSlotMinutes() { return spec.slotMinutes(); }
    public String getCurrency() { return currency; }
    public boolean isActive() { return active; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
