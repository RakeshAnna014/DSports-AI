package com.dsports.ground.application;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.GroundBlackout;
import com.dsports.ground.domain.model.Slot;
import com.dsports.shared.application.TransactionRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory ports for use-case tests. They behave like the real adapters where the use cases depend on it (versions, ordering, constraints). */
public final class Fakes {

    private Fakes() {
    }

    public static final class Grounds implements GroundRepository {
        public final Map<UUID, Ground> store = new LinkedHashMap<>();

        @Override
        public Mono<Ground> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<Ground> findActive(String city) {
            return Flux.fromIterable(store.values()).filter(Ground::isActive).filter(g -> city == null || g.getCity().equalsIgnoreCase(city));
        }

        @Override
        public Flux<Ground> findAllById(java.util.Collection<UUID> ids) {
            return Flux.fromIterable(ids).flatMap(this::findById);
        }

        @Override
        public Flux<Ground> findByOwner(UUID ownerId) {
            return Flux.fromIterable(store.values()).filter(g -> g.getOwnerId().equals(ownerId));
        }

        @Override
        public Mono<Ground> save(Ground ground) {
            ground.setVersion(ground.getVersion() + 1);
            store.put(ground.getId(), ground);
            return Mono.just(ground);
        }
    }

    public static final class Blackouts implements BlackoutRepository {
        public final List<GroundBlackout> store = new ArrayList<>();

        @Override
        public Flux<GroundBlackout> findOverlapping(UUID groundId, Instant from, Instant to) {
            return Flux.fromIterable(store).filter(b -> b.groundId().equals(groundId) && b.startAt().isBefore(to) && b.endAt().isAfter(from));
        }

        @Override
        public Mono<GroundBlackout> save(GroundBlackout blackout) {
            store.add(blackout);
            return Mono.just(blackout);
        }
    }

    /** Stores bookings and enforces what the database enforces: no two active bookings of a ground overlap, one booking per (user, key). */
    public static final class Bookings implements BookingRepository {
        public final Map<UUID, Booking> store = new LinkedHashMap<>();
        /** Extra occupied periods that are not bookings (a convenience for tests that only need a taken slot). */
        public final List<Slot> occupied = new ArrayList<>();
        /** Every lock taken, in order, e.g. "user" then "ground". */
        public final List<String> calls = new ArrayList<>();
        public int locks;
        private long sequence;

        @Override
        public Mono<Booking> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<Booking> findByUser(UUID userId) {
            return Flux.fromIterable(store.values()).filter(b -> b.getUserId().equals(userId)).sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        }

        @Override
        public Flux<Booking> findByGround(UUID groundId, Instant from, Instant to) {
            return Flux.fromIterable(store.values()).filter(b -> b.getGroundId().equals(groundId) && b.period().overlaps(from, to))
                .sort((a, b) -> a.getStartAt().compareTo(b.getStartAt()));
        }

        @Override
        public Flux<UUID> findEndedConfirmedIds(Instant endedBefore, int limit) {
            return Flux.fromIterable(store.values()).filter(b -> b.getStatus().name().equals("CONFIRMED") && !b.getEndAt().isAfter(endedBefore)).map(Booking::getId).take(limit);
        }

        @Override
        public Mono<Booking> findByIdForUpdate(UUID id) {
            calls.add("row-lock");
            return findById(id);
        }

        @Override
        public Flux<UUID> findLapsedHoldIds(Instant now, int limit) {
            return Flux.fromIterable(store.values()).filter(bk -> bk.isHoldLapsed(now)).map(Booking::getId).take(limit);
        }

        @Override
        public Mono<Booking> findByUserAndKey(UUID userId, String key) {
            return Flux.fromIterable(store.values()).filter(b -> b.getUserId().equals(userId) && b.getIdempotencyKey().equals(key)).next();
        }

        @Override
        public Flux<Slot> findOccupiedPeriods(UUID groundId, Instant from, Instant to) {
            var periods = new ArrayList<>(occupied);
            store.values().stream().filter(b -> b.getGroundId().equals(groundId) && b.getStatus().occupiesSlot()).map(Booking::period).forEach(periods::add);
            return Flux.fromIterable(periods).filter(s -> s.overlaps(from, to));
        }

        @Override
        public Mono<Long> countActiveHolds(UUID userId, Instant now) {
            return Mono.just(store.values().stream().filter(b -> b.getUserId().equals(userId) && b.isPayable(now)).count());
        }

        @Override
        public Mono<String> nextBookingNumber() {
            return Mono.fromSupplier(() -> String.format("GB-%06d", ++sequence));
        }

        @Override
        public Mono<Booking> save(Booking booking) {
            var others = store.values().stream().filter(b -> !b.getId().equals(booking.getId())).toList();
            if (booking.getStatus().occupiesSlot() && others.stream().anyMatch(b -> b.getGroundId().equals(booking.getGroundId())
                && b.getStatus().occupiesSlot() && b.period().overlaps(booking.period()))) {
                return Mono.error(new GroundDomainException(GroundErrorCode.SLOT_UNAVAILABLE, "overlap"));
            }
            if (others.stream().anyMatch(b -> b.getUserId().equals(booking.getUserId()) && b.getIdempotencyKey().equals(booking.getIdempotencyKey()))) {
                return Mono.error(new GroundDomainException(GroundErrorCode.IDEMPOTENCY_KEY_REUSED, "key"));
            }
            booking.setVersion(booking.getVersion() + 1);
            store.put(booking.getId(), booking);
            return Mono.just(booking);
        }

        @Override
        public Mono<Void> lockGround(UUID groundId) {
            return Mono.fromRunnable(() -> {
                locks++;
                calls.add("ground");
            });
        }

        @Override
        public Mono<Void> lockUser(UUID userId) {
            return Mono.fromRunnable(() -> calls.add("user"));
        }
    }

    public static final TransactionRunner IMMEDIATE = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };
}
