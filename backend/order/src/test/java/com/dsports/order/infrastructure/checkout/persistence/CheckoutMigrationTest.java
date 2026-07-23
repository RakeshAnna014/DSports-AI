package com.dsports.order.infrastructure.checkout.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CheckoutMigrationTest {

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
    void shouldHaveCheckoutsTable() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.getMetaData().getTables(null, null, "checkouts", new String[]{"TABLE"});
            assertThat(stmt.next()).isTrue();
        }
    }

    @Test
    void shouldHaveCheckoutItemsTable() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.getMetaData().getTables(null, null, "checkout_items", new String[]{"TABLE"});
            assertThat(stmt.next()).isTrue();
        }
    }

    @Test
    void shouldHaveRequiredColumns() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var meta = conn.getMetaData();
            var cols = meta.getColumns(null, null, "checkouts", null);
            boolean hasDiscountAmount = false;
            boolean hasVersion = false;
            boolean hasStatus = false;
            while (cols.next()) {
                var name = cols.getString("COLUMN_NAME");
                if ("discount_amount".equals(name)) hasDiscountAmount = true;
                if ("version".equals(name)) hasVersion = true;
                if ("status".equals(name)) hasStatus = true;
            }
            assertThat(hasDiscountAmount).isTrue();
            assertThat(hasVersion).isTrue();
            assertThat(hasStatus).isTrue();
        }
    }

    @Test
    void shouldHaveCheckoutItemsForeignKey() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var meta = conn.getMetaData();
            var fks = meta.getImportedKeys(null, null, "checkout_items");
            assertThat(fks.next()).isTrue();
            assertThat(fks.getString("PKTABLE_NAME")).isEqualTo("checkouts");
            assertThat(fks.getString("FKCOLUMN_NAME")).isEqualTo("checkout_id");
        }
    }

    @Test
    void shouldHaveIndexes() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'checkouts'");
            boolean hasCustomerIdIndex = false;
            boolean hasStatusIndex = false;
            while (rs.next()) {
                var name = rs.getString("indexname");
                if ("idx_checkouts_customer_id".equals(name)) hasCustomerIdIndex = true;
                if ("idx_checkouts_status".equals(name)) hasStatusIndex = true;
            }
            assertThat(hasCustomerIdIndex).isTrue();
            assertThat(hasStatusIndex).isTrue();
        }
    }

    @Test
    void shouldAllowValidCheckoutInsert() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var stmt = conn.createStatement();
            var checkoutId = java.util.UUID.randomUUID();
            var customerId = java.util.UUID.randomUUID();
            var cartId = java.util.UUID.randomUUID();
            var count = stmt.executeUpdate(
                "INSERT INTO checkouts (id, customer_id, cart_id, status, subtotal, tax_amount, " +
                "delivery_charge, discount_amount, total_amount, currency, expires_at, created_at, updated_at) " +
                "VALUES ('" + checkoutId + "', '" + customerId + "', '" + cartId + "', " +
                "'PENDING', 100.00, 18.00, 5.00, 0.00, 123.00, 'INR', NOW(), NOW(), NOW())");
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void shouldEnforceCheckoutItemQuantityCheck() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, postgres.getUsername(), postgres.getPassword())) {
            var checkoutId = java.util.UUID.randomUUID();
            var customerId = java.util.UUID.randomUUID();
            var cartId = java.util.UUID.randomUUID();
            conn.createStatement().executeUpdate(
                "INSERT INTO checkouts (id, customer_id, cart_id, status, subtotal, tax_amount, " +
                "delivery_charge, discount_amount, total_amount, currency, expires_at, created_at, updated_at) " +
                "VALUES ('" + checkoutId + "', '" + customerId + "', '" + cartId + "', " +
                "'PENDING', 0, 0, 0, 0, 0, 'INR', NOW(), NOW(), NOW())");

            var itemId = java.util.UUID.randomUUID();
            var productId = java.util.UUID.randomUUID();
            var rs = conn.createStatement().executeQuery(
                "INSERT INTO checkout_items (id, checkout_id, product_id, product_name, sku, " +
                "quantity, unit_price, line_total, created_at) " +
                "VALUES ('" + itemId + "', '" + checkoutId + "', '" + productId + "', " +
                "'Test', 'TST', 0, 10.00, 0, NOW()) RETURNING id");
            assertThat(rs.next()).isFalse();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("quantity");
        }
    }
}
