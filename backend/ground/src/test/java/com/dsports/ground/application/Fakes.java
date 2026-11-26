package com.dsports.ground.application;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.OwnerEligibilityPort;
import com.dsports.ground.application.port.SportCatalogPort;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.usecase.BookingPresenter;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Blackout;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Slot;
import com.dsports.ground.domain.model.Venue;
import com.dsports.shared.application.TransactionRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** In-memory ports for use-case tests. They behave like the real adapters where the use cases depend on it (versions, ordering, constraints). */
public final class Fakes {

    private Fakes() {
    }

    public static final class Venues implements VenueRepository {
        public final Map<UUID, Venue> store = new LinkedHashMap<>();
        /** The courts, so a venue search can honour "has an active court offering the sport" like the SQL does. */
        public Courts courts;

        @Override
        public Mono<Venue> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<Venue> findActive(UUID sportId, String city) {
            return Flux.fromIterable(store.values()).filter(Venue::isActive)
                .filter(v -> city == null || v.getCity().trim().equalsIgnoreCase(city.trim()))
                .filter(v -> courts == null || courts.store.values().stream()
                    .anyMatch(c -> c.getVenueId().equals(v.getId()) && c.isActive() && (sportId == null || c.supports(sportId))));
        }

        @Override
        public Flux<Venue> findAllById(Collection<UUID> ids) {
            return Flux.fromIterable(ids).flatMap(this::findById);
        }

        @Override
        public Flux<Venue> findByOwner(UUID ownerId) {
            return Flux.fromIterable(store.values()).filter(v -> v.getOwnerId().equals(ownerId));
        }

        @Override
        public Flux<Venue> findAll() {
            return Flux.fromIterable(store.values());
        }

        @Override
        public Mono<Venue> save(Venue venue) {
            venue.setVersion(venue.getVersion() + 1);
            store.put(venue.getId(), venue);
            return Mono.just(venue);
        }
    }

    public static final class Courts implements CourtRepository {
        public final Map<UUID, Court> store = new LinkedHashMap<>();

        @Override
        public Mono<Court> findById(UUID id) {
            return Mono.justOrEmpty(store.get(id));
        }

        @Override
        public Flux<Court> findByVenue(UUID venueId) {
            return Flux.fromIterable(store.values()).filter(c -> c.getVenueId().equals(venueId));
        }

        @Override
        public Flux<Court> findActiveByVenue(UUID venueId) {
            return findByVenue(venueId).filter(Court::isActive);
        }

        @Override
        public Flux<Court> findAllById(Collection<UUID> ids) {
            return Flux.fromIterable(ids).flatMap(this::findById);
        }

        @Override
        public Mono<Court> save(Court court) {
            boolean duplicate = store.values().stream().anyMatch(c -> !c.getId().equals(court.getId()) && c.getVenueId().equals(court.getVenueId())
                && c.getName().equalsIgnoreCase(court.getName()));
            if (duplicate) {
                return Mono.error(new GroundDomainException(GroundErrorCode.DUPLICATE_COURT_NAME, "duplicate"));
            }
            court.setVersion(court.getVersion() + 1);
            store.put(court.getId(), court);
            return Mono.just(court);
        }
    }

    public static final class Blackouts implements BlackoutRepository {
        public final List<Blackout> store = new ArrayList<>();

        @Override
        public Flux<Blackout> findOverlapping(UUID venueId, UUID courtId, Instant from, Instant to) {
            return Flux.fromIterable(store).filter(b -> b.venueId().equals(venueId) && b.appliesTo(courtId) && b.startAt().isBefore(to) && b.endAt().isAfter(from));
        }

        @Override
        public Flux<Blackout> findByVenue(UUID venueId, Instant from, Instant to) {
            return Flux.fromIterable(store).filter(b -> b.venueId().equals(venueId) && b.startAt().isBefore(to) && b.endAt().isAfter(from));
        }

        @Override
        public Mono<Blackout> save(Blackout blackout) {
            store.add(blackout);
            return Mono.just(blackout);
        }
    }

    /** Stores bookings and enforces what the database enforces: no two active bookings of a COURT overlap, one booking per (user, key). */
    public static final class Bookings implements BookingRepository {
        public final Map<UUID, Booking> store = new LinkedHashMap<>();
        /** Extra occupied periods that are not bookings (a convenience for tests that only need a taken slot). */
        public final List<Slot> occupied = new ArrayList<>();
        /** Every lock taken, in order, e.g. "user" then "venue". */
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
        public Flux<Booking> findByVenue(UUID venueId, Instant from, Instant to) {
            return Flux.fromIterable(store.values()).filter(b -> b.getVenueId().equals(venueId) && b.period().overlaps(from, to))
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
        public Flux<Slot> findOccupiedPeriods(UUID courtId, Instant from, Instant to) {
            var periods = new ArrayList<>(occupied);
            store.values().stream().filter(b -> b.getCourtId().equals(courtId) && b.getStatus().occupiesSlot()).map(Booking::period).forEach(periods::add);
            return Flux.fromIterable(periods).filter(s -> s.overlaps(from, to));
        }

        @Override
        public Flux<Slot> findOccupiedPeriodsAtVenue(UUID venueId, Instant from, Instant to) {
            var periods = new ArrayList<>(occupied);
            store.values().stream().filter(b -> b.getVenueId().equals(venueId) && b.getStatus().occupiesSlot()).map(Booking::period).forEach(periods::add);
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
            if (booking.getStatus().occupiesSlot() && others.stream().anyMatch(b -> b.getCourtId().equals(booking.getCourtId())
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
        public Mono<Void> lockVenue(UUID venueId) {
            return Mono.fromRunnable(() -> {
                locks++;
                calls.add("venue");
            });
        }

        @Override
        public Mono<Void> lockUser(UUID userId) {
            return Mono.fromRunnable(() -> calls.add("user"));
        }
    }

    /** The catalogue, with a few sports registered by the tests. */
    public static final class Sports implements SportCatalogPort {
        public final Map<UUID, SportRef> store = new LinkedHashMap<>();

        public Sports with(UUID id, String name, boolean bookable) {
            store.put(id, new SportRef(id, name, name.toLowerCase(), bookable));
            return this;
        }

        @Override
        public Flux<SportRef> findByIds(Collection<UUID> ids) {
            return Flux.fromIterable(ids).distinct().filter(store::containsKey).map(store::get);
        }

        @Override
        public Flux<SportRef> findBookable() {
            return Flux.fromIterable(store.values()).filter(SportRef::bookable);
        }
    }

    /** Who currently holds the ground-owner role; a test removes someone to simulate an administrator taking the role away. */
    public static final class Owners implements OwnerEligibilityPort {
        public final Set<UUID> withRole = new HashSet<>();

        @Override
        public Mono<Set<UUID>> ownersAmong(Collection<UUID> userIds) {
            return Mono.just(userIds.stream().filter(withRole::contains).collect(Collectors.toSet()));
        }
    }

    /** The presenter over the fakes, as the use cases get it in production. */
    public static BookingPresenter presenter(Venues venues, Courts courts, Sports sports) {
        return new BookingPresenter(venues, courts, sports);
    }

    public static final TransactionRunner IMMEDIATE = new TransactionRunner() {
        @Override
        public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
            return Mono.defer(work);
        }
    };
}
