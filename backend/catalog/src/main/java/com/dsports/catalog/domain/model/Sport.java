package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.SportArchivedEvent;
import com.dsports.catalog.domain.event.SportCreatedEvent;
import com.dsports.catalog.domain.event.SportUpdatedEvent;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Sport {

    private final SportId id;
    private SportName name;
    private Slug slug;
    private String description;
    private Status status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Sport(SportId id, SportName name, Slug slug, String description, Status status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.slug = Objects.requireNonNull(slug, "slug must not be null");
        this.description = description;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.version = 0;
    }

    public static Sport create(SportName name, Slug slug, String description) {
        var sport = new Sport(SportId.generate(), name, slug, description, Status.ACTIVE);
        sport.recordEvent(new SportCreatedEvent(sport.id, sport.name, sport.slug));
        return sport;
    }

    public static Sport reconstitute(SportId id, SportName name, Slug slug, String description,
                                      Status status, Instant createdAt, Instant updatedAt, int version) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        Objects.requireNonNull(status, "status must not be null");
        var sport = new Sport(id, name, slug, description, status);
        sport.createdAt = createdAt;
        sport.updatedAt = updatedAt;
        sport.version = version;
        return sport;
    }

    public void update(SportName name, Slug slug, String description) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(slug, "slug must not be null");
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Cannot update an archived sport");
        }
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.updatedAt = Instant.now();
        recordEvent(new SportUpdatedEvent(this.id, this.name, this.slug));
    }

    public void archive() {
        if (this.status == Status.ARCHIVED) {
            throw new IllegalStateException("Sport is already archived");
        }
        this.status = this.status.transitionTo(Status.ARCHIVED);
        this.updatedAt = Instant.now();
        recordEvent(new SportArchivedEvent(this.id));
    }

    public SportId getId() { return id; }
    public SportName getName() { return name; }
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
        if (!(o instanceof Sport sport)) return false;
        return Objects.equals(id, sport.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Sport{id=" + id + ", name=" + name + ", status=" + status + "}";
    }
}
