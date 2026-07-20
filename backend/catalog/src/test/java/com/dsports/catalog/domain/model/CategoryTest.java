package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.CategoryArchivedEvent;
import com.dsports.catalog.domain.event.CategoryCreatedEvent;
import com.dsports.catalog.domain.event.CategoryUpdatedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CategoryTest {

    @Test
    void shouldCreateCategory() {
        var cat = Category.create(CategoryName.from("Bat"), Slug.from("bat"), "Cricket bats");

        assertThat(cat.getId()).isNotNull();
        assertThat(cat.getName().value()).isEqualTo("Bat");
        assertThat(cat.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(cat.getDomainEvents()).hasSize(1);
        assertThat(cat.getDomainEvents().get(0)).isInstanceOf(CategoryCreatedEvent.class);
    }

    @Test
    void shouldUpdateCategory() {
        var cat = Category.create(CategoryName.from("Bat"), Slug.from("bat"), null);
        cat.clearDomainEvents();

        cat.update(CategoryName.from("Bat Updated"), Slug.from("bat-updated"), "New desc");

        assertThat(cat.getName().value()).isEqualTo("Bat Updated");
        assertThat(cat.getDomainEvents()).hasSize(1);
        assertThat(cat.getDomainEvents().get(0)).isInstanceOf(CategoryUpdatedEvent.class);
    }

    @Test
    void shouldArchiveCategory() {
        var cat = Category.create(CategoryName.from("Bat"), Slug.from("bat"), null);
        cat.clearDomainEvents();
        cat.archive();

        assertThat(cat.getStatus()).isEqualTo(Status.ARCHIVED);
        assertThat(cat.getDomainEvents()).hasSize(1);
        assertThat(cat.getDomainEvents().get(0)).isInstanceOf(CategoryArchivedEvent.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> CategoryName.from("  "))
                .isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
    }
}
