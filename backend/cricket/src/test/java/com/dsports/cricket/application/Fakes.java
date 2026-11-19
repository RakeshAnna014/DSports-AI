package com.dsports.cricket.application;

import com.dsports.cricket.application.port.BookingLookupPort;
import com.dsports.cricket.application.port.ClaimRepository;
import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.PlayerRepository;
import com.dsports.cricket.application.port.TeamRepository;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayerClaim;
import com.dsports.cricket.domain.model.Team;
import com.dsports.shared.application.TransactionRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory ports for use-case tests. They enforce what the database enforces (unique phone per creator, one player per customer, unique team name per creator, versions). */
public final class Fakes {

    private Fakes() {
    }

    public static final class Players implements PlayerRepository {
        public final Map<UUID, Player> store = new LinkedHashMap<>();

        @Override
        public Mono<Player> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<Player> findAllById(Collection<UUID> ids) {
            return Flux.fromIterable(ids).flatMap(this::findById);
        }

        @Override
        public Flux<Player> findUsableBy(UUID user) {
            return Flux.fromIterable(store.values()).filter(p -> p.usableBy(user)).sort(Comparator.comparing(Player::getName));
        }

        @Override
        public Mono<Player> findByCustomer(UUID customerId) {
            return Flux.fromIterable(store.values()).filter(p -> customerId.equals(p.getCustomerId())).next();
        }

        @Override
        public Flux<Player> findUnlinkedByPhone(String phone, int limit) {
            return Flux.fromIterable(store.values()).filter(p -> !p.isLinked() && phone.equals(p.getPhone())).take(limit);
        }

        @Override
        public Mono<Player> save(Player player) {
            for (var other : store.values()) {
                if (other.getId().equals(player.getId())) {
                    continue;
                }
                if (player.getPhone() != null && player.getPhone().equals(other.getPhone()) && player.getCreatedBy().equals(other.getCreatedBy())) {
                    return Mono.error(new CricketDomainException(CricketErrorCode.DUPLICATE_PLAYER_PHONE, "dup phone"));
                }
                if (player.getCustomerId() != null && player.getCustomerId().equals(other.getCustomerId())) {
                    return Mono.error(new CricketDomainException(CricketErrorCode.CLAIMANT_ALREADY_LINKED, "dup customer"));
                }
            }
            player.setVersion(player.getVersion() + 1);
            store.put(player.getId(), player);
            return Mono.just(player);
        }
    }

    public static final class Teams implements TeamRepository {
        public final Map<UUID, Team> store = new LinkedHashMap<>();
        public Players players;

        @Override
        public Mono<Team> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<Team> findMine(UUID user) {
            return Flux.fromIterable(store.values()).filter(t -> t.getCreatedBy().equals(user)
                || (players != null && t.getMembers().stream().anyMatch(m -> user.equals(players.store.get(m.playerId()).getCustomerId())))).sort(Comparator.comparing(Team::getName));
        }

        @Override
        public Mono<Team> save(Team team) {
            var existing = store.get(team.getId());
            if (existing != null && existing != team && existing.getVersion() != team.getVersion()) {
                return Mono.error(new CricketDomainException(CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT, "stale"));
            }
            for (var other : store.values()) {
                if (!other.getId().equals(team.getId()) && other.getCreatedBy().equals(team.getCreatedBy()) && other.getName().equalsIgnoreCase(team.getName())) {
                    return Mono.error(new CricketDomainException(CricketErrorCode.DUPLICATE_TEAM_NAME, "dup name"));
                }
            }
            team.setVersion(team.getVersion() + 1);
            store.put(team.getId(), team);
            return Mono.just(team);
        }
    }

    public static final class Claims implements ClaimRepository {
        public final Map<UUID, PlayerClaim> store = new LinkedHashMap<>();
        public Players players;

        @Override
        public Mono<PlayerClaim> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<PlayerClaim> findPendingForCreator(UUID creator) {
            return Flux.fromIterable(store.values()).filter(c -> c.isPending() && players.store.get(c.getPlayerId()).getCreatedBy().equals(creator));
        }

        @Override
        public Flux<PlayerClaim> findPending(int limit) {
            return Flux.fromIterable(store.values()).filter(PlayerClaim::isPending).take(limit);
        }

        @Override
        public Flux<PlayerClaim> findPendingByPlayer(UUID playerId) {
            return Flux.fromIterable(store.values()).filter(c -> c.isPending() && c.getPlayerId().equals(playerId));
        }

        @Override
        public Flux<PlayerClaim> findByClaimant(UUID claimant) {
            return Flux.fromIterable(store.values()).filter(c -> c.getClaimantId().equals(claimant)).sort(Comparator.comparing(PlayerClaim::getCreatedAt).reversed());
        }

        @Override
        public Mono<Boolean> existsPending(UUID playerId, UUID claimant) {
            return Mono.just(store.values().stream().anyMatch(c -> c.isPending() && c.getPlayerId().equals(playerId) && c.getClaimantId().equals(claimant)));
        }

        @Override
        public Mono<PlayerClaim> save(PlayerClaim claim) {
            store.put(claim.getId(), claim);
            return Mono.just(claim);
        }
    }

    public static final class Directory implements CustomerDirectoryPort {
        public final Map<UUID, Person> people = new HashMap<>();

        @Override
        public Mono<UUID> findIdByEmail(String email) {
            return Flux.fromIterable(people.entrySet()).filter(e -> e.getValue().email().equalsIgnoreCase(email.trim())).map(Map.Entry::getKey).next();
        }

        @Override
        public Mono<Map<UUID, Person>> describe(Collection<UUID> ids) {
            var found = new HashMap<UUID, Person>();
            ids.forEach(id -> {
                if (people.containsKey(id)) {
                    found.put(id, people.get(id));
                }
            });
            return Mono.just(found);
        }
    }

    public static final class Matches implements MatchRepository {
        public final Map<UUID, Match> store = new LinkedHashMap<>();
        public Teams teams;

        @Override
        public Mono<Match> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<Match> findMine(UUID user) {
            return Flux.fromIterable(store.values()).filter(m -> m.isScorer(user)).sort(Comparator.comparing(Match::getCreatedAt).reversed());
        }

        @Override
        public Flux<Match> findLive(String city, int limit, int offset) {
            return Flux.fromIterable(store.values()).filter(m -> m.getStatus().isLive() && (city == null || city.equalsIgnoreCase(m.getSettings().city())))
                .sort(Comparator.comparing(Match::getUpdatedAt, Comparator.<java.time.Instant>reverseOrder()).thenComparing(Match::getId)).skip(offset).take(limit);
        }

        @Override
        public Mono<Match> save(Match match) {
            for (var other : store.values()) {
                if (!other.getId().equals(match.getId()) && match.getVenue() != null && other.getVenue() != null
                    && other.getVenue().bookingId().equals(match.getVenue().bookingId())) {
                    return Mono.error(new CricketDomainException(CricketErrorCode.BOOKING_ALREADY_USED, "booking used"));
                }
            }
            match.setVersion(match.getVersion() + 1);
            store.put(match.getId(), match);
            return Mono.just(match);
        }
    }

    public static final class Bookings implements BookingLookupPort {
        public final Map<UUID, BookingInfo> store = new HashMap<>();

        @Override
        public Mono<BookingInfo> find(UUID bookingId) {
            return Mono.justOrEmpty(store.get(bookingId));
        }
    }

    public static final TransactionRunner IMMEDIATE = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };
}
