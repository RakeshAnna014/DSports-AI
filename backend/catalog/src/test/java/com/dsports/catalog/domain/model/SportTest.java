package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.SportArchivedEvent;
import com.dsports.catalog.domain.event.SportCreatedEvent;
import com.dsports.catalog.domain.event.SportUpdatedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SportTest {

    @Test
    void shouldCreateSport() {
        var sport = Sport.create(SportName.from("Cricket"), Slug.from("cricket"), "A bat-and-ball sport");

        assertThat(sport.getId()).isNotNull();
        assertThat(sport.getName().value()).isEqualTo("Cricket");
        assertThat(sport.getSlug().value()).isEqualTo("cricket");
        assertThat(sport.getDescription()).isEqualTo("A bat-and-ball sport");
        assertThat(sport.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(sport.getVersion()).isZero();
        assertThat(sport.getDomainEvents()).hasSize(1);
        assertThat(sport.getDomainEvents().get(0)).isInstanceOf(SportCreatedEvent.class);
    }

    @Test
    void shouldReconstituteSport() {
        var id = SportId.generate();
        var now = java.time.Instant.now();
        var sport = Sport.reconstitute(id, SportName.from("Football"), Slug.from("football"),
                "A team sport", Status.ACTIVE, now, now, 5);

        assertThat(sport.getId()).isEqualTo(id);
        assertThat(sport.getVersion()).isEqualTo(5);
        assertThat(sport.getDomainEvents()).isEmpty();
    }

    @Test
    void shouldUpdateSport() {
        var sport = Sport.create(SportName.from("Cricket"), Slug.from("cricket"), "Old desc");
        sport.clearDomainEvents();

        sport.update(SportName.from("Cricket Updated"), Slug.from("cricket-updated"), "New desc");

        assertThat(sport.getName().value()).isEqualTo("Cricket Updated");
        assertThat(sport.getSlug().value()).isEqualTo("cricket-updated");
        assertThat(sport.getDescription()).isEqualTo("New desc");
        assertThat(sport.getDomainEvents()).hasSize(1);
        assertThat(sport.getDomainEvents().get(0)).isInstanceOf(SportUpdatedEvent.class);
    }

    @Test
    void shouldArchiveSport() {
        var sport = Sport.create(SportName.from("Cricket"), Slug.from("cricket"), null);
        sport.clearDomainEvents();

        sport.archive();

        assertThat(sport.getStatus()).isEqualTo(Status.ARCHIVED);
        assertThat(sport.getDomainEvents()).hasSize(1);
        assertThat(sport.getDomainEvents().get(0)).isInstanceOf(SportArchivedEvent.class);
    }

    @Test
    void shouldThrowWhenArchivingAlreadyArchived() {
        var sport = Sport.create(SportName.from("Cricket"), Slug.from("cricket"), null);
        sport.archive();

        assertThatThrownBy(sport::archive)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already archived");
    }

    @Test
    void shouldThrowWhenUpdatingArchived() {
        var sport = Sport.create(SportName.from("Cricket"), Slug.from("cricket"), null);
        sport.archive();

        assertThatThrownBy(() -> sport.update(SportName.from("New"), Slug.from("new"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archived");
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> SportName.from(null))
                .isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> SportName.from("  "))
                .isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
    }

    @Test
    void shouldRejectNullSlug() {
        assertThatThrownBy(() -> Slug.from(null))
                .isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
    }

    @Test
    void shouldRejectBlankSlug() {
        assertThatThrownBy(() -> Slug.from("  "))
                .isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
    }

    @Test
    void shouldHaveValueEqualityById() {
        var id = SportId.generate();
        var sport1 = Sport.reconstitute(id, SportName.from("A"), Slug.from("a"), null, Status.ACTIVE,
                java.time.Instant.now(), java.time.Instant.now(), 0);
        var sport2 = Sport.reconstitute(id, SportName.from("B"), Slug.from("b"), null, Status.ACTIVE,
                java.time.Instant.now(), java.time.Instant.now(), 0);

        assertThat(sport1).isEqualTo(sport2);
        assertThat(sport1.hashCode()).isEqualTo(sport2.hashCode());
    }
}
