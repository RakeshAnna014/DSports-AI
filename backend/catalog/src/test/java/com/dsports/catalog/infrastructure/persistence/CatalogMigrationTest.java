package com.dsports.catalog.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CatalogMigrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;

    @BeforeAll
    static void setUp() {
        try {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("testdb")
                    .withUsername("test")
                    .withPassword("test");
            postgres.start();
        } catch (Exception e) {
            assumeTrue(false, "Docker is required for this test: " + e.getMessage());
            return;
        }

        jdbcUrl = postgres.getJdbcUrl();

        Flyway.configure()
                .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void shouldHaveSportsTable() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.getMetaData().getTables(null, null, "sports", new String[]{"TABLE"});
            assertThat(stmt.next()).isTrue();
        }
    }

    @Test
    void shouldHaveCategoriesTable() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.getMetaData().getTables(null, null, "categories", new String[]{"TABLE"});
            assertThat(stmt.next()).isTrue();
        }
    }

    @Test
    void shouldHaveBrandsTable() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.getMetaData().getTables(null, null, "brands", new String[]{"TABLE"});
            assertThat(stmt.next()).isTrue();
        }
    }

    @Test
    void shouldHaveSeedSports() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM sports");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(6);
        }
    }

    @Test
    void shouldHaveSeedCategories() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM categories");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(8);
        }
    }

    @Test
    void shouldHaveSeedBrands() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM brands");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(10);
        }
    }

    @Test
    void shouldEnforceUniqueSportName() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO sports (id, name, slug, status, created_at, updated_at) " +
                            "VALUES (gen_random_uuid(), 'Cricket', 'another-cricket', 'ACTIVE', NOW(), NOW())"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("uq_sports_name");
        }
    }

    @Test
    void shouldEnforceUniqueSportSlug() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO sports (id, name, slug, status, created_at, updated_at) " +
                            "VALUES (gen_random_uuid(), 'Another Cricket', 'cricket', 'ACTIVE', NOW(), NOW())"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("uq_sports_slug");
        }
    }
}
