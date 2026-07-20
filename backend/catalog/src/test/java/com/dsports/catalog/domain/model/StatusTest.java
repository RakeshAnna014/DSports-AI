package com.dsports.catalog.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StatusTest {

    @Test
    void shouldBeActiveByDefault() {
        assertThat(Status.ACTIVE.isActive()).isTrue();
        assertThat(Status.ACTIVE.isArchived()).isFalse();
    }

    @Test
    void shouldBeArchived() {
        assertThat(Status.ARCHIVED.isArchived()).isTrue();
        assertThat(Status.ARCHIVED.isActive()).isFalse();
    }

    @Test
    void shouldTransitionFromActiveToArchived() {
        var result = Status.ACTIVE.transitionTo(Status.ARCHIVED);
        assertThat(result).isEqualTo(Status.ARCHIVED);
    }

    @Test
    void shouldThrowWhenTransitioningFromArchived() {
        assertThatThrownBy(() -> Status.ARCHIVED.transitionTo(Status.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
    }
}
