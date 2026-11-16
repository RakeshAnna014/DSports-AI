package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("ground_bookings")
public class BookingEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("booking_number")
    private String bookingNumber;

    @Column("ground_id")
    private UUID groundId;

    @Column("user_id")
    private UUID userId;

    @Column("start_at")
    private Instant startAt;

    @Column("end_at")
    private Instant endAt;

    @Column("price")
    private BigDecimal price;

    @Column("currency")
    private String currency;

    @Column("status")
    private String status;

    @Column("payment_id")
    private UUID paymentId;

    @Column("idempotency_key")
    private String idempotencyKey;

    @Column("held_until")
    private Instant heldUntil;

    @Column("cancelled_at")
    private Instant cancelledAt;

    @Column("cancelled_by")
    private String cancelledBy;

    @Version
    @Column("version")
    private int version;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getBookingNumber() { return bookingNumber; }
    public void setBookingNumber(String bookingNumber) { this.bookingNumber = bookingNumber; }
    public UUID getGroundId() { return groundId; }
    public void setGroundId(UUID groundId) { this.groundId = groundId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getHeldUntil() { return heldUntil; }
    public void setHeldUntil(Instant heldUntil) { this.heldUntil = heldUntil; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(String cancelledBy) { this.cancelledBy = cancelledBy; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
