package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.RoleAdminRepository;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Direct SQL over {@code customer_roles}, {@code customers} and {@code role_audit_log}; touches nothing else of a user's record. */
public class RoleAdminR2dbcRepositoryAdapter implements RoleAdminRepository {

    private final DatabaseClient db;

    public RoleAdminR2dbcRepositoryAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Member> findMemberByEmail(String email) {
        return db.sql("SELECT id, email, first_name, last_name FROM customers WHERE lower(email) = lower(:email) AND deleted_at IS NULL")
            .bind("email", email.trim()).map((row, meta) -> member(row)).one();
    }

    @Override
    public Mono<Member> findMemberById(UUID id) {
        return db.sql("SELECT id, email, first_name, last_name FROM customers WHERE id = :id AND deleted_at IS NULL")
            .bind("id", id).map((row, meta) -> member(row)).one();
    }

    @Override
    public Mono<Boolean> grant(UUID memberId, String role) {
        return db.sql("INSERT INTO customer_roles (customer_id, role) VALUES (:id, :role) ON CONFLICT DO NOTHING")
            .bind("id", memberId).bind("role", role).fetch().rowsUpdated().map(rows -> rows > 0);
    }

    @Override
    public Mono<Boolean> revoke(UUID memberId, String role) {
        return db.sql("DELETE FROM customer_roles WHERE customer_id = :id AND role = :role")
            .bind("id", memberId).bind("role", role).fetch().rowsUpdated().map(rows -> rows > 0);
    }

    @Override
    public Flux<Holder> holders(String role) {
        return db.sql("""
                SELECT c.id, c.email, c.first_name, c.last_name,
                       (SELECT MAX(a.occurred_at) FROM role_audit_log a WHERE a.target_id = c.id AND a.role = :role AND a.action = 'GRANTED') AS granted_at
                  FROM customer_roles r JOIN customers c ON c.id = r.customer_id
                 WHERE r.role = :role AND c.deleted_at IS NULL
                 ORDER BY lower(c.email)
                """)
            .bind("role", role)
            .map((row, meta) -> new Holder(member(row), row.get("granted_at", Instant.class)))
            .all();
    }

    @Override
    public Mono<Void> appendAudit(AuditEntry entry) {
        return db.sql("""
                INSERT INTO role_audit_log (id, actor_id, target_id, target_email, role, action, occurred_at)
                VALUES (:id, :actor, :target, :email, :role, :action, :at)
                """)
            .bind("id", entry.id()).bind("actor", entry.actorId()).bind("target", entry.targetId()).bind("email", entry.targetEmail())
            .bind("role", entry.role()).bind("action", entry.action()).bind("at", entry.occurredAt())
            .fetch().rowsUpdated().then();
    }

    @Override
    public Flux<AuditEntry> audit(String role, int limit) {
        return db.sql("SELECT id, actor_id, target_id, target_email, role, action, occurred_at FROM role_audit_log WHERE role = :role ORDER BY occurred_at DESC LIMIT :limit")
            .bind("role", role).bind("limit", limit)
            .map((row, meta) -> new AuditEntry(row.get("id", UUID.class), row.get("actor_id", UUID.class), row.get("target_id", UUID.class),
                row.get("target_email", String.class), row.get("role", String.class), row.get("action", String.class), row.get("occurred_at", Instant.class)))
            .all();
    }

    private static Member member(Row row) {
        return new Member(row.get("id", UUID.class), row.get("email", String.class), row.get("first_name", String.class), row.get("last_name", String.class));
    }
}
