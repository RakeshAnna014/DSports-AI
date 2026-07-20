package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.CategoryArchivedEvent;
import com.dsports.catalog.domain.event.CategoryCreatedEvent;
import com.dsports.catalog.domain.event.CategoryUpdatedEvent;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Category {

    private final CategoryId id;
    private CategoryName name;
    private Slug slug;
    private String description;
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Category(CategoryId id, CategoryName name, Slug slug, String description, Status status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.slug = Objects.requireNonNull(slug, "slug must not be null");
        this.description = description;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.version = 0;
    }

    public static Category create(CategoryName name, Slug slug, String description) {
        var category = new Category(CategoryId.generate(), name, slug, description, Status.ACTIVE);
        category.recordEvent(new CategoryCreatedEvent(category.id, category.name, category.slug));
        return category;
    }

    public static Category reconstitute(CategoryId id, CategoryName name, Slug slug, String description,
                                         Status status, Instant createdAt, Instant updatedAt, int version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(status, "status must not be null");
        var category = new Category(id, name, slug, description, status);
        category.createdAt = createdAt;
        category.updatedAt = updatedAt;
        category.version = version;
        return category;
    }

    public void update(CategoryName name, Slug slug, String description) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Cannot update an archived category");
        }
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.updatedAt = Instant.now();
        recordEvent(new CategoryUpdatedEvent(this.id, this.name, this.slug));
    }

    public void archive() {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Category is already archived");
        }
        this.status = this.status.transitionTo(Status.ARCHIVED);
        this.updatedAt = Instant.now();
        recordEvent(new CategoryArchivedEvent(this.id));
    }

    public CategoryId getId() { return id; }
    public CategoryName getName() { return name; }
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
        if (!(o instanceof Category category)) return false;
        return Objects.equals(id, category.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Category{id=" + id + ", name=" + name + ", status=" + status + "}";
    }
}
