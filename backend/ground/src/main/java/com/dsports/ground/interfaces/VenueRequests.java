package com.dsports.ground.interfaces;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Request bodies of the venue management API (owner and administrator). Bean validation rejects malformed input early; the domain re-validates every rule it owns. */
public final class VenueRequests {

    private VenueRequests() {
    }

    /** Everything that describes the place. Times are wall-clock times in {@code timeZone} (an IANA id such as Asia/Kolkata). */
    public record VenueRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 300) String address,
        @NotBlank @Size(max = 80) String city,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @Size(max = 20) List<@NotBlank @Size(max = 40) String> facilities,
        @Size(max = 10) List<@NotBlank @Size(max = 500) String> photoUrls,
        @NotNull LocalTime opensAt,
        @NotNull LocalTime closesAt,
        @NotBlank @Size(max = 40) String timeZone) {

        public Venue.Spec toSpec() {
            ZoneId zone;
            try {
                zone = ZoneId.of(timeZone.trim());
            } catch (DateTimeException e) {
                throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "Unknown time zone: " + timeZone);
            }
            return new Venue.Spec(name, address, city, latitude, longitude, facilities, photoUrls, opensAt, closesAt, zone);
        }
    }

    /** {@code ownerId} is used only on the administrator route (who names the owner); an owner always creates for themselves. */
    public record CreateVenueRequest(@NotNull @Valid VenueRequest venue, UUID ownerId) {
    }

    /** An edit: the full new details, whether the venue is bookable, and the version the caller last saw. {@code ownerId} (administrators only) reassigns it. */
    public record UpdateVenueRequest(@NotNull @Valid VenueRequest venue, boolean active, @NotNull @Min(0) Integer expectedVersion, UUID ownerId) {
    }

    /** A court: its own slot length and prices, and the catalogue sports it offers. */
    public record CourtRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 40) String surface,
        @NotEmpty @Size(max = 12) List<@NotNull UUID> sportIds,
        @NotNull @Min(15) @Max(240) Integer slotMinutes,
        @NotNull @DecimalMin("0") BigDecimal basePrice,
        @DecimalMin("0") BigDecimal weekendPrice,
        @DecimalMin("0") BigDecimal peakPrice,
        LocalTime peakFrom,
        LocalTime peakTo) {

        public Court.Spec toSpec() {
            return new Court.Spec(name, surface, sportIds, slotMinutes, basePrice, weekendPrice, peakPrice, peakFrom, peakTo);
        }
    }

    public record UpdateCourtRequest(@NotNull @Valid CourtRequest court, boolean active, @NotNull @Min(0) Integer expectedVersion) {
    }

    /** {@code courtId} null closes the whole venue. */
    public record BlackoutRequest(UUID courtId, @NotNull Instant startAt, @NotNull Instant endAt, @NotBlank @Size(max = 200) String reason) {
    }
}
