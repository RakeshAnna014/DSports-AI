package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.result.SlotResult;
import com.dsports.ground.application.usecase.GetGroundSlotsUseCase;
import com.dsports.ground.application.usecase.GetGroundUseCase;
import com.dsports.ground.application.usecase.SearchGroundsUseCase;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.GroundBlackout;
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
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.SATURDAY;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public read path on a real PostgreSQL: ground persistence, blackouts and the computed slots with the real adapters. */
@Tag("integration")
class GroundReadPathIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static GroundR2dbcRepositoryAdapter grounds;
    private static BlackoutR2dbcRepositoryAdapter blackouts;
    private static DatabaseClient db;
    private static GetGroundSlotsUseCase slots;
    private static GetGroundUseCase getGround;
    private static SearchGroundsUseCase search;

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
        grounds = new GroundR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcGroundRepository.class));
        blackouts = new BlackoutR2dbcRepositoryAdapter(db, template);
        var bookings = new BookingR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcBookingRepository.class), db);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        slots = new GetGroundSlotsUseCase(grounds, bookings, blackouts, BookingPolicy.defaults(), clock);
        getGround = new GetGroundUseCase(grounds);
        search = new SearchGroundsUseCase(grounds, slots);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Ground saved(String city) {
        var s = spec();
        var spec = new Ground.Spec("Ground in " + city, s.address(), city, s.latitude(), s.longitude(), s.pitchType(), s.facilities(), s.photoUrls(),
            s.opensAt(), s.closesAt(), s.timeZone(), s.slotMinutes(), s.basePrice(), s.weekendPrice(), s.peakPrice(), s.peakFrom(), s.peakTo());
        return grounds.save(Ground.create(OWNER, spec, NOW)).block();
    }

    private static void booking(Ground ground, int fromHour, int toHour, String status) {
        db.sql("""
                INSERT INTO ground_bookings (id, booking_number, ground_id, user_id, start_at, end_at, price, currency, status, idempotency_key, held_until)
                VALUES (:id, :number, :ground, :user, :start, :end, 1000, 'INR', :status, :key, now() + interval '10 minutes')
                """)
            .bind("id", UUID.randomUUID()).bind("number", "GB-" + UUID.randomUUID().toString().substring(0, 8)).bind("ground", ground.getId())
            .bind("user", UUID.randomUUID()).bind("start", at(WEDNESDAY, fromHour, 0)).bind("end", at(WEDNESDAY, toHour, 0))
            .bind("status", status).bind("key", UUID.randomUUID().toString()).fetch().rowsUpdated().block();
    }

    private static SlotResult slot(List<SlotResult> day, int hour) {
        return day.stream().filter(s -> s.start().equals(at(WEDNESDAY, hour, 0))).findFirst().orElseThrow();
    }

    @Test
    void aGroundSurvivesAPersistenceRoundTripIncludingArraysTimesAndTheVersion() {
        var ground = saved("Pune");

        var loaded = grounds.findById(ground.getId()).block();

        assertThat(loaded.getSpec().facilities()).containsExactly("Floodlights", "Parking");
        assertThat(loaded.getSpec().photoUrls()).containsExactly("https://example.com/p1.jpg");
        assertThat(loaded.getSpec().opensAt()).isEqualTo(LocalTime.of(6, 0));
        assertThat(loaded.getSpec().peakFrom()).isEqualTo(LocalTime.of(18, 0));
        assertThat(loaded.getTimeZone().getId()).isEqualTo("Asia/Kolkata");
        assertThat(loaded.getSpec().basePrice()).isEqualByComparingTo("1000");
        assertThat(loaded.getVersion()).isEqualTo(1);

        loaded.update(new Ground.Spec("Renamed", "New Road", "Pune", null, null, loaded.getSpec().pitchType(), List.of(), List.of(), LocalTime.of(7, 0),
            LocalTime.of(21, 0), loaded.getTimeZone(), 120, loaded.getSpec().basePrice(), null, null, null, null), NOW);
        grounds.save(loaded).block();
        var reloaded = grounds.findById(ground.getId()).block();
        assertThat(reloaded.getName()).isEqualTo("Renamed");
        assertThat(reloaded.getSpec().facilities()).isEmpty();
        assertThat(reloaded.getVersion()).isEqualTo(2);
    }

    @Test
    void aStaleWriterIsRejected() {
        var ground = saved("Nashik");
        var first = grounds.findById(ground.getId()).block();
        var second = grounds.findById(ground.getId()).block();
        first.deactivate(NOW);
        grounds.save(first).block();
        second.deactivate(NOW);

        assertThatThrownBy(() -> grounds.save(second).block())
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT));
    }

    @Test
    void aSlotIsBlockedByHeldAndConfirmedBookingsButNotByExpiredOrCancelledOnes() {
        var ground = saved("Surat");
        booking(ground, 9, 11, "CONFIRMED");
        booking(ground, 12, 13, "HELD");
        booking(ground, 14, 15, "EXPIRED");
        booking(ground, 15, 16, "CANCELLED");

        var day = slots.execute(ground.getId(), WEDNESDAY).block();

        assertThat(day).hasSize(16);
        assertThat(slot(day, 9).unavailableReason()).isEqualTo("BOOKED");
        assertThat(slot(day, 10).unavailableReason()).isEqualTo("BOOKED");
        assertThat(slot(day, 11).available()).isTrue();
        assertThat(slot(day, 12).unavailableReason()).isEqualTo("BOOKED");
        assertThat(slot(day, 14).available()).isTrue();
        assertThat(slot(day, 15).available()).isTrue();
    }

    @Test
    void aBlackoutBlocksItsSlotsAndPricesComeFromTheGroundRules() {
        var ground = saved("Indore");
        blackouts.save(GroundBlackout.create(ground.getId(), at(WEDNESDAY, 7, 0), at(WEDNESDAY, 9, 0), "Pitch maintenance", OWNER, NOW)).block();

        var day = slots.execute(ground.getId(), WEDNESDAY).block();

        assertThat(slot(day, 7).unavailableReason()).isEqualTo("BLACKED_OUT");
        assertThat(slot(day, 8).unavailableReason()).isEqualTo("BLACKED_OUT");
        assertThat(slot(day, 9).available()).isTrue();
        assertThat(slot(day, 9).price()).isEqualByComparingTo("1000");
        assertThat(slot(day, 18).price()).isEqualByComparingTo("2000");
        var weekend = slots.execute(ground.getId(), SATURDAY).block();
        assertThat(weekend.get(3).price()).isEqualByComparingTo("1500");
    }

    @Test
    void anInactiveOrUnknownGroundIsNotFound() {
        var ground = saved("Jaipur");
        ground.deactivate(NOW);
        grounds.save(ground).block();

        assertNotFound(() -> getGround.execute(ground.getId()).block());
        assertNotFound(() -> slots.execute(ground.getId(), WEDNESDAY).block());
        assertNotFound(() -> getGround.execute(UUID.randomUUID()).block());
    }

    @Test
    void searchFiltersByCityIgnoringCaseAndByFreeSlotsInAWindow() {
        var ground = saved("Kochi");
        saved("Goa");
        for (int h = 18; h < 22; h++) {
            booking(ground, h, h + 1, "CONFIRMED");
        }

        var byCity = search.execute("kOcHi", null, null, null).collectList().block();
        assertThat(byCity).hasSize(1);
        assertThat(byCity.get(0).ground().name()).isEqualTo("Ground in Kochi");
        assertThat(byCity.get(0).availableSlots()).isNull();

        var daytime = search.execute("Kochi", WEDNESDAY, LocalTime.of(6, 0), LocalTime.of(10, 0)).collectList().block();
        assertThat(daytime).hasSize(1);
        assertThat(daytime.get(0).availableSlots()).isEqualTo(4);
        assertThat(daytime.get(0).lowestAvailablePrice()).isEqualByComparingTo("1000");

        var evening = search.execute("Kochi", WEDNESDAY, LocalTime.of(18, 0), LocalTime.of(22, 0)).collectList().block();
        assertThat(evening).isEmpty();
    }

    @Test
    void invalidSearchesAreValidationErrors() {
        assertThatThrownBy(() -> search.execute(null, null, LocalTime.NOON, null).collectList().block()).isInstanceOf(GroundDomainException.class);
        assertThatThrownBy(() -> search.execute(null, WEDNESDAY, LocalTime.NOON, LocalTime.of(9, 0)).collectList().block()).isInstanceOf(GroundDomainException.class);
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(GroundDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(GroundErrorCode.GROUND_NOT_FOUND));
    }
}
