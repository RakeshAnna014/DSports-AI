package com.dsports.ground.domain;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Venue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.dsports.ground.domain.GroundFixtures.IST;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.venue;
import static com.dsports.ground.domain.GroundFixtures.venueSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VenueTest {

    @Test
    void aNewVenueIsActiveAndTrimsItsText() {
        var s = venueSpec();
        var venue = Venue.create(OWNER, new Venue.Spec("  Green Park  ", " 12 Park Road ", " Bengaluru ", null, null, null, null,
            s.opensAt(), s.closesAt(), IST), NOW);

        assertThat(venue.isActive()).isTrue();
        assertThat(venue.getName()).isEqualTo("Green Park");
        assertThat(venue.getCity()).isEqualTo("Bengaluru");
        assertThat(venue.getCurrency()).isEqualTo("INR");
        assertThat(venue.getSpec().facilities()).isEmpty();
    }

    @Test
    void theHoursAreWallClockTimesInTheVenuesOwnZone() {
        var hours = venue().hours();

        assertThat(hours.opensAt()).isEqualTo(LocalTime.of(6, 0));
        assertThat(hours.closesAt()).isEqualTo(LocalTime.of(22, 0));
        assertThat(hours.timeZone()).isEqualTo(IST);
    }

    @Test
    void invalidVenueDetailsAreRejected() {
        assertInvalid(s -> change(s, " ", s.opensAt(), s.closesAt(), null, null));
        assertInvalid(s -> change(s, s.name(), LocalTime.of(22, 0), LocalTime.of(6, 0), null, null));
        assertInvalid(s -> change(s, s.name(), s.opensAt(), s.opensAt(), null, null));
        assertInvalid(s -> change(s, s.name(), s.opensAt(), s.closesAt(), new BigDecimal("12.9"), null));
        assertInvalid(s -> change(s, s.name(), s.opensAt(), s.closesAt(), new BigDecimal("95"), new BigDecimal("77")));
        assertInvalid(s -> new Venue.Spec(s.name(), s.address(), s.city(), null, null, List.of(), List.of("javascript:alert(1)"), s.opensAt(), s.closesAt(), s.timeZone()));
        assertInvalid(s -> new Venue.Spec(s.name(), s.address(), s.city(), null, null, java.util.Collections.nCopies(21, "x"), List.of(), s.opensAt(), s.closesAt(), s.timeZone()));
    }

    @Test
    void updatingRevalidatesAndKeepsTheOwner() {
        var venue = venue();
        var s = venueSpec();
        venue.update(new Venue.Spec("Renamed", s.address(), "Mysuru", null, null, List.of(), List.of(), LocalTime.of(7, 0), LocalTime.of(21, 0), IST), NOW.plusSeconds(60));

        assertThat(venue.getName()).isEqualTo("Renamed");
        assertThat(venue.getOwnerId()).isEqualTo(OWNER);
        assertThatThrownBy(() -> venue.update(new Venue.Spec("", s.address(), "x", null, null, List.of(), List.of(), s.opensAt(), s.closesAt(), IST), NOW))
            .isInstanceOf(GroundDomainException.class);
    }

    @Test
    void reassigningHandsTheVenueToAnotherOwnerAndNothingElseChanges() {
        var venue = venue();
        var other = UUID.randomUUID();

        venue.reassignTo(other, NOW.plusSeconds(5));

        assertThat(venue.getOwnerId()).isEqualTo(other);
        assertThat(venue.getName()).isEqualTo(venueSpec().name());
        assertThat(venue.isActive()).isTrue();
    }

    private static Venue.Spec change(Venue.Spec s, String name, LocalTime opens, LocalTime closes, BigDecimal lat, BigDecimal lon) {
        return new Venue.Spec(name, s.address(), s.city(), lat, lon, List.of(), List.of(), opens, closes, s.timeZone());
    }

    private static void assertInvalid(UnaryOperator<Venue.Spec> change) {
        assertThatThrownBy(() -> Venue.create(OWNER, change.apply(venueSpec()), NOW))
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.VALIDATION_ERROR));
    }
}
