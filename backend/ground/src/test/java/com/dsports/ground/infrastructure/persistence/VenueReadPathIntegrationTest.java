package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.Fakes;
import com.dsports.ground.application.result.SlotResult;
import com.dsports.ground.application.usecase.GetCourtSlotsUseCase;
import com.dsports.ground.application.usecase.GetVenueUseCase;
import com.dsports.ground.application.usecase.SearchVenuesUseCase;
import com.dsports.ground.application.usecase.VenueAssembler;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Blackout;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.BookingPolicy;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dsports.ground.domain.GroundFixtures.BADMINTON;
import static com.dsports.ground.domain.GroundFixtures.CRICKET;
import static com.dsports.ground.domain.GroundFixtures.FOOTBALL;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.SATURDAY;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.courtSpec;
import static com.dsports.ground.domain.GroundFixtures.venueSpec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public read path on a real PostgreSQL: venue and court persistence, blackouts and the computed slots with the real adapters. */
@Tag("integration")
class VenueReadPathIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static VenueR2dbcRepositoryAdapter venues;
    private static CourtR2dbcRepositoryAdapter courts;
    private static BlackoutR2dbcRepositoryAdapter blackouts;
    private static DatabaseClient db;
    private static GetCourtSlotsUseCase slots;
    private static GetVenueUseCase getVenue;
    private static SearchVenuesUseCase search;
    private static final Fakes.Owners OWNERS = new Fakes.Owners();

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").load().migrate();
        var connectionFactory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        var template = new R2dbcEntityTemplate(connectionFactory);
        var factory = new R2dbcRepositoryFactory(template);
        db = DatabaseClient.create(connectionFactory);
        venues = new VenueR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcVenueRepository.class), db);
        courts = new CourtR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcCourtRepository.class), db);
        blackouts = new BlackoutR2dbcRepositoryAdapter(db, template);
        var bookings = new BookingR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcBookingRepository.class), db);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var sports = new Fakes.Sports().with(CRICKET, "Cricket", true).with(FOOTBALL, "Football", true).with(BADMINTON, "Badminton", true);
        OWNERS.withRole.add(OWNER);
        slots = new GetCourtSlotsUseCase(venues, courts, bookings, blackouts, OWNERS, BookingPolicy.defaults(), clock);
        getVenue = new GetVenueUseCase(venues, courts, new VenueAssembler(courts, sports, OWNERS));
        search = new SearchVenuesUseCase(venues, courts, slots, new VenueAssembler(courts, sports, OWNERS));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Venue savedVenue(String city) {
        var s = venueSpec();
        var spec = new Venue.Spec("Venue in " + city, s.address(), city, s.latitude(), s.longitude(), s.facilities(), s.photoUrls(), s.opensAt(), s.closesAt(), s.timeZone());
        return venues.save(Venue.create(OWNER, spec, NOW)).block();
    }

    private static Court savedCourt(Venue venue, String name, UUID... sportIds) {
        var s = courtSpec();
        var spec = new Court.Spec(name, s.surface(), List.of(sportIds), s.slotMinutes(), s.basePrice(), s.weekendPrice(), s.peakPrice(), s.peakFrom(), s.peakTo());
        return courts.save(Court.create(venue, spec, NOW)).block();
    }

    private static void booking(Venue venue, Court court, UUID sport, int fromHour, int toHour, String status) {
        db.sql("""
                INSERT INTO ground_bookings (id, booking_number, venue_id, court_id, sport_id, user_id, start_at, end_at, price, currency, status, idempotency_key, held_until)
                VALUES (:id, :number, :venue, :court, :sport, :user, :start, :end, 1000, 'INR', :status, :key, now() + interval '10 minutes')
                """)
            .bind("id", UUID.randomUUID()).bind("number", "GB-" + UUID.randomUUID().toString().substring(0, 8)).bind("venue", venue.getId())
            .bind("court", court.getId()).bind("sport", sport).bind("user", UUID.randomUUID())
            .bind("start", at(WEDNESDAY, fromHour, 0)).bind("end", at(WEDNESDAY, toHour, 0))
            .bind("status", status).bind("key", UUID.randomUUID().toString()).fetch().rowsUpdated().block();
    }

    private static SlotResult slot(List<SlotResult> day, int hour) {
        return day.stream().filter(s -> s.start().equals(at(WEDNESDAY, hour, 0))).findFirst().orElseThrow();
    }

    @Test
    void aVenueSurvivesAPersistenceRoundTripIncludingArraysTimesAndTheVersion() {
        var venue = savedVenue("Pune");

        var loaded = venues.findById(venue.getId()).block();

        assertThat(loaded.getSpec().facilities()).containsExactly("Floodlights", "Parking");
        assertThat(loaded.getSpec().photoUrls()).containsExactly("https://example.com/p1.jpg");
        assertThat(loaded.getSpec().opensAt()).isEqualTo(LocalTime.of(6, 0));
        assertThat(loaded.getTimeZone().getId()).isEqualTo("Asia/Kolkata");
        assertThat(loaded.getOwnerId()).isEqualTo(OWNER);
        assertThat(loaded.getVersion()).isEqualTo(1);

        var s = venueSpec();
        loaded.update(new Venue.Spec("Renamed", "New Road", "Pune", null, null, List.of(), List.of(), LocalTime.of(7, 0), LocalTime.of(21, 0), s.timeZone()), NOW);
        venues.save(loaded).block();
        var reloaded = venues.findById(venue.getId()).block();
        assertThat(reloaded.getName()).isEqualTo("Renamed");
        assertThat(reloaded.getSpec().facilities()).isEmpty();
        assertThat(reloaded.getVersion()).isEqualTo(2);
    }

    @Test
    void aCourtSurvivesARoundTripWithItsSportsPricesAndTheVenuesHours() {
        var venue = savedVenue("Nagpur");
        var court = savedCourt(venue, "Court 1", CRICKET, FOOTBALL);

        var loaded = courts.findById(court.getId()).block();

        assertThat(loaded.getSportIds()).containsExactly(CRICKET, FOOTBALL);
        assertThat(loaded.getSpec().basePrice()).isEqualByComparingTo("1000");
        assertThat(loaded.getSpec().peakFrom()).isEqualTo(LocalTime.of(18, 0));
        assertThat(loaded.getHours().opensAt()).isEqualTo(LocalTime.of(6, 0));
        assertThat(loaded.getTimeZone().getId()).isEqualTo("Asia/Kolkata");
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(courts.findByVenue(venue.getId()).collectList().block()).hasSize(1);
        assertThat(courts.findAllById(List.of(court.getId(), UUID.randomUUID())).collectList().block()).hasSize(1);
    }

    @Test
    void whenAVenueChangesItsHoursItsCourtsFollowOnTheNextLoad() {
        var venue = savedVenue("Bhopal");
        var court = savedCourt(venue, "Court 1", CRICKET);
        var s = venueSpec();
        venue.update(new Venue.Spec(s.name(), s.address(), s.city(), null, null, List.of(), List.of(), LocalTime.of(8, 0), LocalTime.of(12, 0), s.timeZone()), NOW);
        venues.save(venue).block();

        var loaded = courts.findById(court.getId()).block();

        assertThat(loaded.slotsOn(WEDNESDAY)).hasSize(4);
    }

    @Test
    void aStaleWriterIsRejectedForVenuesAndCourts() {
        var venue = savedVenue("Nashik");
        var court = savedCourt(venue, "Court 1", CRICKET);
        var first = venues.findById(venue.getId()).block();
        var second = venues.findById(venue.getId()).block();
        first.deactivate(NOW);
        venues.save(first).block();
        second.deactivate(NOW);
        var firstCourt = courts.findById(court.getId()).block();
        var secondCourt = courts.findById(court.getId()).block();
        firstCourt.deactivate(NOW);
        courts.save(firstCourt).block();
        secondCourt.deactivate(NOW);

        assertConflict(() -> venues.save(second).block(), GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertConflict(() -> courts.save(secondCourt).block(), GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
    }

    @Test
    void aCourtNameIsUniqueAtItsVenueInAnyCaseButNotAcrossVenues() {
        var venue = savedVenue("Ranchi");
        savedCourt(venue, "Court 1", CRICKET);

        assertConflict(() -> savedCourt(venue, "COURT 1", FOOTBALL), GroundErrorCode.DUPLICATE_COURT_NAME);
        assertThat(savedCourt(savedVenue("Patna"), "Court 1", CRICKET).getName()).isEqualTo("Court 1");
    }

    @Test
    void aSlotIsBlockedByHeldAndConfirmedBookingsOfThatCourtOnlyAndNotByExpiredOrCancelledOnes() {
        var venue = savedVenue("Surat");
        var one = savedCourt(venue, "Court 1", CRICKET, FOOTBALL);
        var two = savedCourt(venue, "Court 2", CRICKET);
        booking(venue, one, CRICKET, 9, 11, "CONFIRMED");
        booking(venue, one, FOOTBALL, 12, 13, "HELD");
        booking(venue, one, CRICKET, 14, 15, "EXPIRED");
        booking(venue, one, CRICKET, 15, 16, "CANCELLED");

        var day = slots.execute(venue.getId(), one.getId(), WEDNESDAY).block();
        var other = slots.execute(venue.getId(), two.getId(), WEDNESDAY).block();

        assertThat(day).hasSize(16);
        assertThat(slot(day, 9).unavailableReason()).isEqualTo("BOOKED");
        assertThat(slot(day, 10).unavailableReason()).isEqualTo("BOOKED");
        assertThat(slot(day, 11).available()).isTrue();
        assertThat(slot(day, 12).unavailableReason()).isEqualTo("BOOKED");
        assertThat(slot(day, 14).available()).isTrue();
        assertThat(slot(day, 15).available()).isTrue();
        assertThat(slot(other, 9).available()).isTrue();
        assertThat(slot(other, 12).available()).isTrue();
    }

    @Test
    void aVenueBlackoutBlocksEveryCourtAndACourtBlackoutOnlyThatOne() {
        var venue = savedVenue("Indore");
        var one = savedCourt(venue, "Court 1", CRICKET);
        var two = savedCourt(venue, "Court 2", CRICKET);
        blackouts.save(Blackout.create(venue.getId(), null, at(WEDNESDAY, 7, 0), at(WEDNESDAY, 9, 0), "Pitch maintenance", OWNER, NOW)).block();
        blackouts.save(Blackout.create(venue.getId(), two.getId(), at(WEDNESDAY, 13, 0), at(WEDNESDAY, 14, 0), "Net repair", OWNER, NOW)).block();

        var first = slots.execute(venue.getId(), one.getId(), WEDNESDAY).block();
        var second = slots.execute(venue.getId(), two.getId(), WEDNESDAY).block();

        assertThat(slot(first, 7).unavailableReason()).isEqualTo("BLACKED_OUT");
        assertThat(slot(first, 8).unavailableReason()).isEqualTo("BLACKED_OUT");
        assertThat(slot(first, 9).available()).isTrue();
        assertThat(slot(first, 13).available()).isTrue();
        assertThat(slot(second, 7).unavailableReason()).isEqualTo("BLACKED_OUT");
        assertThat(slot(second, 13).unavailableReason()).isEqualTo("BLACKED_OUT");
        assertThat(slot(first, 9).price()).isEqualByComparingTo("1000");
        assertThat(slot(first, 18).price()).isEqualByComparingTo("2000");
        assertThat(slots.execute(venue.getId(), one.getId(), SATURDAY).block().get(3).price()).isEqualByComparingTo("1500");
        assertThat(blackouts.findByVenue(venue.getId(), at(WEDNESDAY, 0, 0), at(WEDNESDAY, 23, 0)).collectList().block()).hasSize(2);
    }

    @Test
    void anInactiveUnknownOrForeignVenueOrCourtIsNotFound() {
        var venue = savedVenue("Jaipur");
        var court = savedCourt(venue, "Court 1", CRICKET);
        var elsewhere = savedVenue("Kota");
        court.deactivate(NOW);
        courts.save(court).block();

        assertNotFound(() -> slots.execute(venue.getId(), court.getId(), WEDNESDAY).block(), GroundErrorCode.COURT_NOT_FOUND);
        assertNotFound(() -> slots.execute(elsewhere.getId(), court.getId(), WEDNESDAY).block(), GroundErrorCode.COURT_NOT_FOUND);
        venue.deactivate(NOW);
        venues.save(venue).block();
        assertNotFound(() -> getVenue.execute(venue.getId()).block(), GroundErrorCode.VENUE_NOT_FOUND);
        assertNotFound(() -> getVenue.execute(UUID.randomUUID()).block(), GroundErrorCode.VENUE_NOT_FOUND);
    }

    @Test
    void searchFiltersBySportAndCityIgnoringCaseAndSpacesAndByFreeSlotsInAWindow() {
        var kochi = savedVenue("Kochi");
        var court = savedCourt(kochi, "Court 1", CRICKET);
        savedCourt(kochi, "Hall", BADMINTON);
        var goa = savedVenue("Goa");
        savedCourt(goa, "Court 1", FOOTBALL);
        for (int h = 18; h < 22; h++) {
            booking(kochi, court, CRICKET, h, h + 1, "CONFIRMED");
        }

        var byCity = search.execute(CRICKET, " kOcHi ", null, null, null).collectList().block();
        assertThat(byCity).hasSize(1);
        assertThat(byCity.get(0).venue().name()).isEqualTo("Venue in Kochi");
        assertThat(byCity.get(0).courts()).extracting(c -> c.name()).containsExactly("Court 1");
        assertThat(byCity.get(0).availableSlots()).isNull();

        assertThat(search.execute(FOOTBALL, "kochi", null, null, null).collectList().block()).isEmpty();
        assertThat(search.execute(BADMINTON, "KOCHI", null, null, null).collectList().block()).hasSize(1);
        assertThat(search.execute(FOOTBALL, null, null, null, null).collectList().block()).extracting(h -> h.venue().name()).contains("Venue in Goa");

        var daytime = search.execute(CRICKET, "Kochi", WEDNESDAY, LocalTime.of(6, 0), LocalTime.of(10, 0)).collectList().block();
        assertThat(daytime).hasSize(1);
        assertThat(daytime.get(0).availableSlots()).isEqualTo(4);
        assertThat(daytime.get(0).lowestAvailablePrice()).isEqualByComparingTo("1000");

        var evening = search.execute(CRICKET, "Kochi", WEDNESDAY, LocalTime.of(18, 0), LocalTime.of(22, 0)).collectList().block();
        assertThat(evening).isEmpty();
    }

    @Test
    void aVenueWhoseOwnerLostTheRoleIsHiddenFromSearch() {
        var venue = savedVenue("Mysuru");
        savedCourt(venue, "Court 1", CRICKET);
        assertThat(search.execute(CRICKET, "Mysuru", null, null, null).collectList().block()).hasSize(1);

        OWNERS.withRole.remove(OWNER);
        try {
            assertThat(search.execute(CRICKET, "Mysuru", null, null, null).collectList().block()).isEmpty();
        } finally {
            OWNERS.withRole.add(OWNER);
        }
    }

    @Test
    void invalidSearchesAreValidationErrors() {
        assertThatThrownBy(() -> search.execute(null, null, null, LocalTime.NOON, null).collectList().block()).isInstanceOf(GroundDomainException.class);
        assertThatThrownBy(() -> search.execute(null, null, WEDNESDAY, LocalTime.NOON, LocalTime.of(9, 0)).collectList().block()).isInstanceOf(GroundDomainException.class);
    }

    private static void assertNotFound(Runnable action, GroundErrorCode code) {
        assertConflict(action, code);
    }

    private static void assertConflict(Runnable action, GroundErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
