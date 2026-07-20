package com.dsports.catalog.domain.model;

import com.dsports.catalog.domain.exception.CatalogDomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SlugTest {

    @Test
    void shouldCreateValidSlug() {
        var slug = Slug.from("cricket-bat");
        assertThat(slug.value()).isEqualTo("cricket-bat");
    }

    @Test
    void shouldRejectNull() {
        assertThatThrownBy(() -> Slug.from(null))
                .isInstanceOf(CatalogDomainException.class);
    }

    @Test
    void shouldRejectBlank() {
        assertThatThrownBy(() -> Slug.from("  "))
                .isInstanceOf(CatalogDomainException.class);
    }

    @Test
    void shouldImplementValueEquality() {
        var slug1 = Slug.from("test-slug");
        var slug2 = Slug.from("test-slug");
        assertThat(slug1).isEqualTo(slug2);
        assertThat(slug1.hashCode()).isEqualTo(slug2.hashCode());
    }
}
