package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Table("courts")
public class CourtEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("venue_id")
    private UUID venueId;

    @Column("name")
    private String name;

    @Column("surface")
    private String surface;

    @Column("sport_ids")
    private UUID[] sportIds;

    @Column("slot_minutes")
    private int slotMinutes;

    @Column("base_price")
    private BigDecimal basePrice;

    @Column("weekend_price")
    private BigDecimal weekendPrice;

    @Column("peak_price")
    private BigDecimal peakPrice;

    @Column("peak_from")
    private LocalTime peakFrom;

    @Column("peak_to")
    private LocalTime peakTo;

    @Column("active")
    private boolean active;

    @Version
    @Column("version")
    private int version;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getVenueId() { return venueId; }
    public void setVenueId(UUID venueId) { this.venueId = venueId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSurface() { return surface; }
    public void setSurface(String surface) { this.surface = surface; }
    public UUID[] getSportIds() { return sportIds; }
    public void setSportIds(UUID[] sportIds) { this.sportIds = sportIds; }
    public int getSlotMinutes() { return slotMinutes; }
    public void setSlotMinutes(int slotMinutes) { this.slotMinutes = slotMinutes; }
    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }
    public BigDecimal getWeekendPrice() { return weekendPrice; }
    public void setWeekendPrice(BigDecimal weekendPrice) { this.weekendPrice = weekendPrice; }
    public BigDecimal getPeakPrice() { return peakPrice; }
    public void setPeakPrice(BigDecimal peakPrice) { this.peakPrice = peakPrice; }
    public LocalTime getPeakFrom() { return peakFrom; }
    public void setPeakFrom(LocalTime peakFrom) { this.peakFrom = peakFrom; }
    public LocalTime getPeakTo() { return peakTo; }
    public void setPeakTo(LocalTime peakTo) { this.peakTo = peakTo; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
