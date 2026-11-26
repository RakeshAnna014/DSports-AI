package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.Fakes;
import com.dsports.ground.application.usecase.BookingPresenter;
import com.dsports.ground.application.usecase.HoldBookingUseCase;
import com.dsports.ground.application.usecase.HoldBookingUseCase.Command;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.shared.application.TransactionRunner;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.dsports.ground.domain.GroundFixtures.CRICKET;
import static com.dsports.ground.domain.GroundFixtures.FOOTBALL;
import static com.dsports.ground.domain.GroundFixtures.NOW;
import static com.dsports.ground.domain.GroundFixtures.OWNER;
import static com.dsports.ground.domain.GroundFixtures.WEDNESDAY;
import static com.dsports.ground.domain.GroundFixtures.at;
import static com.dsports.ground.domain.GroundFixtures.courtSpec;
import static com.dsports.ground.domain.GroundFixtures.venueSpec;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holding a slot under real concurrency on PostgreSQL: many simultaneous requests for one slot give exactly one booking and clean 409s
 * for the rest; an identical request repeated at the same moment creates one booking; one customer cannot exceed the hold limit by racing.
 */
@Tag("integration")
class HoldBookingIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static VenueR2dbcRepositoryAdapter venues;
    private static CourtR2dbcRepositoryAdapter courts;
    private static BookingR2dbcRepositoryAdapter bookings;
    private static HoldBookingUseCase hold;
    private static DatabaseClient db;

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
        bookings = new BookingR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcBookingRepository.class), db);
        var operator = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        TransactionRunner transactions = new TransactionRunner() {
            @Override
            public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
                return operator.transactional(Mono.defer(work));
            }
        };
        var blackouts = new BlackoutR2dbcRepositoryAdapter(db, template);
        var sports = new Fakes.Sports().with(CRICKET, "Cricket", true).with(FOOTBALL, "Football", true);
        var owners = new Fakes.Owners();
        owners.withRole.add(OWNER);
        hold = new HoldBookingUseCase(venues, courts, bookings, blackouts, owners, sports, new BookingPresenter(venues, courts, sports), BookingPolicy.defaults(),
            Duration.ofMinutes(10), 2, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Venue venue() {
        return venues.save(Venue.create(OWNER, venueSpec(), NOW)).block();
    }

    private static Court court(Venue venue, String name) {
        var s = courtSpec();
        var spec = new Court.Spec(name, s.surface(), s.sportIds(), s.slotMinutes(), s.basePrice(), s.weekendPrice(), s.peakPrice(), s.peakFrom(), s.peakTo());
        return courts.save(Court.create(venue, spec, NOW)).block();
    }

    private static Court court() {
        return court(venue(), "Court 1");
    }

    private static Command command(Court court, UUID user, int fromHour, int toHour, String key) {
        return command(court, CRICKET, user, fromHour, toHour, key);
    }

    private static Command command(Court court, UUID sport, UUID user, int fromHour, int toHour, String key) {
        return new Command(user, court.getId(), sport, at(WEDNESDAY, fromHour, 0), at(WEDNESDAY, toHour, 0), key);
    }

    /** "OK" for a booking, otherwise the error code, run with every request in flight at the same time. */
    private static List<String> runConcurrently(List<Mono<HoldBookingUseCase.Result>> requests) {
        return Flux.fromIterable(requests)
            .flatMap(request -> request.subscribeOn(Schedulers.boundedElastic())
                .map(result -> result.replayed() ? "REPLAY" : "OK")
                .onErrorResume(GroundDomainException.class, e -> Mono.just(e.getErrorCode().name())), requests.size())
            .collectList().block(Duration.ofSeconds(60));
    }

    private static Venue venueOf(Court court) {
        return venues.findById(court.getVenueId()).block();
    }

    private static long count(String sql, Object... binds) {
        var spec = db.sql(sql);
        for (int i = 0; i < binds.length; i++) {
            spec = spec.bind(i, binds[i]);
        }
        return spec.map((row, meta) -> row.get(0, Long.class)).one().block();
    }

    @Test
    void ofManyCustomersRacingForOneSlotExactlyOneWinsAndTheRestGetACleanConflict() {
        var court = court();
        var requests = IntStream.range(0, 12).mapToObj(i -> hold.execute(command(court, UUID.randomUUID(), 17, 18, "race-" + i))).toList();

        var outcomes = runConcurrently(requests);

        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.equals("OK"))).hasSize(11).allMatch(GroundErrorCode.SLOT_UNAVAILABLE.name()::equals);
        assertThat(count("SELECT COUNT(*) FROM ground_bookings WHERE court_id = $1 AND status = 'HELD'", court.getId())).isEqualTo(1);
    }

    @Test
    void overlappingMultiSlotRequestsStillAllowExactlyOneWinner() {
        var court = court();
        var requests = IntStream.range(0, 8).mapToObj(i -> hold.execute(command(court, UUID.randomUUID(), 10 + i % 3, 12 + i % 3, "multi-" + i))).toList();

        var outcomes = runConcurrently(requests);

        assertThat(outcomes.stream().filter("OK"::equals).count()).isGreaterThanOrEqualTo(1);
        assertThat(outcomes).allMatch(o -> o.equals("OK") || o.equals(GroundErrorCode.SLOT_UNAVAILABLE.name()));
        var rows = bookings.findOccupiedPeriods(court.getId(), at(WEDNESDAY, 6, 0), at(WEDNESDAY, 22, 0)).collectList().block();
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                assertThat(rows.get(i).overlaps(rows.get(j))).as("bookings %s and %s overlap", rows.get(i), rows.get(j)).isFalse();
            }
        }
    }

    @Test
    void theSameRequestRepeatedAtTheSameMomentCreatesOneBooking() {
        var court = court();
        var user = UUID.randomUUID();
        var requests = IntStream.range(0, 6).mapToObj(i -> hold.execute(command(court, user, 9, 10, "same-key"))).toList();

        var outcomes = runConcurrently(requests);

        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter("REPLAY"::equals).count()).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM ground_bookings WHERE user_id = $1", user)).isEqualTo(1);
    }

    @Test
    void oneCustomerRacingWithDifferentSlotsCannotExceedTheHoldLimit() {
        var court = court();
        var user = UUID.randomUUID();
        var requests = IntStream.range(0, 6).mapToObj(i -> hold.execute(command(court, user, 6 + i, 7 + i, "limit-" + i))).toList();

        var outcomes = runConcurrently(requests);

        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(2);
        assertThat(outcomes.stream().filter(o -> !o.equals("OK"))).hasSize(4).allMatch(GroundErrorCode.HOLD_LIMIT_REACHED.name()::equals);
        assertThat(count("SELECT COUNT(*) FROM ground_bookings WHERE user_id = $1 AND status = 'HELD'", user)).isEqualTo(2);
    }

    @Test
    void twoCourtsOfOneVenueBookedAtTheSameMomentForTheSameTimeBothSucceed() {
        var venue = venue();
        var one = court(venue, "Court 1");
        var two = court(venue, "Court 2");
        var requests = List.of(hold.execute(command(one, UUID.randomUUID(), 17, 18, "c1")), hold.execute(command(two, UUID.randomUUID(), 17, 18, "c2")));

        var outcomes = runConcurrently(requests);

        assertThat(outcomes).containsExactlyInAnyOrder("OK", "OK");
        assertThat(count("SELECT COUNT(*) FROM ground_bookings WHERE venue_id = $1 AND status = 'HELD'", venue.getId())).isEqualTo(2);
    }

    @Test
    void theSameCourtRacedForTwoSportsAtTheSameTimeGivesExactlyOneWinner() {
        var court = court();
        var requests = IntStream.range(0, 10).mapToObj(i -> hold.execute(command(court, i % 2 == 0 ? CRICKET : FOOTBALL, UUID.randomUUID(), 18, 19, "sport-" + i))).toList();

        var outcomes = runConcurrently(requests);

        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.equals("OK"))).hasSize(9).allMatch(GroundErrorCode.SLOT_UNAVAILABLE.name()::equals);
        assertThat(count("SELECT COUNT(*) FROM ground_bookings WHERE court_id = $1 AND status = 'HELD'", court.getId())).isEqualTo(1);
    }

    @Test
    void theDatabaseConstraintAloneStillTurnsEveryLoserIntoAConflictWhenTheLocksAreBypassed() {
        var court = court();
        var requests = IntStream.range(0, 12).mapToObj(i -> Mono.defer(() -> {
            var booking = Booking.hold(venueOf(court), court, i % 2 == 0 ? CRICKET : FOOTBALL, UUID.randomUUID(), at(WEDNESDAY, 20, 0), at(WEDNESDAY, 21, 0),
                "GB-RAW" + i, "raw-" + i, NOW, Duration.ofMinutes(10), BookingPolicy.defaults());
            return bookings.save(booking).map(b -> new HoldBookingUseCase.Result(null, false));
        })).toList();

        var outcomes = runConcurrently(requests);

        assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.equals("OK")).collect(Collectors.toSet())).containsOnly(GroundErrorCode.SLOT_UNAVAILABLE.name());
    }

    @Test
    void aBookingSurvivesAPersistenceRoundTripAndEachUpdateRaisesItsVersion() {
        var court = court();
        var customer = UUID.randomUUID();
        var created = hold.execute(command(court, customer, 14, 16, "round-trip")).block().booking();

        var loaded = bookings.findById(created.id()).block();
        assertThat(loaded.getPrice()).isEqualByComparingTo("2000");
        assertThat(loaded.getStartAt()).isEqualTo(at(WEDNESDAY, 14, 0));
        assertThat(loaded.getVersion()).isEqualTo(1);

        loaded.confirm(UUID.randomUUID(), NOW);
        bookings.save(loaded).block();

        var reloaded = bookings.findById(created.id()).block();
        assertThat(reloaded.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(reloaded.getPaymentId()).isEqualTo(loaded.getPaymentId());
        assertThat(reloaded.getVersion()).isEqualTo(2);
        assertThat(bookings.findByUserAndKey(customer, "round-trip").block().getId()).isEqualTo(created.id());
        assertThat(bookings.nextBookingNumber().block()).matches("GB-\\d{6}");
    }
}
