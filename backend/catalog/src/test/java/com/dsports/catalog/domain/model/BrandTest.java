package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.event.BrandArchivedEvent;
import com.dsports.catalog.domain.event.BrandCreatedEvent;
import com.dsports.catalog.domain.event.BrandUpdatedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BrandTest {

    @Test
    void shouldCreateBrand() {
        var brand = Brand.create(BrandName.from("MRF"), Slug.from("mrf"), "Cricket bat manufacturer");

        assertThat(brand.getId()).isNotNull();
        assertThat(brand.getName().value()).isEqualTo("MRF");
        assertThat(brand.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(brand.getDomainEvents()).hasSize(1);
        assertThat(brand.getDomainEvents().get(0)).isInstanceOf(BrandCreatedEvent.class);
    }

    @Test
    void shouldUpdateBrand() {
        var brand = Brand.create(BrandName.from("MRF"), Slug.from("mrf"), null);
        brand.clearDomainEvents();

        brand.update(BrandName.from("MRF Updated"), Slug.from("mrf-updated"), "Updated desc");

        assertThat(brand.getDomainEvents()).hasSize(1);
        assertThat(brand.getDomainEvents().get(0)).isInstanceOf(BrandUpdatedEvent.class);
    }

    @Test
    void shouldArchiveBrand() {
        var brand = Brand.create(BrandName.from("MRF"), Slug.from("mrf"), null);
        brand.clearDomainEvents();
        brand.archive();

        assertThat(brand.getStatus()).isEqualTo(Status.ARCHIVED);
        assertThat(brand.getDomainEvents()).hasSize(1);
        assertThat(brand.getDomainEvents().get(0)).isInstanceOf(BrandArchivedEvent.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> BrandName.from("  "))
                .isInstanceOf(com.dsports.catalog.domain.exception.CatalogDomainException.class);
    }
}
