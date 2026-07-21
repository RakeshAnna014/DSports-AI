package com.dsports.pricing.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PriceMigrationTest {

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
    void shouldHavePricesTable() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.getMetaData().getTables(null, null, "prices", new String[]{"TABLE"});
            assertThat(stmt.next()).isTrue();
        }
    }

    @Test
    void shouldEnforceMrpCheckConstraint() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                            "VALUES (gen_random_uuid(), gen_random_uuid(), -1, 0, 'INR', NOW(), 'DRAFT')"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("ck_price_mrp_positive");
        }
    }

    @Test
    void shouldEnforceSellingPriceCheckConstraint() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                            "VALUES (gen_random_uuid(), gen_random_uuid(), 100, -1, 'INR', NOW(), 'DRAFT')"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("ck_price_selling_positive");
        }
    }

    @Test
    void shouldEnforceSellingNotExceedMrp() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                            "VALUES (gen_random_uuid(), gen_random_uuid(), 100, 150, 'INR', NOW(), 'DRAFT')"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("ck_price_selling_not_exceed_mrp");
        }
    }

    @Test
    void shouldEnforceCurrencyFormat() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                            "VALUES (gen_random_uuid(), gen_random_uuid(), 100, 80, 'inr', NOW(), 'DRAFT')"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("ck_price_currency_format");
        }
    }

    @Test
    void shouldEnforceStatusValid() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                            "VALUES (gen_random_uuid(), gen_random_uuid(), 100, 80, 'INR', NOW(), 'INVALID')"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("ck_price_status_valid");
        }
    }

    @Test
    void shouldAllowValidInsert() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            var productId = java.util.UUID.randomUUID();
            var count = stmt.executeUpdate("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                    "VALUES (gen_random_uuid(), '" + productId + "', 100, 80, 'INR', NOW(), 'DRAFT')");
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void shouldEnforceUniqueActivePricePerProductAndCurrency() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            var productId = java.util.UUID.randomUUID();
            stmt.execute("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                    "VALUES (gen_random_uuid(), '" + productId + "', 100, 80, 'INR', NOW(), 'ACTIVE')");
            assertThatThrownBy(() ->
                    stmt.execute("INSERT INTO prices (id, product_id, mrp, selling_price, currency, effective_from, status) " +
                            "VALUES (gen_random_uuid(), '" + productId + "', 100, 80, 'INR', NOW(), 'ACTIVE')"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("uq_price_product_currency_active");
        }
    }
}
