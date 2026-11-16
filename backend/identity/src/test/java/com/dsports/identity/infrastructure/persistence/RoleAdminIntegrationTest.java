package com.dsports.identity.infrastructure.persistence;

import com.dsports.identity.application.port.RoleAdminRepository.AuditEntry;
import com.dsports.identity.infrastructure.persistence.repository.RoleAdminR2dbcRepositoryAdapter;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The role administration SQL on a real PostgreSQL: roles, case-insensitive email lookup, listing with the grant date, and the audit log. */
@Tag("integration")
class RoleAdminIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DatabaseClient db;
    private static RoleAdminR2dbcRepositoryAdapter roles;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName()));
        roles = new RoleAdminR2dbcRepositoryAdapter(db);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static UUID register(String email, boolean deleted) {
        var id = UUID.randomUUID();
        db.sql("INSERT INTO customers (id, email, first_name, last_name, status, created_at, updated_at, deleted_at) "
                + "VALUES (:id, :email, 'First', 'Last', 'ACTIVE', now(), now(), " + (deleted ? "now()" : "NULL") + ")")
            .bind("id", id).bind("email", email).fetch().rowsUpdated().block();
        return id;
    }

    @Test
    void anEmailIsFoundIgnoringCaseButNotForADeletedAccount() {
        var id = register("Mixed.Case@Example.com", false);
        register("gone@example.com", true);

        assertThat(roles.findMemberByEmail("mixed.case@example.COM").block().id()).isEqualTo(id);
        assertThat(roles.findMemberByEmail("gone@example.com").block()).isNull();
        assertThat(roles.findMemberByEmail("missing@example.com").block()).isNull();
        assertThat(roles.findMemberById(id).block().email()).isEqualTo("Mixed.Case@Example.com");
    }

    @Test
    void grantingIsIdempotentAndRevokingReportsWhetherAnythingChanged() {
        var id = register("owner-a@example.com", false);

        assertThat(roles.grant(id, "GROUND_OWNER").block()).isTrue();
        assertThat(roles.grant(id, "GROUND_OWNER").block()).isFalse();
        assertThat(roles.revoke(id, "GROUND_OWNER").block()).isTrue();
        assertThat(roles.revoke(id, "GROUND_OWNER").block()).isFalse();
    }

    @Test
    void holdersListsOnlyThoseWithTheRoleWithTheirLastGrantDateFromTheAudit() {
        var id = register("owner-b@example.com", false);
        var sqlOnly = register("owner-c@example.com", false);
        var other = register("customer-d@example.com", false);
        roles.grant(id, "GROUND_OWNER").block();
        roles.grant(sqlOnly, "GROUND_OWNER").block();
        roles.grant(other, "CUSTOMER").block();
        var at = Instant.parse("2026-11-16T10:00:00Z");
        roles.appendAudit(new AuditEntry(UUID.randomUUID(), UUID.randomUUID(), id, "owner-b@example.com", "GROUND_OWNER", "GRANTED", at)).block();

        var holders = roles.holders("GROUND_OWNER").collectList().block();

        assertThat(holders).extracting(h -> h.member().id()).contains(id, sqlOnly).doesNotContain(other);
        assertThat(holders.stream().filter(h -> h.member().id().equals(id)).findFirst().orElseThrow().grantedAt()).isEqualTo(at);
        assertThat(holders.stream().filter(h -> h.member().id().equals(sqlOnly)).findFirst().orElseThrow().grantedAt()).isNull();
    }

    @Test
    void theAuditLogKeepsEntriesNewestFirstAndRefusesUnknownActions() {
        var target = UUID.randomUUID();
        var actor = UUID.randomUUID();
        roles.appendAudit(new AuditEntry(UUID.randomUUID(), actor, target, "x@example.com", "AUDITED_ROLE", "GRANTED", Instant.parse("2026-11-16T09:00:00Z"))).block();
        roles.appendAudit(new AuditEntry(UUID.randomUUID(), actor, target, "x@example.com", "AUDITED_ROLE", "REVOKED", Instant.parse("2026-11-16T10:00:00Z"))).block();

        var log = roles.audit("AUDITED_ROLE", 10).collectList().block();

        assertThat(log).extracting(AuditEntry::action).containsExactly("REVOKED", "GRANTED");
        assertThat(roles.audit("AUDITED_ROLE", 1).collectList().block()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roles.appendAudit(
            new AuditEntry(UUID.randomUUID(), actor, target, "x@example.com", "AUDITED_ROLE", "WHATEVER", Instant.now())).block())
            .hasMessageContaining("ck_role_audit_action");
    }
}
