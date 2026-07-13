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

import java.util.HashSet;
import java.util.Optional;
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
    public Optional<User> findByEmail(Email email) {
        CustomerEntity entity = databaseClient.sql("""
                        SELECT * FROM customers WHERE email = :email
                        """)
                .bind("email", email.value())
                .map((row, metadata) -> mapCustomer(row))
                .one()
                .blockOptional()
                .orElse(null);

        if (entity == null) {
            return Optional.empty();
        }

        Set<CustomerRoleEntity> roles = loadRoles(entity.getId());
        Set<CustomerAuthProviderEntity> providers = loadAuthProviders(entity.getId());
        return Optional.of(mapper.toDomain(entity, roles, providers));
    }

    @Override
    public Optional<User> findById(UserId id) {
        CustomerEntity entity = databaseClient.sql("""
                        SELECT * FROM customers WHERE id = :id
                        """)
                .bind("id", id.value())
                .map((row, metadata) -> mapCustomer(row))
                .one()
                .blockOptional()
                .orElse(null);

        if (entity == null) {
            return Optional.empty();
        }

        Set<CustomerRoleEntity> roles = loadRoles(entity.getId());
        Set<CustomerAuthProviderEntity> providers = loadAuthProviders(entity.getId());
        return Optional.of(mapper.toDomain(entity, roles, providers));
    }

    @Override
    public boolean existsByEmail(Email email) {
        Integer count = databaseClient.sql("""
                        SELECT COUNT(*) FROM customers WHERE email = :email
                        """)
                .bind("email", email.value())
                .map((row, metadata) -> row.get("count", Integer.class))
                .one()
                .blockOptional()
                .orElse(0);
        return count > 0;
    }

    @Override
    public void save(User user) {
        CustomerEntity entity = mapper.toEntity(user);
        UUID customerId = entity.getId();

        databaseClient.sql("""
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
                .block();

        replaceRoles(customerId, user.getRoles());
        replaceAuthProviders(customerId, user.getAuthProviders());
    }

    private void replaceRoles(UUID customerId, Set<com.dsports.identity.domain.model.UserRole> roles) {
        databaseClient.sql("DELETE FROM customer_roles WHERE customer_id = :customerId")
                .bind("customerId", customerId)
                .then()
                .block();

        for (CustomerRoleEntity role : mapper.toRoleEntities(customerId, roles)) {
            databaseClient.sql("INSERT INTO customer_roles (customer_id, role) VALUES (:customerId, :role)")
                    .bind("customerId", role.getCustomerId())
                    .bind("role", role.getRole())
                    .then()
                    .block();
        }
    }

    private void replaceAuthProviders(UUID customerId, Set<com.dsports.identity.domain.model.AuthenticationProvider> providers) {
        databaseClient.sql("DELETE FROM customer_auth_providers WHERE customer_id = :customerId")
                .bind("customerId", customerId)
                .then()
                .block();

        for (CustomerAuthProviderEntity provider : mapper.toAuthProviderEntities(customerId, providers)) {
            databaseClient.sql("INSERT INTO customer_auth_providers (customer_id, provider) VALUES (:customerId, :provider)")
                    .bind("customerId", provider.getCustomerId())
                    .bind("provider", provider.getProvider())
                    .then()
                    .block();
        }
    }

    private Set<CustomerRoleEntity> loadRoles(UUID customerId) {
        return new HashSet<>(databaseClient.sql("""
                        SELECT * FROM customer_roles WHERE customer_id = :customerId
                        """)
                .bind("customerId", customerId)
                .map((row, metadata) -> {
                    CustomerRoleEntity e = new CustomerRoleEntity();
                    e.setCustomerId(row.get("customer_id", UUID.class));
                    e.setRole(row.get("role", String.class));
                    return e;
                })
                .all()
                .collectList()
                .blockOptional()
                .orElse(java.util.Collections.emptyList()));
    }

    private Set<CustomerAuthProviderEntity> loadAuthProviders(UUID customerId) {
        return new HashSet<>(databaseClient.sql("""
                        SELECT * FROM customer_auth_providers WHERE customer_id = :customerId
                        """)
                .bind("customerId", customerId)
                .map((row, metadata) -> {
                    CustomerAuthProviderEntity e = new CustomerAuthProviderEntity();
                    e.setCustomerId(row.get("customer_id", UUID.class));
                    e.setProvider(row.get("provider", String.class));
                    return e;
                })
                .all()
                .collectList()
                .blockOptional()
                .orElse(java.util.Collections.emptyList()));
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
