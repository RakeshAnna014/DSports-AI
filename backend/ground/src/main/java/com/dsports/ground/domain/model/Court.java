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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.dsports.ground.domain.model.Rules.require;
import static com.dsports.ground.domain.model.Rules.requireMoney;
import static com.dsports.ground.domain.model.Rules.requireText;

/**
 * One bookable playing area at a {@link Venue}: its own slot length, prices and the sports it supports. The court is what cannot be double
 * booked (whatever the sport); different courts of one venue are independent.
 * <p>
 * The slot grid comes from the venue's opening hours ({@link Hours}, carried here as a read-only copy so a loaded court can compute its own
 * slots) and the court's slot length. Price per slot: the peak price when the slot starts inside the peak window, otherwise the weekend price on
 * a Saturday or Sunday (when one is set), otherwise the base price. Peak therefore wins over weekend.
 */
public final class Court {

    private static final int MAX_SPORTS = 12;

    /** The venue's opening hours: wall-clock times in the venue's own zone. */
    public record Hours(LocalTime opensAt, LocalTime closesAt, ZoneId timeZone) {
    }

    /** Everything an owner can edit on a court. {@code sportIds} are catalogue sport ids; at least one is required. */
    public record Spec(String name, String surface, List<UUID> sportIds, int slotMinutes, BigDecimal basePrice, BigDecimal weekendPrice,
                       BigDecimal peakPrice, LocalTime peakFrom, LocalTime peakTo) {
    }

    private final UUID id;
    private final UUID venueId;
    private Spec spec;
    private Hours hours;
    private boolean active;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Court(UUID id, UUID venueId, Spec spec, Hours hours, boolean active, int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.venueId = Objects.requireNonNull(venueId);
        this.spec = spec;
        this.hours = hours;
        this.active = active;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Court create(Venue venue, Spec spec, Instant now) {
        validate(spec, venue.hours());
        return new Court(UUID.randomUUID(), venue.getId(), normalise(spec), venue.hours(), true, 0, now, now);
    }

    public static Court reconstitute(UUID id, UUID venueId, Spec spec, Hours hours, boolean active, int version, Instant createdAt, Instant updatedAt) {
        return new Court(id, venueId, spec, hours, active, version, createdAt, updatedAt);
    }

    public void update(Spec newSpec, Instant now) {
        validate(newSpec, hours);
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

    public boolean supports(UUID sportId) {
        return sportId != null && spec.sportIds().contains(sportId);
    }

    // ---- slots -------------------------------------------------------------------------------------------------

    /** The slot grid of one local calendar day: from opening time in steps of the slot length; a trailing part-slot is dropped. */
    public List<Slot> slotsOn(LocalDate date) {
        var slots = new ArrayList<Slot>();
        var step = Duration.ofMinutes(spec.slotMinutes());
        var cursor = date.atTime(hours.opensAt());
        var close = date.atTime(hours.closesAt());
        while (!cursor.plus(step).isAfter(close)) {
            var next = cursor.plus(step);
            slots.add(new Slot(cursor.atZone(hours.timeZone()).toInstant(), next.atZone(hours.timeZone()).toInstant()));
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
        var slots = slotsOn(start.atZone(hours.timeZone()).toLocalDate());
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
        var local = slot.start().atZone(hours.timeZone());
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
                "The requested time is not on this court's slot grid or is outside its opening hours");
        }
        var total = BigDecimal.ZERO;
        for (var slot : slotsOn(start.atZone(hours.timeZone()).toLocalDate())) {
            if (!slot.start().isBefore(start) && !slot.end().isAfter(end)) {
                total = total.add(slotPrice(slot));
            }
        }
        return total;
    }

    // ---- validation --------------------------------------------------------------------------------------------

    private static Spec normalise(Spec s) {
        var sports = new LinkedHashSet<>(s.sportIds());
        var surface = s.surface() == null || s.surface().isBlank() ? null : s.surface().trim();
        return new Spec(s.name().trim(), surface, List.copyOf(sports), s.slotMinutes(), s.basePrice(), s.weekendPrice(), s.peakPrice(), s.peakFrom(), s.peakTo());
    }

    private static void validate(Spec s, Hours hours) {
        require(s != null, "Court details are required");
        requireText(s.name(), 80, "Court name");
        require(s.surface() == null || s.surface().trim().length() <= 40, "Surface is up to 40 characters");
        require(s.sportIds() != null && !s.sportIds().isEmpty() && s.sportIds().stream().noneMatch(Objects::isNull), "A court needs at least one sport");
        require(new LinkedHashSet<>(s.sportIds()).size() <= MAX_SPORTS, "At most " + MAX_SPORTS + " sports per court");
        require(s.slotMinutes() >= 15 && s.slotMinutes() <= 240, "Slot length must be between 15 and 240 minutes");
        require(hours == null || Duration.between(hours.opensAt(), hours.closesAt()).toMinutes() >= s.slotMinutes(),
            "The venue's opening hours must fit at least one slot");
        requireMoney(s.basePrice(), true, "Base price");
        requireMoney(s.weekendPrice(), false, "Weekend price");
        requireMoney(s.peakPrice(), false, "Peak price");
        boolean anyPeak = s.peakPrice() != null || s.peakFrom() != null || s.peakTo() != null;
        if (anyPeak) {
            require(s.peakPrice() != null && s.peakFrom() != null && s.peakTo() != null && s.peakTo().isAfter(s.peakFrom()),
                "A peak window needs a price and a start before its end");
        }
    }

    public UUID getId() { return id; }
    public UUID getVenueId() { return venueId; }
    public Spec getSpec() { return spec; }
    public String getName() { return spec.name(); }
    public List<UUID> getSportIds() { return spec.sportIds(); }
    public int getSlotMinutes() { return spec.slotMinutes(); }
    public Hours getHours() { return hours; }
    public ZoneId getTimeZone() { return hours.timeZone(); }
    public boolean isActive() { return active; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
