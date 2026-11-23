package com.dsports.cricket.application;

import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.scoring.CareerStats;
import com.dsports.cricket.domain.scoring.LogEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/** An in-memory scoring store with the same rules as the database one: one worker per match at a time, unique seq and client event id, and a snapshot per match. */
final class FakeScoringStore implements ScoringStore {

    final Map<UUID, List<Stored>> logs = new ConcurrentHashMap<>();
    final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    final Map<UUID, MatchStatus> statuses = new ConcurrentHashMap<>();
    final Map<UUID, List<CareerStats.Match>> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Semaphore> locks = new ConcurrentHashMap<>();

    List<LogEntry> entriesOf(UUID matchId) {
        return logs.getOrDefault(matchId, List.of()).stream().map(Stored::entry).toList();
    }

    @Override
    public <T> Mono<T> exclusive(UUID matchId, Function<Session, Mono<T>> work) {
        var lock = locks.computeIfAbsent(matchId, id -> new Semaphore(1));
        return Mono.fromRunnable(lock::acquireUninterruptibly).subscribeOn(Schedulers.boundedElastic())
            .then(Mono.defer(() -> work.apply(new FakeSession(matchId))))
            .doFinally(signal -> lock.release());
    }

    @Override
    public Flux<LogEntry> entries(UUID matchId) {
        return Flux.fromIterable(entriesOf(matchId));
    }

    @Override
    public Mono<Snapshot> snapshot(UUID matchId) {
        return Mono.justOrEmpty(snapshots.get(matchId));
    }

    @Override
    public Flux<CareerStats.Match> statsOf(UUID playerId) {
        return Flux.fromIterable(stats.values()).flatMapIterable(rows -> rows).filter(r -> r.playerId().equals(playerId));
    }

    @Override
    public Flux<UUID> matchIdsToRebuild() {
        return Flux.fromIterable(stats.keySet());
    }

    private final class FakeSession implements Session {
        private final UUID matchId;

        FakeSession(UUID matchId) {
            this.matchId = matchId;
        }

        @Override
        public Mono<Stored> findByClientEventId(UUID clientEventId) {
            return Mono.justOrEmpty(logs.getOrDefault(matchId, List.of()).stream().filter(s -> s.entry().clientEventId().equals(clientEventId)).findFirst());
        }

        @Override
        public Flux<LogEntry> entries() {
            return FakeScoringStore.this.entries(matchId);
        }

        @Override
        public Mono<Snapshot> snapshot() {
            return FakeScoringStore.this.snapshot(matchId);
        }

        @Override
        public Mono<Void> append(LogEntry entry, String requestHash, Snapshot snapshot, MatchStatus status) {
            return Mono.defer(() -> {
                var list = logs.computeIfAbsent(matchId, id -> new ArrayList<>());
                int last = list.isEmpty() ? 0 : list.get(list.size() - 1).entry().seq();
                boolean duplicateClient = list.stream().anyMatch(s -> s.entry().clientEventId().equals(entry.clientEventId()));
                if (entry.seq() != last + 1 || duplicateClient) {
                    return Mono.error(new CricketDomainException(CricketErrorCode.SCORE_CONFLICT, "unique key"));
                }
                list.add(new Stored(entry, requestHash));
                snapshots.put(matchId, snapshot);
                statuses.put(matchId, status);
                return Mono.empty();
            });
        }

        @Override
        public Mono<Void> saveSnapshot(Snapshot snapshot, MatchStatus status) {
            return Mono.fromRunnable(() -> snapshots.put(matchId, snapshot));
        }

        @Override
        public Mono<Void> replaceStats(List<CareerStats.Match> rows) {
            return Mono.fromRunnable(() -> {
                if (rows.isEmpty()) {
                    stats.remove(matchId);
                } else {
                    stats.put(matchId, List.copyOf(rows));
                }
            });
        }
    }
}
