package com.dsports.catalog.infrastructure.persistence.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product pages must be stable: the catalogue is paged with LIMIT/OFFSET and most products share one created_at, so the sort needs a total order.
 * Without the id tie-breaker the same product could appear on two pages while another was skipped (seen in the browser as duplicate React keys).
 */
class ProductOrderingTest {

    @Test
    void everySortEndsWithTheUniqueIdAsTieBreaker() {
        for (String column : new String[] {"sku", "name", "slug", "created_at", "updated_at", "status"}) {
            for (String direction : new String[] {"ASC", "DESC"}) {
                assertThat(ProductR2dbcRepositoryAdapter.orderByClause(column, direction))
                        .isEqualTo(" ORDER BY " + column + " " + direction + ", id ASC");
            }
        }
    }

    @Test
    void theRequestedSortStaysFirstAndTheTieBreakerIsAlwaysAscending() {
        var clause = ProductR2dbcRepositoryAdapter.orderByClause("created_at", "DESC");
        assertThat(clause.indexOf("created_at DESC")).isLessThan(clause.indexOf("id ASC"));
        assertThat(clause).endsWith(", id ASC");
    }
}
