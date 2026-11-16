package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import io.r2dbc.spi.Row;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Courts are read joined with their venue's opening hours (so a court can compute its slot grid) and written on their own row. */
public class CourtR2dbcRepositoryAdapter implements CourtRepository {

    private static final String SELECT = """
        SELECT c.id, c.venue_id, c.name, c.surface, c.sport_ids, c.slot_minutes, c.base_price, c.weekend_price, c.peak_price, c.peak_from, c.peak_to,
               c.active, c.version, c.created_at, c.updated_at, v.opens_at, v.closes_at, v.time_zone
          FROM courts c JOIN venues v ON v.id = c.venue_id
        """;

    private final SpringR2dbcCourtRepository springRepository;
    private final DatabaseClient databaseClient;

    public CourtR2dbcRepositoryAdapter(SpringR2dbcCourtRepository springRepository, DatabaseClient databaseClient) {
        this.springRepository = springRepository;
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Court> findById(UUID id) {
        return databaseClient.sql(SELECT + " WHERE c.id = :id").bind("id", id).map((row, meta) -> toDomain(row)).one();
    }

    @Override
    public Flux<Court> findByVenue(UUID venueId) {
        return databaseClient.sql(SELECT + " WHERE c.venue_id = :venueId ORDER BY lower(c.name)").bind("venueId", venueId).map((row, meta) -> toDomain(row)).all();
    }

    @Override
    public Flux<Court> findActiveByVenue(UUID venueId) {
        return databaseClient.sql(SELECT + " WHERE c.venue_id = :venueId AND c.active ORDER BY lower(c.name)").bind("venueId", venueId)
            .map((row, meta) -> toDomain(row)).all();
    }

    @Override
    public Flux<Court> findAllById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }
        return databaseClient.sql(SELECT + " WHERE c.id = ANY(:ids)").bind("ids", ids.toArray(UUID[]::new)).map((row, meta) -> toDomain(row)).all();
    }

    @Override
    public Mono<Court> save(Court court) {
        return springRepository.save(toEntity(court))
            .doOnNext(saved -> court.setVersion(saved.getVersion()))
            .thenReturn(court)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new GroundDomainException(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                "Court " + court.getId() + " was modified concurrently. Reload and retry."))
            .onErrorMap(DataIntegrityViolationException.class, e -> String.valueOf(e.getMessage()).contains("uq_courts_venue_name")
                ? new GroundDomainException(GroundErrorCode.DUPLICATE_COURT_NAME, "This venue already has a court with that name")
                : new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "The court could not be saved"));
    }

    private static Court toDomain(Row row) {
        var ids = row.get("sport_ids", UUID[].class);
        var spec = new Court.Spec(row.get("name", String.class), row.get("surface", String.class), ids == null ? List.of() : Arrays.asList(ids),
            row.get("slot_minutes", Integer.class), row.get("base_price", java.math.BigDecimal.class), row.get("weekend_price", java.math.BigDecimal.class),
            row.get("peak_price", java.math.BigDecimal.class), row.get("peak_from", LocalTime.class), row.get("peak_to", LocalTime.class));
        var hours = new Court.Hours(row.get("opens_at", LocalTime.class), row.get("closes_at", LocalTime.class), ZoneId.of(row.get("time_zone", String.class)));
        return Court.reconstitute(row.get("id", UUID.class), row.get("venue_id", UUID.class), spec, hours, Boolean.TRUE.equals(row.get("active", Boolean.class)),
            row.get("version", Integer.class), row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
    }

    static CourtEntity toEntity(Court c) {
        var s = c.getSpec();
        var e = new CourtEntity();
        e.setId(c.getId());
        e.setVenueId(c.getVenueId());
        e.setName(s.name());
        e.setSurface(s.surface());
        e.setSportIds(s.sportIds().toArray(UUID[]::new));
        e.setSlotMinutes(s.slotMinutes());
        e.setBasePrice(s.basePrice());
        e.setWeekendPrice(s.weekendPrice());
        e.setPeakPrice(s.peakPrice());
        e.setPeakFrom(s.peakFrom());
        e.setPeakTo(s.peakTo());
        e.setActive(c.isActive());
        e.setVersion(c.getVersion());
        e.setCreatedAt(c.getCreatedAt());
        e.setUpdatedAt(c.getUpdatedAt());
        return e;
    }
}
