package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.infrastructure.persistence.entity.CustomerAuthProviderEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerRoleEntity;
import com.dsports.identity.infrastructure.persistence.mapper.CustomerEntityMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

public class UserR2dbcRepositoryAdapter implements UserRepository {

    private final DatabaseClient databaseClient;
    private final CustomerEntityMapper mapper;

    public UserR2dbcRepositoryAdapter(DatabaseClient databaseClient, CustomerEntityMapper mapper) {
        this.databaseClient = databaseClient;
        this.mapper = mapper;
    }

    @Override
    public Mono<User> findByEmail(Email email) {
        return databaseClient.sql("""
                        SELECT * FROM customers WHERE email = :email
                        """)
                .bind("email", email.value())
                .map((row, metadata) -> mapCustomer(row))
                .one()
                .flatMap(entity ->
                    Mono.zip(
                        loadRoles(entity.getId()).collectList(),
                        loadAuthProviders(entity.getId()).collectList()
                    ).map(tuple ->
                        mapper.toDomain(entity, Set.copyOf(tuple.getT1()), Set.copyOf(tuple.getT2()))
                    )
                );
    }

    @Override
    public Mono<User> findById(UserId id) {
        return databaseClient.sql("""
                        SELECT * FROM customers WHERE id = :id
                        """)
                .bind("id", id.value())
                .map((row, metadata) -> mapCustomer(row))
                .one()
                .flatMap(entity ->
                    Mono.zip(
                        loadRoles(entity.getId()).collectList(),
                        loadAuthProviders(entity.getId()).collectList()
                    ).map(tuple ->
                        mapper.toDomain(entity, Set.copyOf(tuple.getT1()), Set.copyOf(tuple.getT2()))
                    )
                );
    }

    @Override
    public Mono<Boolean> existsByEmail(Email email) {
        return databaseClient.sql("""
                        SELECT COUNT(*) FROM customers WHERE email = :email
                        """)
                .bind("email", email.value())
                .map((row, metadata) -> {
                    var count = row.get("count", Integer.class);
                    return count != null && count > 0;
                })
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Void> save(User user) {
        CustomerEntity entity = mapper.toEntity(user);
        UUID customerId = entity.getId();

        return databaseClient.sql("""
                        INSERT INTO customers (id, email, password_hash, first_name, last_name,
                            phone, status, failed_login_attempts, locked_until, last_login_at,
                            created_at, updated_at, deleted_at)
                        VALUES (:id, :email, :passwordHash, :firstName, :lastName,
                            :phone, :status, :failedLoginAttempts, :lockedUntil, :lastLoginAt,
                            :createdAt, :updatedAt, :deletedAt)
                        ON CONFLICT (id) DO UPDATE SET
                            email = EXCLUDED.email,
                            password_hash = EXCLUDED.password_hash,
                            first_name = EXCLUDED.first_name,
                            last_name = EXCLUDED.last_name,
                            phone = EXCLUDED.phone,
                            status = EXCLUDED.status,
                            failed_login_attempts = EXCLUDED.failed_login_attempts,
                            locked_until = EXCLUDED.locked_until,
                            last_login_at = EXCLUDED.last_login_at,
                            updated_at = EXCLUDED.updated_at,
                            deleted_at = EXCLUDED.deleted_at
                        """)
                .bind("id", entity.getId())
                .bind("email", entity.getEmail())
                .bind("passwordHash", entity.getPasswordHash())
                .bind("firstName", entity.getFirstName())
                .bind("lastName", entity.getLastName())
                .bind("phone", entity.getPhone())
                .bind("status", entity.getStatus())
                .bind("failedLoginAttempts", entity.getFailedLoginAttempts())
                .bind("lockedUntil", entity.getLockedUntil())
                .bind("lastLoginAt", entity.getLastLoginAt())
                .bind("createdAt", entity.getCreatedAt())
                .bind("updatedAt", entity.getUpdatedAt())
                .bind("deletedAt", entity.getDeletedAt())
                .then()
                .then(replaceRoles(customerId, user.getRoles()))
                .then(replaceAuthProviders(customerId, user.getAuthProviders()));
    }

    private Mono<Void> replaceRoles(UUID customerId, Set<com.dsports.identity.domain.model.UserRole> roles) {
        return databaseClient.sql("DELETE FROM customer_roles WHERE customer_id = :customerId")
                .bind("customerId", customerId)
                .then()
                .thenMany(Flux.fromIterable(mapper.toRoleEntities(customerId, roles)))
                .flatMap(role -> databaseClient.sql(
                            "INSERT INTO customer_roles (customer_id, role) VALUES (:customerId, :role)")
                        .bind("customerId", role.getCustomerId())
                        .bind("role", role.getRole())
                        .then())
                .then();
    }

    private Mono<Void> replaceAuthProviders(UUID customerId, Set<com.dsports.identity.domain.model.AuthenticationProvider> providers) {
        return databaseClient.sql("DELETE FROM customer_auth_providers WHERE customer_id = :customerId")
                .bind("customerId", customerId)
                .then()
                .thenMany(Flux.fromIterable(mapper.toAuthProviderEntities(customerId, providers)))
                .flatMap(provider -> databaseClient.sql(
                            "INSERT INTO customer_auth_providers (customer_id, provider) VALUES (:customerId, :provider)")
                        .bind("customerId", provider.getCustomerId())
                        .bind("provider", provider.getProvider())
                        .then())
                .then();
    }

    private Flux<CustomerRoleEntity> loadRoles(UUID customerId) {
        return databaseClient.sql("""
                        SELECT * FROM customer_roles WHERE customer_id = :customerId
                        """)
                .bind("customerId", customerId)
                .map((row, metadata) -> {
                    CustomerRoleEntity e = new CustomerRoleEntity();
                    e.setCustomerId(row.get("customer_id", UUID.class));
                    e.setRole(row.get("role", String.class));
                    return e;
                })
                .all();
    }

    private Flux<CustomerAuthProviderEntity> loadAuthProviders(UUID customerId) {
        return databaseClient.sql("""
                        SELECT * FROM customer_auth_providers WHERE customer_id = :customerId
                        """)
                .bind("customerId", customerId)
                .map((row, metadata) -> {
                    CustomerAuthProviderEntity e = new CustomerAuthProviderEntity();
                    e.setCustomerId(row.get("customer_id", UUID.class));
                    e.setProvider(row.get("provider", String.class));
                    return e;
                })
                .all();
    }

    private CustomerEntity mapCustomer(io.r2dbc.spi.Row row) {
        CustomerEntity e = new CustomerEntity();
        e.setId(row.get("id", UUID.class));
        e.setEmail(row.get("email", String.class));
        e.setPasswordHash(row.get("password_hash", String.class));
        e.setFirstName(row.get("first_name", String.class));
        e.setLastName(row.get("last_name", String.class));
        e.setPhone(row.get("phone", String.class));
        e.setStatus(row.get("status", String.class));
        e.setFailedLoginAttempts(row.get("failed_login_attempts", Integer.class));
        e.setLockedUntil(row.get("locked_until", java.time.Instant.class));
        e.setLastLoginAt(row.get("last_login_at", java.time.Instant.class));
        e.setCreatedAt(row.get("created_at", java.time.Instant.class));
        e.setUpdatedAt(row.get("updated_at", java.time.Instant.class));
        e.setDeletedAt(row.get("deleted_at", java.time.Instant.class));
        return e;
    }
}
