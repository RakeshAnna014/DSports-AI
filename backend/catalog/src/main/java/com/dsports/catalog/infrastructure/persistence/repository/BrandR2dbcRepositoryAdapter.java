package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.application.port.BrandRepository;
import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.Brand;
import com.dsports.catalog.domain.model.BrandId;
import com.dsports.catalog.domain.model.BrandName;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.infrastructure.persistence.entity.BrandEntity;
import com.dsports.catalog.infrastructure.persistence.mapper.CatalogEntityMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class BrandR2dbcRepositoryAdapter implements BrandRepository {

    private final DatabaseClient databaseClient;
    private final CatalogEntityMapper mapper;
    private final SpringR2dbcBrandRepository springRepository;
    private final EventPublisher eventPublisher;

    public BrandR2dbcRepositoryAdapter(DatabaseClient databaseClient, CatalogEntityMapper mapper,
                                        SpringR2dbcBrandRepository springRepository,
                                        EventPublisher eventPublisher) {
        this.databaseClient = databaseClient;
        this.mapper = mapper;
        this.springRepository = springRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<Brand> findById(BrandId id) {
        return springRepository.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Brand> findByName(BrandName name) {
        return databaseClient.sql("SELECT * FROM brands WHERE name = :name")
                .bind("name", name.value())
                .map((row, metadata) -> mapEntity(row))
                .one()
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Boolean> existsByName(BrandName name) {
        return databaseClient.sql("SELECT COUNT(*) FROM brands WHERE name = :name")
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
        return databaseClient.sql("SELECT COUNT(*) FROM brands WHERE slug = :slug")
                .bind("slug", slug.value())
                .map((row, metadata) -> {
                    var count = row.get("count", Long.class);
                    return count != null && count > 0;
                })
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<Brand> findAllActive() {
        return databaseClient.sql("SELECT * FROM brands WHERE status = 'ACTIVE'")
                .map((row, metadata) -> mapper.toDomain(mapEntity(row)))
                .all();
    }

    @Override
    public Mono<Void> save(Brand brand) {
        var entity = mapper.toEntity(brand);
        return springRepository.save(entity)
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                        new CatalogDomainException(CatalogErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                "Brand was modified by another request. Please retry.",
                                java.util.Map.of("brandId", entity.getId().toString())))
                .then(Mono.fromRunnable(() -> {
                    brand.getDomainEvents().forEach(eventPublisher::publish);
                    brand.clearDomainEvents();
                }));
    }

    private BrandEntity mapEntity(io.r2dbc.spi.Row row) {
        var entity = new BrandEntity();
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
