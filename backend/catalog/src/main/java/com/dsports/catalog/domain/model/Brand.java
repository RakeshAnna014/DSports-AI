package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.BrandArchivedEvent;
import com.dsports.catalog.domain.event.BrandCreatedEvent;
import com.dsports.catalog.domain.event.BrandUpdatedEvent;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Brand {

    private final BrandId id;
    private BrandName name;
    private Slug slug;
    private String description;
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Brand(BrandId id, BrandName name, Slug slug, String description, Status status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.slug = Objects.requireNonNull(slug, "slug must not be null");
        this.description = description;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.version = 0;
    }

    public static Brand create(BrandName name, Slug slug, String description) {
        var brand = new Brand(BrandId.generate(), name, slug, description, Status.ACTIVE);
        brand.recordEvent(new BrandCreatedEvent(brand.id, brand.name, brand.slug));
        return brand;
    }

    public static Brand reconstitute(BrandId id, BrandName name, Slug slug, String description,
                                      Status status, Instant createdAt, Instant updatedAt, int version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(status, "status must not be null");
        var brand = new Brand(id, name, slug, description, status);
        brand.createdAt = createdAt;
        brand.updatedAt = updatedAt;
        brand.version = version;
        return brand;
    }

    public void update(BrandName name, Slug slug, String description) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Cannot update an archived brand");
        }
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.updatedAt = Instant.now();
        recordEvent(new BrandUpdatedEvent(this.id, this.name, this.slug));
    }

    public void archive() {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Brand is already archived");
        }
        this.status = this.status.transitionTo(Status.ARCHIVED);
        this.updatedAt = Instant.now();
        recordEvent(new BrandArchivedEvent(this.id));
    }

    public BrandId getId() { return id; }
    public BrandName getName() { return name; }
    public Slug getSlug() { return slug; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }

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
        if (!(o instanceof Brand brand)) return false;
        return Objects.equals(id, brand.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Brand{id=" + id + ", name=" + name + ", status=" + status + "}";
    }
}
