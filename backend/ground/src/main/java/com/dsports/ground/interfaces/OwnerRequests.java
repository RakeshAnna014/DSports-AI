package com.dsports.ground.interfaces;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.PitchType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** Request bodies of the owner API. Bean validation rejects malformed input early; the domain re-validates every rule it owns. */
public final class OwnerRequests {

    private OwnerRequests() {
    }

    /** Everything an owner can set on a ground. Times are wall-clock times in {@code timeZone} (an IANA id such as Asia/Kolkata). */
    public record GroundRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 300) String address,
        @NotBlank @Size(max = 80) String city,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull PitchType pitchType,
        @Size(max = 20) List<@NotBlank @Size(max = 40) String> facilities,
        @Size(max = 10) List<@NotBlank @Size(max = 500) String> photoUrls,
        @NotNull LocalTime opensAt,
        @NotNull LocalTime closesAt,
        @NotBlank @Size(max = 40) String timeZone,
        @NotNull @Min(15) @Max(240) Integer slotMinutes,
        @NotNull @DecimalMin("0") BigDecimal basePrice,
        @DecimalMin("0") BigDecimal weekendPrice,
        @DecimalMin("0") BigDecimal peakPrice,
        LocalTime peakFrom,
        LocalTime peakTo) {

        public Ground.Spec toSpec() {
            ZoneId zone;
            try {
                zone = ZoneId.of(timeZone.trim());
            } catch (DateTimeException e) {
                throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "Unknown time zone: " + timeZone);
            }
            return new Ground.Spec(name, address, city, latitude, longitude, pitchType, facilities, photoUrls, opensAt, closesAt, zone,
                slotMinutes, basePrice, weekendPrice, peakPrice, peakFrom, peakTo);
        }
    }

    /** An edit: the full new details, whether the ground is bookable, and the version the owner last saw. */
    public record UpdateGroundRequest(@NotNull @Valid GroundRequest ground, boolean active, @NotNull @Min(0) Integer expectedVersion) {
    }

    public record BlackoutRequest(@NotNull Instant startAt, @NotNull Instant endAt, @NotBlank @Size(max = 200) String reason) {
    }
}
