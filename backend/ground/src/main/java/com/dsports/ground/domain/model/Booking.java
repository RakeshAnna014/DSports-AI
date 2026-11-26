package com.dsports.ground.domain.model;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.policy.BookingPolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's booking of one court of a venue, for one chosen sport and a period. It is created HELD (the slot is reserved while payment runs), becomes
 * CONFIRMED when the payment is captured, EXPIRED when the hold lapses unpaid, and CANCELLED by the customer or the owner.
 * The price is a snapshot taken at hold time: later price changes never change an existing booking.
 */
public final class Booking {

    private final UUID id;
    private final String bookingNumber;
    private final UUID venueId;
    private final UUID courtId;
    private final UUID sportId;
    private final UUID userId;
    private final Instant startAt;
    private final Instant endAt;
    private final BigDecimal price;
    private final String currency;
    private BookingStatus status;
    private UUID paymentId;
    private final String idempotencyKey;
    private final Instant heldUntil;
    private Instant cancelledAt;
    private CancelledBy cancelledBy;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Booking(UUID id, String bookingNumber, UUID venueId, UUID courtId, UUID sportId, UUID userId, Instant startAt, Instant endAt, BigDecimal price,
                    String currency, BookingStatus status, UUID paymentId, String idempotencyKey, Instant heldUntil,
                    Instant cancelledAt, CancelledBy cancelledBy, int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.bookingNumber = Objects.requireNonNull(bookingNumber);
        this.venueId = Objects.requireNonNull(venueId);
        this.courtId = Objects.requireNonNull(courtId);
        this.sportId = Objects.requireNonNull(sportId);
        this.userId = Objects.requireNonNull(userId);
        this.startAt = Objects.requireNonNull(startAt);
        this.endAt = Objects.requireNonNull(endAt);
        this.price = Objects.requireNonNull(price);
        this.currency = Objects.requireNonNull(currency);
        this.status = Objects.requireNonNull(status);
        this.paymentId = paymentId;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.heldUntil = Objects.requireNonNull(heldUntil);
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** Validates the request against the venue, the court, the chosen sport and the booking rules, snapshots the price and holds the slot. */
    public static Booking hold(Venue venue, Court court, UUID sportId, UUID userId, Instant start, Instant end, String bookingNumber,
                               String idempotencyKey, Instant now, Duration holdDuration, BookingPolicy policy) {
        if (!venue.isActive()) {
            throw new GroundDomainException(GroundErrorCode.VENUE_NOT_FOUND, "Venue not found: " + venue.getId());
        }
        if (!court.isActive() || !court.getVenueId().equals(venue.getId())) {
            throw new GroundDomainException(GroundErrorCode.COURT_NOT_FOUND, "Court not found: " + court.getId());
        }
        if (!court.supports(sportId)) {
            throw new GroundDomainException(GroundErrorCode.SPORT_NOT_SUPPORTED, "This court does not offer that sport");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "An Idempotency-Key of 1-100 characters is required");
        }
        int slots = court.slotCount(start, end);
        policy.check(start, now, slots);
        var price = court.priceFor(start, end);
        return new Booking(UUID.randomUUID(), bookingNumber, venue.getId(), court.getId(), sportId, userId, start, end, price, venue.getCurrency(),
            BookingStatus.HELD, null, idempotencyKey, now.plus(holdDuration), null, null, 0, now, now);
    }

    public static Booking reconstitute(UUID id, String bookingNumber, UUID venueId, UUID courtId, UUID sportId, UUID userId, Instant startAt, Instant endAt,
                                       BigDecimal price, String currency, BookingStatus status, UUID paymentId, String idempotencyKey,
                                       Instant heldUntil, Instant cancelledAt, CancelledBy cancelledBy, int version,
                                       Instant createdAt, Instant updatedAt) {
        return new Booking(id, bookingNumber, venueId, courtId, sportId, userId, startAt, endAt, price, currency, status, paymentId, idempotencyKey,
            heldUntil, cancelledAt, cancelledBy, version, createdAt, updatedAt);
    }

    /**
     * Payment captured. Only a booking that is still HELD can be confirmed: if the hold was expired or the booking cancelled in
     * the meantime this fails, and the caller must void or refund the payment. Time is deliberately NOT checked here: a payment
     * already being captured keeps its hold even when the hold's deadline passes mid-capture (the expiry job skips it).
     */
    public void confirm(UUID paymentId, Instant now) {
        transitionTo(BookingStatus.CONFIRMED, now);
        this.paymentId = Objects.requireNonNull(paymentId);
    }

    public void expire(Instant now) {
        transitionTo(BookingStatus.EXPIRED, now);
    }

    /** Cancelling is allowed up to the start of the booking. */
    public void cancel(CancelledBy by, Instant now) {
        if (!now.isBefore(startAt)) {
            throw new GroundDomainException(GroundErrorCode.INVALID_STATUS_TRANSITION, "A booking that has already started cannot be cancelled");
        }
        transitionTo(BookingStatus.CANCELLED, now);
        this.cancelledAt = now;
        this.cancelledBy = Objects.requireNonNull(by);
    }

    public void complete(Instant now) {
        requireEnded(now);
        transitionTo(BookingStatus.COMPLETED, now);
    }

    public void markNoShow(Instant now) {
        requireEnded(now);
        transitionTo(BookingStatus.NO_SHOW, now);
    }

    /** True when a HELD booking's payment window has passed. */
    public boolean isHoldLapsed(Instant now) {
        return status == BookingStatus.HELD && !now.isBefore(heldUntil);
    }

    /** True while the slot can still be paid for: HELD and not yet past its deadline. */
    public boolean isPayable(Instant now) {
        return status == BookingStatus.HELD && now.isBefore(heldUntil);
    }

    public Slot period() {
        return new Slot(startAt, endAt);
    }

    private void requireEnded(Instant now) {
        if (now.isBefore(endAt)) {
            throw new GroundDomainException(GroundErrorCode.INVALID_STATUS_TRANSITION, "The booking has not ended yet");
        }
    }

    private void transitionTo(BookingStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new GroundDomainException(GroundErrorCode.INVALID_STATUS_TRANSITION,
                "Booking " + bookingNumber + " is " + status + " and cannot become " + target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getBookingNumber() { return bookingNumber; }
    public UUID getVenueId() { return venueId; }
    public UUID getCourtId() { return courtId; }
    public UUID getSportId() { return sportId; }
    public UUID getUserId() { return userId; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public BookingStatus getStatus() { return status; }
    public UUID getPaymentId() { return paymentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getHeldUntil() { return heldUntil; }
    public Instant getCancelledAt() { return cancelledAt; }
    public CancelledBy getCancelledBy() { return cancelledBy; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
