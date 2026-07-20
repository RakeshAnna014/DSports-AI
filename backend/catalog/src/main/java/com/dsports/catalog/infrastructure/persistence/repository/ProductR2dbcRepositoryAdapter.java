package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.application.port.EventPublisher;
import com.dsports.catalog.application.port.ProductFilter;
import com.dsports.catalog.application.port.ProductRepository;
import com.dsports.catalog.domain.exception.CatalogDomainException;
import com.dsports.catalog.domain.exception.CatalogErrorCode;
import com.dsports.catalog.domain.model.*;
import com.dsports.catalog.infrastructure.persistence.entity.ProductEntity;
import com.dsports.catalog.infrastructure.persistence.entity.ProductImageEntity;
import com.dsports.catalog.infrastructure.persistence.mapper.ProductEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class ProductR2dbcRepositoryAdapter implements ProductRepository {

    private final DatabaseClient databaseClient;
    private final ProductEntityMapper mapper;
    private final SpringR2dbcProductRepository springRepository;
    private final SpringR2dbcProductImageRepository imageRepository;
    private final EventPublisher eventPublisher;

    public ProductR2dbcRepositoryAdapter(DatabaseClient databaseClient, ProductEntityMapper mapper,
                                          SpringR2dbcProductRepository springRepository,
                                          SpringR2dbcProductImageRepository imageRepository,
                                          EventPublisher eventPublisher) {
        this.databaseClient = databaseClient;
        this.mapper = mapper;
        this.springRepository = springRepository;
        this.imageRepository = imageRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<Product> findById(ProductId id) {
        return springRepository.findById(id.value())
                .flatMap(entity -> loadImages(entity)
                        .map(images -> mapper.toDomain(entity, images)));
    }

    @Override
    public Mono<Product> findBySku(SKU sku) {
        return databaseClient.sql("SELECT * FROM products WHERE sku = :sku")
                .bind("sku", sku.value())
                .map((row, metadata) -> mapEntity(row))
                .one()
                .flatMap(entity -> loadImages(entity)
                        .map(images -> mapper.toDomain(entity, images)));
    }

    @Override
    public Mono<Product> findBySlug(Slug slug) {
        return databaseClient.sql("SELECT * FROM products WHERE slug = :slug")
                .bind("slug", slug.value())
                .map((row, metadata) -> mapEntity(row))
                .one()
                .flatMap(entity -> loadImages(entity)
                        .map(images -> mapper.toDomain(entity, images)));
    }

    @Override
    public Mono<Boolean> existsBySku(SKU sku) {
        return databaseClient.sql("SELECT COUNT(*) FROM products WHERE sku = :sku")
                .bind("sku", sku.value())
                .map((row, metadata) -> {
                    var count = row.get("count", Long.class);
                    return count != null && count > 0;
                })
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> existsBySlug(Slug slug) {
        return databaseClient.sql("SELECT COUNT(*) FROM products WHERE slug = :slug")
                .bind("slug", slug.value())
                .map((row, metadata) -> {
                    var count = row.get("count", Long.class);
                    return count != null && count > 0;
                })
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<Product> findAll() {
        return springRepository.findAll()
                .flatMap(entity -> loadImages(entity)
                        .map(images -> mapper.toDomain(entity, images)));
    }

    @Override
    public Flux<Product> findAll(ProductFilter filter) {
        var sql = new StringBuilder("SELECT * FROM products WHERE 1=1");

        if (filter.brandId() != null) {
            sql.append(" AND brand_id = :brandId");
        }
        if (filter.categoryId() != null) {
            sql.append(" AND category_id = :categoryId");
        }
        if (filter.sportId() != null) {
            sql.append(" AND sport_id = :sportId");
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND status = :status");
        }

        var sortBy = sanitizeSortColumn(filter.sortBy());
        var sortDir = filter.sortDir().equalsIgnoreCase("asc") ? "ASC" : "DESC";
        sql.append(" ORDER BY ").append(sortBy).append(" ").append(sortDir);

        sql.append(" LIMIT :limit OFFSET :offset");

        var spec = databaseClient.sql(sql.toString());

        if (filter.brandId() != null) {
            spec = spec.bind("brandId", filter.brandId());
        }
        if (filter.categoryId() != null) {
            spec = spec.bind("categoryId", filter.categoryId());
        }
        if (filter.sportId() != null) {
            spec = spec.bind("sportId", filter.sportId());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            spec = spec.bind("status", filter.status());
        }

        return spec
                .bind("limit", filter.size())
                .bind("offset", (long) filter.page() * filter.size())
                .map((row, metadata) -> mapEntity(row))
                .all()
                .flatMap(entity -> loadImages(entity)
                        .map(images -> mapper.toDomain(entity, images)));
    }

    @Override
    public Mono<Void> save(Product product) {
        var entity = mapper.toEntity(product);
        return springRepository.save(entity)
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                        new CatalogDomainException(CatalogErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                "Product was modified by another request. Please retry.",
                                java.util.Map.of("productId", entity.getId().toString())))
                .onErrorMap(DataIntegrityViolationException.class, e -> {
                    var msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (msg.contains("uq_products_sku")) {
                        return new CatalogDomainException(CatalogErrorCode.DUPLICATE_SKU,
                                "Product with SKU '" + product.getSku().value() + "' already exists");
                    }
                    if (msg.contains("uq_products_slug")) {
                        return new CatalogDomainException(CatalogErrorCode.DUPLICATE_SLUG,
                                "Product with slug '" + product.getSlug().value() + "' already exists");
                    }
                    return new CatalogDomainException(CatalogErrorCode.GENERIC,
                            "Data integrity violation: " + e.getMessage());
                })
                .flatMap(savedEntity -> saveImages(savedEntity.getId(), product.getImages())
                        .thenReturn(savedEntity))
                .then(Mono.defer(() -> {
                    var events = product.getDomainEvents();
                    product.clearDomainEvents();
                    return Flux.fromIterable(events)
                            .flatMap(event -> Mono.fromRunnable(() -> eventPublisher.publish(event)))
                            .then();
                }));
    }

    private Mono<Void> saveImages(UUID productId, java.util.List<ProductImage> images) {
        return databaseClient.sql("DELETE FROM product_images WHERE product_id = :productId")
                .bind("productId", productId)
                .then()
                .thenMany(Flux.fromIterable(images))
                .flatMap(image -> {
                    var imageEntity = mapper.toImageEntity(image, productId);
                    return imageRepository.save(imageEntity);
                })
                .then();
    }

    private Mono<java.util.List<ProductImage>> loadImages(ProductEntity entity) {
        return imageRepository.findByProductIdOrderByDisplayOrder(entity.getId())
                .map(mapper::toImageDomain)
                .collectList();
    }

    private String sanitizeSortColumn(String sortBy) {
        return switch (sortBy) {
            case "sku" -> "sku";
            case "name" -> "name";
            case "slug" -> "slug";
            case "created_at" -> "created_at";
            case "updated_at" -> "updated_at";
            case "status" -> "status";
            default -> "created_at";
        };
    }

    private ProductEntity mapEntity(io.r2dbc.spi.Row row) {
        var entity = new ProductEntity();
        entity.setId(row.get("id", UUID.class));
        entity.setSku(row.get("sku", String.class));
        entity.setName(row.get("name", String.class));
        entity.setSlug(row.get("slug", String.class));
        entity.setDescription(row.get("description", String.class));
        entity.setBrandId(row.get("brand_id", UUID.class));
        entity.setCategoryId(row.get("category_id", UUID.class));
        entity.setSportId(row.get("sport_id", UUID.class));
        entity.setWeight(row.get("weight", java.math.BigDecimal.class));
        entity.setWeightUnit(row.get("weight_unit", String.class));
        entity.setLength(row.get("length", java.math.BigDecimal.class));
        entity.setWidth(row.get("width", java.math.BigDecimal.class));
        entity.setHeight(row.get("height", java.math.BigDecimal.class));
        entity.setDimensionUnit(row.get("dimension_unit", String.class));
        entity.setStatus(row.get("status", String.class));
        entity.setCreatedAt(row.get("created_at", java.time.Instant.class));
        entity.setUpdatedAt(row.get("updated_at", java.time.Instant.class));
        entity.setVersion(row.get("version", Integer.class));
        return entity;
    }
}
