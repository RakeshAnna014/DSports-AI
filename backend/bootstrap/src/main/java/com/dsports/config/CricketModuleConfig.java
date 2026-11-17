package com.dsports.config;

import com.dsports.cricket.application.port.BookingLookupPort;
import com.dsports.cricket.application.port.ClaimRepository;
import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.application.usecase.ManageMatchesUseCase;
import com.dsports.cricket.application.usecase.ManagePlayersUseCase;
import com.dsports.cricket.application.usecase.ManageTeamsUseCase;
import com.dsports.cricket.application.usecase.PlayerClaimsUseCase;
import com.dsports.cricket.infrastructure.persistence.ClaimR2dbcRepositoryAdapter;
import com.dsports.cricket.infrastructure.persistence.MatchR2dbcRepositoryAdapter;
import com.dsports.cricket.infrastructure.persistence.PlayerR2dbcRepositoryAdapter;
import com.dsports.cricket.infrastructure.persistence.SpringR2dbcMatchRepository;
import com.dsports.cricket.infrastructure.persistence.SpringR2dbcPlayerRepository;
import com.dsports.cricket.infrastructure.persistence.SpringR2dbcTeamRepository;
import com.dsports.cricket.infrastructure.persistence.TeamR2dbcRepositoryAdapter;
import com.dsports.shared.application.TransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Wires the cricket module. The module owns its tables; the one place it reads another module's data (who a claimant is) is a port connected here. */
@Configuration
public class CricketModuleConfig {

    @Bean
    public PlayerRepository playerRepository(SpringR2dbcPlayerRepository repository) {
        return new PlayerR2dbcRepositoryAdapter(repository);
    }

    @Bean
    public TeamRepository teamRepository(SpringR2dbcTeamRepository repository, DatabaseClient databaseClient) {
        return new TeamR2dbcRepositoryAdapter(repository, databaseClient);
    }

    @Bean
    public ClaimRepository claimRepository(DatabaseClient databaseClient) {
        return new ClaimR2dbcRepositoryAdapter(databaseClient);
    }

    /** Names and emails of customers, read from the identity data, shown only to the person who must decide a claim. */
    @Bean
    public CustomerDirectoryPort customerDirectoryPort(DatabaseClient databaseClient) {
        return new CustomerDirectoryPort() {
            @Override
            public Mono<UUID> findIdByEmail(String email) {
                return databaseClient.sql("SELECT id FROM customers WHERE lower(email) = lower(:email) AND deleted_at IS NULL")
                    .bind("email", email.trim()).map((row, meta) -> row.get("id", UUID.class)).one();
            }

            @Override
            public Mono<Map<UUID, Person>> describe(Collection<UUID> ids) {
            if (ids.isEmpty()) {
                return Mono.just(Map.<UUID, CustomerDirectoryPort.Person>of());
            }
            return databaseClient.sql("SELECT id, first_name, last_name, email FROM customers WHERE id = ANY(:ids)")
                .bind("ids", ids.toArray(UUID[]::new))
                .map((row, meta) -> Map.entry(row.get("id", UUID.class),
                    new CustomerDirectoryPort.Person(row.get("first_name", String.class) + " " + row.get("last_name", String.class), row.get("email", String.class))))
                .all().collectList().map(entries -> {
                    var result = new HashMap<UUID, CustomerDirectoryPort.Person>();
                    entries.forEach(e -> result.put(e.getKey(), e.getValue()));
                    return result;
                });
            }
        };
    }

    @Bean
    public MatchRepository matchRepository(SpringR2dbcMatchRepository repository, DatabaseClient databaseClient) {
        return new MatchR2dbcRepositoryAdapter(repository, databaseClient);
    }

    /** A venue booking as the cricket module needs it: who made it, whether it is confirmed, for which sport, where and when. Read from the ground module's tables. */
    @Bean
    public BookingLookupPort bookingLookupPort(DatabaseClient databaseClient) {
        return bookingId -> databaseClient.sql("""
                SELECT b.id, b.user_id, b.status, s.slug AS sport_slug, v.name AS venue_name, v.city AS venue_city, b.start_at
                  FROM ground_bookings b JOIN venues v ON v.id = b.venue_id LEFT JOIN sports s ON s.id = b.sport_id
                 WHERE b.id = :id
                """)
            .bind("id", bookingId)
            .map((row, meta) -> new BookingLookupPort.BookingInfo(row.get("id", UUID.class), row.get("user_id", UUID.class), row.get("status", String.class),
                row.get("sport_slug", String.class), row.get("venue_name", String.class), row.get("venue_city", String.class), row.get("start_at", Instant.class)))
            .one();
    }

    @Bean
    public ManageMatchesUseCase manageMatchesUseCase(MatchRepository matchRepository, TeamRepository teamRepository, PlayerRepository playerRepository,
                                                     BookingLookupPort bookingLookupPort, CustomerDirectoryPort customerDirectoryPort, Clock clock) {
        return new ManageMatchesUseCase(matchRepository, teamRepository, playerRepository, bookingLookupPort, customerDirectoryPort, clock);
    }

    @Bean
    public ManagePlayersUseCase managePlayersUseCase(PlayerRepository playerRepository, Clock clock) {
        return new ManagePlayersUseCase(playerRepository, clock);
    }

    @Bean
    public PlayerClaimsUseCase playerClaimsUseCase(PlayerRepository playerRepository, ClaimRepository claimRepository, CustomerDirectoryPort customerDirectoryPort,
                                                   TransactionRunner transactionRunner, Clock clock) {
        return new PlayerClaimsUseCase(playerRepository, claimRepository, customerDirectoryPort, transactionRunner, clock);
    }

    @Bean
    public ManageTeamsUseCase manageTeamsUseCase(TeamRepository teamRepository, PlayerRepository playerRepository, Clock clock) {
        return new ManageTeamsUseCase(teamRepository, playerRepository, clock);
    }
}
