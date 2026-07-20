package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.catalog.application.port.SportRepository;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.Sport;
import com.dsports.catalog.domain.model.SportId;
import com.dsports.catalog.domain.model.SportName;
import com.dsports.catalog.infrastructure.persistence.mapper.CatalogEntityMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class SportR2dbcRepositoryAdapter implements SportRepository {

    private final DatabaseClient databaseClient;
    private final CatalogEntityMapper mapper;
    private final SpringR2dbcSportRepository springRepository;
    private final EventPublisher eventPublisher;

    public SportR2dbcRepositoryAdapter(DatabaseClient databaseClient, CatalogEntityMapper mapper,
                                        SpringR2dbcSportRepository springRepository,
                                        EventPublisher eventPublisher) {
        this.databaseClient = databaseClient;
        this.mapper = mapper;
        this.springRepository = springRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<Sport> findById(SportId id) {
        return springRepository.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Sport> findByName(SportName name) {
        return databaseClient.sql("SELECT * FROM sports WHERE name = :name")
                .bind("name", name.value())
                .map((row, metadata) -> mapEntity(row))
                .one()
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Boolean> existsByName(SportName name) {
        return databaseClient.sql("SELECT COUNT(*) FROM sports WHERE name = :name")
                .bind("name", name.value())
                .map((row, metadata) -> {
                    var count = row.get("count", Long.class);
                    return count != null && count > 0;
                })
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> existsBySlug(Slug slug) {
        return databaseClient.sql("SELECT COUNT(*) FROM sports WHERE slug = :slug")
                .bind("slug", slug.value())
                .map((row, metadata) -> {
                    var count = row.get("count", Long.class);
                    return count != null && count > 0;
                })
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<Sport> findAllActive() {
        return databaseClient.sql("SELECT * FROM sports WHERE status = 'ACTIVE'")
                .map((row, metadata) -> mapper.toDomain(mapEntity(row)))
                .all();
    }

    @Override
    public Mono<Void> save(Sport sport) {
        var entity = mapper.toEntity(sport);
        return springRepository.save(entity)
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                        new CatalogDomainException(CatalogErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                "Sport was modified by another request. Please retry.",
                                java.util.Map.of("sportId", entity.getId().toString())))
                .then(Mono.fromRunnable(() -> {
                    sport.getDomainEvents().forEach(eventPublisher::publish);
                    sport.clearDomainEvents();
                }));
    }

    private com.dsports.catalog.infrastructure.persistence.entity.SportEntity mapEntity(io.r2dbc.spi.Row row) {
        var entity = new com.dsports.catalog.infrastructure.persistence.entity.SportEntity();
        entity.setId(row.get("id", UUID.class));
        entity.setName(row.get("name", String.class));
        entity.setSlug(row.get("slug", String.class));
        entity.setDescription(row.get("description", String.class));
        entity.setStatus(row.get("status", String.class));
        entity.setCreatedAt(row.get("created_at", java.time.Instant.class));
        entity.setUpdatedAt(row.get("updated_at", java.time.Instant.class));
        entity.setVersion(row.get("version", Integer.class));
        return entity;
    }
}
