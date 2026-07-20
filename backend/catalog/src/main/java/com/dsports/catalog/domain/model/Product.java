package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.PrimaryImageChangedEvent;
import com.dsports.catalog.domain.event.ProductArchivedEvent;
import com.dsports.catalog.domain.event.ProductCreatedEvent;
import com.dsports.catalog.domain.event.ProductImageAddedEvent;
import com.dsports.catalog.domain.event.ProductImageRemovedEvent;
import com.dsports.catalog.domain.event.ProductUpdatedEvent;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Product {

    private static final int MAX_IMAGES = 20;

    private final ProductId id;
    private SKU sku;
    private ProductName name;
    private Slug slug;
    private ProductDescription description;
    private BrandId brandId;
    private CategoryId categoryId;
    private SportId sportId;
    private Weight weight;
    private Dimensions dimensions;
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final List<ProductImage> images = new ArrayList<>();
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Product(ProductId id, SKU sku, ProductName name, Slug slug, ProductDescription description,
                    BrandId brandId, CategoryId categoryId, SportId sportId,
                    Weight weight, Dimensions dimensions, Status status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.slug = Objects.requireNonNull(slug, "slug must not be null");
        this.description = description;
        this.brandId = Objects.requireNonNull(brandId, "brandId must not be null");
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId must not be null");
        this.sportId = Objects.requireNonNull(sportId, "sportId must not be null");
        this.weight = weight;
        this.dimensions = dimensions;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.version = 0;
    }

    public static Product create(SKU sku, ProductName name, Slug slug, ProductDescription description,
                                  BrandId brandId, CategoryId categoryId, SportId sportId,
                                  Weight weight, Dimensions dimensions) {
        var product = new Product(ProductId.generate(), sku, name, slug, description,
                brandId, categoryId, sportId, weight, dimensions, Status.ACTIVE);
        product.recordEvent(new ProductCreatedEvent(product.id, product.sku, product.name));
        return product;
    }

    public static Product reconstitute(ProductId id, SKU sku, ProductName name, Slug slug,
                                        ProductDescription description,
                                        BrandId brandId, CategoryId categoryId, SportId sportId,
                                        Weight weight, Dimensions dimensions,
                                        Status status, Instant createdAt, Instant updatedAt,
                                        int version, List<ProductImage> images) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(brandId, "brandId must not be null");
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(sportId, "sportId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        var product = new Product(id, sku, name, slug, description,
                brandId, categoryId, sportId, weight, dimensions, status);
        product.createdAt = createdAt;
        product.updatedAt = updatedAt;
        product.version = version;
        if (images != null) {
            product.images.addAll(images);
        }
        return product;
    }

    public void update(SKU sku, ProductName name, Slug slug, ProductDescription description,
                        BrandId brandId, CategoryId categoryId, SportId sportId,
                        Weight weight, Dimensions dimensions) {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Cannot update an archived product");
        }
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.slug = Objects.requireNonNull(slug, "slug must not be null");
        this.description = description;
        this.brandId = Objects.requireNonNull(brandId, "brandId must not be null");
        this.categoryId = Objects.requireNonNull(categoryId, "categoryId must not be null");
        this.sportId = Objects.requireNonNull(sportId, "sportId must not be null");
        this.weight = weight;
        this.dimensions = dimensions;
        this.updatedAt = Instant.now();
        recordEvent(new ProductUpdatedEvent(this.id, this.name, this.sku));
    }

    public void archive() {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Product is already archived");
        }
        this.status = this.status.transitionTo(Status.ARCHIVED);
        this.updatedAt = Instant.now();
        recordEvent(new ProductArchivedEvent(this.id));
    }

    public void addImage(ProductImageUrl url, int displayOrder, boolean primary) {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Cannot add images to an archived product");
        }
        if (images.size() >= MAX_IMAGES) {
            throw new IllegalStateException("Maximum of " + MAX_IMAGES + " images allowed");
        }
        if (primary) {
            clearPrimaryFlag();
        }
        var image = ProductImage.create(url, displayOrder, primary);
        images.add(image);
        recordEvent(new ProductImageAddedEvent(this.id, image.getId(), url));
        if (primary) {
            recordEvent(new PrimaryImageChangedEvent(this.id, image.getId()));
        }
    }

    public void removeImage(ProductImageId imageId) {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Cannot remove images from an archived product");
        }
        var removed = images.stream()
                .filter(img -> img.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Image not found: " + imageId));
        images.remove(removed);
        recordEvent(new ProductImageRemovedEvent(this.id, imageId));
    }

    public void changePrimaryImage(ProductImageId imageId) {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Cannot change primary image of an archived product");
        }
        var exists = images.stream().anyMatch(img -> img.getId().equals(imageId));
        if (!exists) {
            throw new IllegalArgumentException("Image not found: " + imageId);
        }
        clearPrimaryFlag();
        images.stream()
                .filter(img -> img.getId().equals(imageId))
                .findFirst()
                .ifPresent(img -> img.setPrimary(true));
        recordEvent(new PrimaryImageChangedEvent(this.id, imageId));
    }

    private void clearPrimaryFlag() {
        images.forEach(img -> img.setPrimary(false));
    }

    public ProductId getId() { return id; }
    public SKU getSku() { return sku; }
    public ProductName getName() { return name; }
    public Slug getSlug() { return slug; }
    public ProductDescription getDescription() { return description; }
    public BrandId getBrandId() { return brandId; }
    public CategoryId getCategoryId() { return categoryId; }
    public SportId getSportId() { return sportId; }
    public Weight getWeight() { return weight; }
    public Dimensions getDimensions() { return dimensions; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
    public List<ProductImage> getImages() { return List.copyOf(images); }

    public List<DomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    private void recordEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product product)) return false;
        return Objects.equals(id, product.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Product{id=" + id + ", sku=" + sku + ", name=" + name + ", status=" + status + "}";
    }
}
