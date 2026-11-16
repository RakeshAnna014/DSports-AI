package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Table("grounds")
public class GroundEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("owner_id")
    private UUID ownerId;

    @Column("name")
    private String name;

    @Column("address")
    private String address;

    @Column("city")
    private String city;

    @Column("latitude")
    private BigDecimal latitude;

    @Column("longitude")
    private BigDecimal longitude;

    @Column("pitch_type")
    private String pitchType;

    @Column("facilities")
    private String[] facilities;

    @Column("photo_urls")
    private String[] photoUrls;

    @Column("opens_at")
    private LocalTime opensAt;

    @Column("closes_at")
    private LocalTime closesAt;

    @Column("time_zone")
    private String timeZone;

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

    @Column("currency")
    private String currency;

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
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
    public String getPitchType() { return pitchType; }
    public void setPitchType(String pitchType) { this.pitchType = pitchType; }
    public String[] getFacilities() { return facilities; }
    public void setFacilities(String[] facilities) { this.facilities = facilities; }
    public String[] getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(String[] photoUrls) { this.photoUrls = photoUrls; }
    public LocalTime getOpensAt() { return opensAt; }
    public void setOpensAt(LocalTime opensAt) { this.opensAt = opensAt; }
    public LocalTime getClosesAt() { return closesAt; }
    public void setClosesAt(LocalTime closesAt) { this.closesAt = closesAt; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
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
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
