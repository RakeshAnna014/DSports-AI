package com.dsports.ground.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V48 migration on a real PostgreSQL: the btree_gist extension is available, and the {@code no_overlap} exclusion constraint makes
 * double booking impossible at the database, however the requests interleave.
 */
@Tag("integration")
class GroundMigrationIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NINE = Instant.parse("2026-11-18T03:30:00Z");

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID ground() throws SQLException {
        var id = UUID.randomUUID();
        try (var c = connection(); var s = c.prepareStatement(
            "INSERT INTO grounds (id, owner_id, name, address, city, pitch_type, opens_at, closes_at, slot_minutes, base_price) "
                + "VALUES (?, ?, 'G', 'A', 'C', 'TURF', '06:00', '22:00', 60, 1000)")) {
            s.setObject(1, id);
            s.setObject(2, UUID.randomUUID());
            s.executeUpdate();
        }
        return id;
    }

    private static void booking(Connection c, UUID ground, Instant start, Instant end, String status, UUID user, String key) throws SQLException {
        try (var s = c.prepareStatement(
            "INSERT INTO ground_bookings (id, booking_number, ground_id, user_id, start_at, end_at, price, currency, status, idempotency_key, held_until) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1000, 'INR', ?, ?, now() + interval '10 minutes')")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, "GB-" + UUID.randomUUID().toString().substring(0, 8));
            s.setObject(3, ground);
            s.setObject(4, user);
            s.setTimestamp(5, Timestamp.from(start));
            s.setTimestamp(6, Timestamp.from(end));
            s.setString(7, status);
            s.setString(8, key);
            s.executeUpdate();
        }
    }

    private static void booking(UUID ground, Instant start, Instant end, String status) throws SQLException {
        try (var c = connection()) {
            booking(c, ground, start, end, status, UUID.randomUUID(), UUID.randomUUID().toString());
        }
    }

    @Test
    void theBtreeGistExtensionIsInstalled() throws SQLException {
        try (var c = connection(); var rs = c.createStatement().executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'btree_gist'")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void anOverlappingActiveBookingOfTheSameGroundIsRejectedByTheDatabase() throws SQLException {
        var ground = ground();
        booking(ground, NINE, NINE.plusSeconds(7200), "CONFIRMED");

        assertThatThrownBy(() -> booking(ground, NINE.plusSeconds(3600), NINE.plusSeconds(10800), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("no_overlap");
        assertThatThrownBy(() -> booking(ground, NINE, NINE.plusSeconds(3600), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("no_overlap");
    }

    @Test
    void backToBackBookingsAndOtherGroundsDoNotConflict() throws SQLException {
        var ground = ground();
        booking(ground, NINE, NINE.plusSeconds(3600), "CONFIRMED");

        booking(ground, NINE.plusSeconds(3600), NINE.plusSeconds(7200), "HELD");
        booking(ground, NINE.minusSeconds(3600), NINE, "HELD");
        booking(ground(), NINE, NINE.plusSeconds(3600), "HELD");
    }

    @Test
    void inactiveBookingsFreeTheirSlot() throws SQLException {
        var ground = ground();
        for (var status : List.of("EXPIRED", "CANCELLED", "COMPLETED", "NO_SHOW")) {
            booking(ground, NINE, NINE.plusSeconds(3600), status);
        }

        booking(ground, NINE, NINE.plusSeconds(3600), "HELD");
    }

    @Test
    void aCustomerCannotReuseAnIdempotencyKey() throws SQLException {
        var ground = ground();
        var user = UUID.randomUUID();
        try (var c = connection()) {
            booking(c, ground, NINE, NINE.plusSeconds(3600), "HELD", user, "same-key");
            assertThatThrownBy(() -> booking(c, ground, NINE.plusSeconds(7200), NINE.plusSeconds(10800), "HELD", user, "same-key"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uq_ground_bookings_user_key");
        }
    }

    @Test
    void invalidRowsAreRefused() throws SQLException {
        var ground = ground();

        assertThatThrownBy(() -> booking(ground, NINE.plusSeconds(3600), NINE, "HELD")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> booking(ground, NINE, NINE.plusSeconds(3600), "BOGUS")).isInstanceOf(SQLException.class);
    }

    /**
     * The constraint alone is already a safe backstop: exactly one insert wins. PostgreSQL may abort a loser of a simultaneous race with
     * "deadlock detected" (40P01) instead of the overlap error (23P01); both mean "not booked", and the use case maps both to 409.
     */
    @Test
    void ofManySimultaneousUnlockedRequestsForOneSlotExactlyOneSucceeds() throws Exception {
        var ground = ground();

        var outcome = race(ground, false);

        assertThat(outcome.wins()).isEqualTo(1);
        assertThat(outcome.failures()).allSatisfy(m -> assertThat(m).containsAnyOf("no_overlap", "deadlock detected"));
    }

    /** What the application does: serialise attempts per ground with an advisory lock, so every loser gets the clean overlap error. */
    @Test
    void withAPerGroundAdvisoryLockEveryLoserGetsACleanOverlapError() throws Exception {
        var ground = ground();

        var outcome = race(ground, true);

        assertThat(outcome.wins()).isEqualTo(1);
        assertThat(outcome.failures()).hasSize(11).allSatisfy(m -> assertThat(m).contains("no_overlap").doesNotContain("deadlock"));
    }

    private record Outcome(long wins, List<String> failures) {
    }

    private static Outcome race(UUID ground, boolean lock) throws Exception {
        int requests = 12;
        var executor = Executors.newFixedThreadPool(requests);
        var ready = new CountDownLatch(requests);
        var go = new CountDownLatch(1);
        try {
            List<Future<String>> results = IntStream.range(0, requests).mapToObj(i -> executor.submit(() -> {
                try (var c = connection()) {
                    c.setAutoCommit(false);
                    ready.countDown();
                    go.await();
                    try {
                        if (lock) {
                            try (var s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                                s.setString(1, "ground-slot:" + ground);
                                s.execute();
                            }
                        }
                        booking(c, ground, NINE, NINE.plusSeconds(3600), "HELD", UUID.randomUUID(), "race-" + i);
                        c.commit();
                        return "";
                    } catch (SQLException e) {
                        c.rollback();
                        return e.getMessage();
                    }
                }
            })).toList();
            ready.await();
            go.countDown();
            long wins = 0;
            var failures = new java.util.ArrayList<String>();
            for (var f : results) {
                var message = f.get();
                if (message.isEmpty()) {
                    wins++;
                } else {
                    failures.add(message);
                }
            }
            return new Outcome(wins, failures);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidGroundsAreRefused() throws SQLException {
        try (var c = connection()) {
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                "INSERT INTO grounds (id, owner_id, name, address, city, pitch_type, opens_at, closes_at, slot_minutes, base_price) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), 'G', 'A', 'C', 'TURF', '22:00', '06:00', 60, 1000)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_grounds_hours");
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                "INSERT INTO grounds (id, owner_id, name, address, city, pitch_type, opens_at, closes_at, slot_minutes, base_price, peak_price) "
                    + "VALUES (gen_random_uuid(), gen_random_uuid(), 'G', 'A', 'C', 'TURF', '06:00', '22:00', 60, 1000, 2000)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_grounds_peak_window");
        }
    }
}
