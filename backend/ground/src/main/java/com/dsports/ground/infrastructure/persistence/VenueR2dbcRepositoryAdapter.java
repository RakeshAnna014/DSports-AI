package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Venue;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class VenueR2dbcRepositoryAdapter implements VenueRepository {

    private final SpringR2dbcVenueRepository springRepository;
    private final DatabaseClient databaseClient;

    public VenueR2dbcRepositoryAdapter(SpringR2dbcVenueRepository springRepository, DatabaseClient databaseClient) {
        this.springRepository = springRepository;
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Venue> findById(UUID id) {
        return springRepository.findById(id).map(VenueR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Venue> findActive(UUID sportIdOrNull, String cityOrNull) {
        // lower() on both sides makes the city match case-insensitive (there is an index on lower(city)); the sport is matched in the court's sport array.
        var sql = new StringBuilder("SELECT v.id FROM venues v WHERE v.active AND EXISTS (SELECT 1 FROM courts c WHERE c.venue_id = v.id AND c.active");
        if (sportIdOrNull != null) {
            sql.append(" AND :sportId = ANY(c.sport_ids)");
        }
        sql.append(')');
        if (cityOrNull != null) {
            sql.append(" AND lower(v.city) = lower(:city)");
        }
        var spec = databaseClient.sql(sql.toString());
        if (sportIdOrNull != null) {
            spec = spec.bind("sportId", sportIdOrNull);
        }
        if (cityOrNull != null) {
            spec = spec.bind("city", cityOrNull.trim());
        }
        return spec.map((row, meta) -> row.get("id", UUID.class)).all().collectList()
            .flatMapMany(ids -> ids.isEmpty() ? Flux.<VenueEntity>empty() : springRepository.findAllById(ids))
            .map(VenueR2dbcRepositoryAdapter::toDomain)
            .sort(Comparator.comparing(Venue::getName, String.CASE_INSENSITIVE_ORDER));
    }

    @Override
    public Flux<Venue> findByOwner(UUID ownerId) {
        return springRepository.findByOwnerIdOrderByNameAsc(ownerId).map(VenueR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Venue> findAll() {
        return springRepository.findAllByOrderByNameAsc().map(VenueR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Venue> findAllById(Collection<UUID> ids) {
        return ids.isEmpty() ? Flux.empty() : springRepository.findAllById(ids).map(VenueR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Venue> save(Venue venue) {
        return springRepository.save(toEntity(venue))
            .doOnNext(saved -> venue.setVersion(saved.getVersion()))
            .thenReturn(venue)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new GroundDomainException(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                "Venue " + venue.getId() + " was modified concurrently. Reload and retry."));
    }

    static Venue toDomain(VenueEntity e) {
        var spec = new Venue.Spec(e.getName(), e.getAddress(), e.getCity(), e.getLatitude(), e.getLongitude(),
            List.of(e.getFacilities() == null ? new String[0] : e.getFacilities()), List.of(e.getPhotoUrls() == null ? new String[0] : e.getPhotoUrls()),
            e.getOpensAt(), e.getClosesAt(), ZoneId.of(e.getTimeZone()));
        return Venue.reconstitute(e.getId(), e.getOwnerId(), spec, e.getCurrency(), e.isActive(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    static VenueEntity toEntity(Venue v) {
        var s = v.getSpec();
        var e = new VenueEntity();
        e.setId(v.getId());
        e.setOwnerId(v.getOwnerId());
        e.setName(s.name());
        e.setAddress(s.address());
        e.setCity(s.city());
        e.setLatitude(s.latitude());
        e.setLongitude(s.longitude());
        e.setFacilities(s.facilities().toArray(String[]::new));
        e.setPhotoUrls(s.photoUrls().toArray(String[]::new));
        e.setOpensAt(s.opensAt());
        e.setClosesAt(s.closesAt());
        e.setTimeZone(s.timeZone().getId());
        e.setCurrency(v.getCurrency());
        e.setActive(v.isActive());
        e.setVersion(v.getVersion());
        e.setCreatedAt(v.getCreatedAt());
        e.setUpdatedAt(v.getUpdatedAt());
        return e;
    }
}
