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
 * The venue and court migrations on a real PostgreSQL: the btree_gist extension is available, the {@code no_overlap_court} exclusion constraint makes
 * double booking of a court impossible whatever the sport and however the requests interleave, different courts of one venue are independent, and
 * V51 carries pre-existing V48 grounds over to venues with one court.
 */
@Tag("integration")
class VenueMigrationIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NINE = Instant.parse("2026-11-18T03:30:00Z");
    private static final UUID CRICKET = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID FOOTBALL = UUID.fromString("00000000-0000-0000-0000-00000000f001");

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

    private static UUID venue() throws SQLException {
        var id = UUID.randomUUID();
        try (var c = connection(); var s = c.prepareStatement(
            "INSERT INTO venues (id, owner_id, name, address, city, opens_at, closes_at) VALUES (?, ?, 'V', 'A', 'C', '06:00', '22:00')")) {
            s.setObject(1, id);
            s.setObject(2, UUID.randomUUID());
            s.executeUpdate();
        }
        return id;
    }

    private static UUID court(UUID venue, String name, UUID... sports) throws SQLException {
        var id = UUID.randomUUID();
        try (var c = connection(); var s = c.prepareStatement(
            "INSERT INTO courts (id, venue_id, name, sport_ids, slot_minutes, base_price) VALUES (?, ?, ?, ?, 60, 1000)")) {
            s.setObject(1, id);
            s.setObject(2, venue);
            s.setString(3, name);
            s.setArray(4, c.createArrayOf("uuid", sports));
            s.executeUpdate();
        }
        return id;
    }

    /** A venue with one court that offers cricket and football. */
    private record Place(UUID venue, UUID court) {
    }

    private static Place place() throws SQLException {
        var venue = venue();
        return new Place(venue, court(venue, "Court 1", CRICKET, FOOTBALL));
    }

    private static void booking(Connection c, UUID venue, UUID court, UUID sport, Instant start, Instant end, String status, UUID user, String key) throws SQLException {
        try (var s = c.prepareStatement(
            "INSERT INTO ground_bookings (id, booking_number, venue_id, court_id, sport_id, user_id, start_at, end_at, price, currency, status, idempotency_key, held_until) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1000, 'INR', ?, ?, now() + interval '10 minutes')")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, "GB-" + UUID.randomUUID().toString().substring(0, 8));
            s.setObject(3, venue);
            s.setObject(4, court);
            s.setObject(5, sport);
            s.setObject(6, user);
            s.setTimestamp(7, Timestamp.from(start));
            s.setTimestamp(8, Timestamp.from(end));
            s.setString(9, status);
            s.setString(10, key);
            s.executeUpdate();
        }
    }

    private static void booking(Place place, UUID sport, Instant start, Instant end, String status) throws SQLException {
        try (var c = connection()) {
            booking(c, place.venue(), place.court(), sport, start, end, status, UUID.randomUUID(), UUID.randomUUID().toString());
        }
    }

    @Test
    void theBtreeGistExtensionIsInstalled() throws SQLException {
        try (var c = connection(); var rs = c.createStatement().executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'btree_gist'")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void anOverlappingActiveBookingOfTheSameCourtIsRejectedByTheDatabase() throws SQLException {
        var place = place();
        booking(place, CRICKET, NINE, NINE.plusSeconds(7200), "CONFIRMED");

        assertThatThrownBy(() -> booking(place, CRICKET, NINE.plusSeconds(3600), NINE.plusSeconds(10800), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("no_overlap_court");
        assertThatThrownBy(() -> booking(place, CRICKET, NINE, NINE.plusSeconds(3600), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("no_overlap_court");
    }

    @Test
    void theSameCourtCannotBeBookedForTwoSportsAtTheSameTime() throws SQLException {
        var place = place();
        booking(place, CRICKET, NINE, NINE.plusSeconds(3600), "CONFIRMED");

        assertThatThrownBy(() -> booking(place, FOOTBALL, NINE, NINE.plusSeconds(3600), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("no_overlap_court");
        assertThatThrownBy(() -> booking(place, FOOTBALL, NINE.plusSeconds(1800), NINE.plusSeconds(5400), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("no_overlap_court");
    }

    @Test
    void differentCourtsOfOneVenueCanBeBookedAtTheSameTime() throws SQLException {
        var venue = venue();
        var one = new Place(venue, court(venue, "Court 1", CRICKET));
        var two = new Place(venue, court(venue, "Court 2", CRICKET));

        booking(one, CRICKET, NINE, NINE.plusSeconds(3600), "CONFIRMED");
        booking(two, CRICKET, NINE, NINE.plusSeconds(3600), "HELD");
    }

    @Test
    void backToBackBookingsAndOtherCourtsDoNotConflict() throws SQLException {
        var place = place();
        booking(place, CRICKET, NINE, NINE.plusSeconds(3600), "CONFIRMED");

        booking(place, CRICKET, NINE.plusSeconds(3600), NINE.plusSeconds(7200), "HELD");
        booking(place, FOOTBALL, NINE.minusSeconds(3600), NINE, "HELD");
        booking(place(), CRICKET, NINE, NINE.plusSeconds(3600), "HELD");
    }

    @Test
    void inactiveBookingsFreeTheirSlot() throws SQLException {
        var place = place();
        for (var status : List.of("EXPIRED", "CANCELLED", "COMPLETED", "NO_SHOW")) {
            booking(place, CRICKET, NINE, NINE.plusSeconds(3600), status);
        }

        booking(place, FOOTBALL, NINE, NINE.plusSeconds(3600), "HELD");
    }

    @Test
    void aCustomerCannotReuseAnIdempotencyKey() throws SQLException {
        var place = place();
        var user = UUID.randomUUID();
        try (var c = connection()) {
            booking(c, place.venue(), place.court(), CRICKET, NINE, NINE.plusSeconds(3600), "HELD", user, "same-key");
            assertThatThrownBy(() -> booking(c, place.venue(), place.court(), CRICKET, NINE.plusSeconds(7200), NINE.plusSeconds(10800), "HELD", user, "same-key"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uq_ground_bookings_user_key");
        }
    }

    @Test
    void invalidRowsAreRefused() throws SQLException {
        var place = place();

        assertThatThrownBy(() -> booking(place, CRICKET, NINE.plusSeconds(3600), NINE, "HELD")).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> booking(place, CRICKET, NINE, NINE.plusSeconds(3600), "BOGUS")).isInstanceOf(SQLException.class);
    }

    @Test
    void aBookingMustReferToARealVenueAndCourt() throws SQLException {
        var place = place();

        assertThatThrownBy(() -> booking(new Place(UUID.randomUUID(), place.court()), CRICKET, NINE, NINE.plusSeconds(3600), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("fk_ground_bookings_venue");
        assertThatThrownBy(() -> booking(new Place(place.venue(), UUID.randomUUID()), CRICKET, NINE, NINE.plusSeconds(3600), "HELD"))
            .isInstanceOf(SQLException.class).hasMessageContaining("fk_ground_bookings_court");
    }

    /**
     * The constraint alone is already a safe backstop: exactly one insert wins. PostgreSQL may abort a loser of a simultaneous race with
     * "deadlock detected" (40P01) instead of the overlap error (23P01); both mean "not booked", and the use case maps both to 409.
     */
    @Test
    void ofManySimultaneousUnlockedRequestsForOneCourtAndSlotExactlyOneSucceedsWhateverTheSport() throws Exception {
        var place = place();

        var outcome = race(place, false);

        assertThat(outcome.wins()).isEqualTo(1);
        assertThat(outcome.failures()).allSatisfy(m -> assertThat(m).containsAnyOf("no_overlap_court", "deadlock detected"));
    }

    /** What the application does: serialise attempts per venue with an advisory lock, so every loser gets the clean overlap error. */
    @Test
    void withAPerVenueAdvisoryLockEveryLoserGetsACleanOverlapError() throws Exception {
        var place = place();

        var outcome = race(place, true);

        assertThat(outcome.wins()).isEqualTo(1);
        assertThat(outcome.failures()).hasSize(11).allSatisfy(m -> assertThat(m).contains("no_overlap_court").doesNotContain("deadlock"));
    }

    private record Outcome(long wins, List<String> failures) {
    }

    private static Outcome race(Place place, boolean lock) throws Exception {
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
                                s.setString(1, "ground-venue:" + place.venue());
                                s.execute();
                            }
                        }
                        booking(c, place.venue(), place.court(), i % 2 == 0 ? CRICKET : FOOTBALL, NINE, NINE.plusSeconds(3600), "HELD", UUID.randomUUID(), "race-" + i);
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
    void invalidVenuesAndCourtsAreRefused() throws SQLException {
        var venue = venue();
        try (var c = connection()) {
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                "INSERT INTO venues (id, owner_id, name, address, city, opens_at, closes_at) VALUES (gen_random_uuid(), gen_random_uuid(), 'V', 'A', 'C', '22:00', '06:00')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_venues_hours");
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                "INSERT INTO courts (id, venue_id, name, slot_minutes, base_price, peak_price) VALUES (gen_random_uuid(), '" + venue + "', 'X', 60, 1000, 2000)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_courts_peak_window");
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                "INSERT INTO courts (id, venue_id, name, slot_minutes, base_price) VALUES (gen_random_uuid(), '" + venue + "', 'Y', 5, 1000)"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_courts_slot_minutes");
        }
    }

    @Test
    void courtNamesAreUniquePerVenueIgnoringCase() throws SQLException {
        var venue = venue();
        court(venue, "Court 1", CRICKET);

        assertThatThrownBy(() -> court(venue, "COURT 1", FOOTBALL)).isInstanceOf(SQLException.class).hasMessageContaining("uq_courts_venue_name");
        court(venue(), "Court 1", CRICKET);
    }

    // ---- V51 on pre-existing data ---------------------------------------------------------------------------------

    /**
     * Rows written under V48 (grounds, bookings, a blackout) are carried over by V51: every ground becomes a venue with the same id and one court
     * ("Main", same id) offering cricket; bookings and blackouts are re-pointed at them and the new constraint accepts them.
     */
    @Test
    void v51CarriesExistingGroundsBookingsAndBlackoutsOverToVenuesAndCourts() throws SQLException {
        try (var admin = connection()) {
            admin.createStatement().execute("CREATE DATABASE legacy");
        }
        var url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/legacy");
        var user = POSTGRES.getUsername();
        var password = POSTGRES.getPassword();

        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("48").load().migrate();

        var ground = UUID.randomUUID();
        var booking = UUID.randomUUID();
        var blackout = UUID.randomUUID();
        var owner = UUID.randomUUID();
        try (var c = DriverManager.getConnection(url, user, password)) {
            try (var s = c.prepareStatement("INSERT INTO grounds (id, owner_id, name, address, city, pitch_type, facilities, opens_at, closes_at, slot_minutes, "
                + "base_price, weekend_price, peak_price, peak_from, peak_to) VALUES (?, ?, 'Old Turf', '1 Road', 'Kochi', 'NATURAL_GRASS', ARRAY['Parking'], "
                + "'06:00', '22:00', 60, 1000, 1500, 2000, '18:00', '21:00')")) {
                s.setObject(1, ground);
                s.setObject(2, owner);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO ground_bookings (id, booking_number, ground_id, user_id, start_at, end_at, price, currency, status, "
                + "idempotency_key, held_until) VALUES (?, 'GB-000001', ?, ?, ?, ?, 1000, 'INR', 'CONFIRMED', 'k', now())")) {
                s.setObject(1, booking);
                s.setObject(2, ground);
                s.setObject(3, UUID.randomUUID());
                s.setTimestamp(4, Timestamp.from(NINE));
                s.setTimestamp(5, Timestamp.from(NINE.plusSeconds(3600)));
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO ground_blackouts (id, ground_id, start_at, end_at, reason, created_by) VALUES (?, ?, ?, ?, 'Rain', ?)")) {
                s.setObject(1, blackout);
                s.setObject(2, ground);
                s.setTimestamp(3, Timestamp.from(NINE.plusSeconds(7200)));
                s.setTimestamp(4, Timestamp.from(NINE.plusSeconds(10800)));
                s.setObject(5, owner);
                s.executeUpdate();
            }
            // The catalogue lives in another module; a stand-in table with cricket is enough for the migration to find the sport.
            c.createStatement().execute("CREATE TABLE sports (id UUID PRIMARY KEY, slug VARCHAR(150) NOT NULL)");
            try (var s = c.prepareStatement("INSERT INTO sports (id, slug) VALUES (?, 'cricket')")) {
                s.setObject(1, CRICKET);
                s.executeUpdate();
            }
        }

        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load().migrate();

        try (var c = DriverManager.getConnection(url, user, password)) {
            try (var s = c.prepareStatement("SELECT owner_id, name, city, opens_at::text AS opens, time_zone FROM venues WHERE id = ?")) {
                s.setObject(1, ground);
                var rs = s.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("owner_id", UUID.class)).isEqualTo(owner);
                assertThat(rs.getString("name")).isEqualTo("Old Turf");
                assertThat(rs.getString("city")).isEqualTo("Kochi");
                assertThat(rs.getString("opens")).startsWith("06:00");
                assertThat(rs.getString("time_zone")).isEqualTo("Asia/Kolkata");
            }
            try (var s = c.prepareStatement("SELECT venue_id, name, surface, sport_ids, base_price, weekend_price, peak_price FROM courts WHERE id = ?")) {
                s.setObject(1, ground);
                var rs = s.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("venue_id", UUID.class)).isEqualTo(ground);
                assertThat(rs.getString("name")).isEqualTo("Main");
                assertThat(rs.getString("surface")).isEqualTo("natural grass");
                assertThat((Object[]) rs.getArray("sport_ids").getArray()).containsExactly(CRICKET);
                assertThat(rs.getBigDecimal("base_price")).isEqualByComparingTo("1000");
                assertThat(rs.getBigDecimal("weekend_price")).isEqualByComparingTo("1500");
                assertThat(rs.getBigDecimal("peak_price")).isEqualByComparingTo("2000");
            }
            try (var s = c.prepareStatement("SELECT venue_id, court_id, sport_id FROM ground_bookings WHERE id = ?")) {
                s.setObject(1, booking);
                var rs = s.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("venue_id", UUID.class)).isEqualTo(ground);
                assertThat(rs.getObject("court_id", UUID.class)).isEqualTo(ground);
                assertThat(rs.getObject("sport_id", UUID.class)).isEqualTo(CRICKET);
            }
            try (var s = c.prepareStatement("SELECT venue_id, court_id FROM ground_blackouts WHERE id = ?")) {
                s.setObject(1, blackout);
                var rs = s.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("venue_id", UUID.class)).isEqualTo(ground);
                assertThat(rs.getObject("court_id")).isNull();
            }
            // The carried-over booking is protected by the new per-court constraint, and the old table is still there.
            assertThatThrownBy(() -> {
                try (var s = c.prepareStatement("INSERT INTO ground_bookings (id, booking_number, venue_id, court_id, sport_id, user_id, start_at, end_at, price, currency, "
                    + "status, idempotency_key, held_until) VALUES (gen_random_uuid(), 'GB-000002', ?, ?, ?, gen_random_uuid(), ?, ?, 1000, 'INR', 'HELD', 'k2', now())")) {
                    s.setObject(1, ground);
                    s.setObject(2, ground);
                    s.setObject(3, FOOTBALL);
                    s.setTimestamp(4, Timestamp.from(NINE));
                    s.setTimestamp(5, Timestamp.from(NINE.plusSeconds(1800)));
                    s.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("no_overlap_court");
            try (var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM grounds")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void v51IsHarmlessOnAnEmptyDatabase() throws SQLException {
        try (var admin = connection()) {
            admin.createStatement().execute("CREATE DATABASE empty_db");
        }
        var url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/empty_db");

        var result = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();

        assertThat(result.success).isTrue();
        try (var c = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             var rs = c.createStatement().executeQuery("SELECT (SELECT COUNT(*) FROM venues) + (SELECT COUNT(*) FROM courts)")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
