package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.application.port.CategoryRepository;
import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.Category;
import com.dsports.catalog.domain.model.CategoryId;
import com.dsports.catalog.domain.model.CategoryName;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.infrastructure.persistence.entity.CategoryEntity;
import com.dsports.catalog.infrastructure.persistence.mapper.CatalogEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class CategoryR2dbcRepositoryAdapter implements CategoryRepository {

    private final DatabaseClient databaseClient;
    private final CatalogEntityMapper mapper;
    private final SpringR2dbcCategoryRepository springRepository;
    private final EventPublisher eventPublisher;

    public CategoryR2dbcRepositoryAdapter(DatabaseClient databaseClient, CatalogEntityMapper mapper,
                                           SpringR2dbcCategoryRepository springRepository,
                                           EventPublisher eventPublisher) {
        this.databaseClient = databaseClient;
        this.mapper = mapper;
        this.springRepository = springRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<Category> findById(CategoryId id) {
        return springRepository.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Category> findByName(CategoryName name) {
        return databaseClient.sql("SELECT * FROM categories WHERE name = :name")
                .bind("name", name.value())
                .map((row, metadata) -> mapEntity(row))
                .one()
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Boolean> existsByName(CategoryName name) {
        return databaseClient.sql("SELECT COUNT(*) FROM categories WHERE name = :name")
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
        return databaseClient.sql("SELECT COUNT(*) FROM categories WHERE slug = :slug")
                .bind("slug", slug.value())
                .map((row, metadata) -> {
                    var count = row.get("count", Long.class);
                    return count != null && count > 0;
                })
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<Category> findAllActive() {
        return databaseClient.sql("SELECT * FROM categories WHERE status = 'ACTIVE'")
                .map((row, metadata) -> mapper.toDomain(mapEntity(row)))
                .all();
    }

    @Override
    public Flux<Category> findAll() {
        return springRepository.findAll()
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Void> save(Category category) {
        var entity = mapper.toEntity(category);
        return springRepository.save(entity)
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                        new CatalogDomainException(CatalogErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                "Category was modified by another request. Please retry.",
                                java.util.Map.of("categoryId", entity.getId().toString())))
                .onErrorMap(DataIntegrityViolationException.class, e -> {
                    var msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (msg.contains("uq_categories_name")) {
                        return new CatalogDomainException(CatalogErrorCode.DUPLICATE_CATEGORY_NAME,
                                "Category with name '" + category.getName().value() + "' already exists");
                    }
                    if (msg.contains("uq_categories_slug")) {
                        return new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                "Category with slug '" + category.getSlug().value() + "' already exists");
                    }
                    return new CatalogDomainException(CatalogErrorCode.GENERIC,
                            "Data integrity violation: " + e.getMessage());
                })
                .then(Mono.defer(() -> {
                    var events = category.getDomainEvents();
                    category.clearDomainEvents();
                    return Flux.fromIterable(events)
                            .flatMap(event -> Mono.fromRunnable(() -> eventPublisher.publish(event)))
                            .then();
                }));
    }

    private CategoryEntity mapEntity(io.r2dbc.spi.Row row) {
        var entity = new CategoryEntity();
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
