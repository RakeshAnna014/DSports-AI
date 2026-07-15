package com.dsports.identity.infrastructure.persistence.repository;

import com.dsports.identity.application.port.EventPublisher;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.model.Address;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserId;
import com.dsports.identity.infrastructure.persistence.entity.AddressEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerAuthProviderEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerEntity;
import com.dsports.identity.infrastructure.persistence.entity.CustomerRoleEntity;
import com.dsports.identity.infrastructure.persistence.mapper.CustomerEntityMapper;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class UserR2dbcRepositoryAdapter implements UserRepository {

    private final DatabaseClient databaseClient;
    private final CustomerEntityMapper mapper;
    private final SpringR2dbcUserRepository userRepository;
    private final SpringR2dbcAddressRepository addressRepository;
    private final EventPublisher eventPublisher;

    public UserR2dbcRepositoryAdapter(DatabaseClient databaseClient, CustomerEntityMapper mapper,
                                       SpringR2dbcUserRepository userRepository,
                                       SpringR2dbcAddressRepository addressRepository,
                                       EventPublisher eventPublisher) {
        this.databaseClient = databaseClient;
        this.mapper = mapper;
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<User> findByEmail(Email email) {
        return databaseClient.sql("""
                        SELECT * FROM customers WHERE email = :email
                        """)
                .bind("email", email.value())
                .map((row, metadata) -> mapCustomer(row))
                .one()
                .flatMap(this::loadUserWithRolesProvidersAndAddresses);
    }

    @Override
    public Mono<User> findById(UserId id) {
        return userRepository.findById(id.value())
                .flatMap(this::loadUserWithRolesProvidersAndAddresses);
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
        var entity = mapper.toEntity(user);
        var customerId = entity.getId();

        return userRepository.save(entity)
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                        new IdentityDomainException(ErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                "User was modified by another request. Please retry.",
                                java.util.Map.of("userId", customerId.toString())))
                .flatMap(savedEntity ->
                        replaceRoles(customerId, user.getRoles())
                                .then(replaceAuthProviders(customerId, user.getAuthProviders()))
                                .then(replaceAddresses(customerId, user.getAddresses()))
                )
                .then(Mono.fromRunnable(() -> {
                    user.getDomainEvents().forEach(event -> eventPublisher.publish(event));
                    user.clearDomainEvents();
                }));
    }

    private Mono<User> loadUserWithRolesProvidersAndAddresses(CustomerEntity entity) {
        return Mono.zip(
                loadRoles(entity.getId()).collectList(),
                loadAuthProviders(entity.getId()).collectList(),
                addressRepository.findByCustomerId(entity.getId()).collectList()
        ).map(tuple ->
                mapper.toDomain(entity,
                        Set.copyOf(tuple.getT1()),
                        Set.copyOf(tuple.getT2()),
                        tuple.getT3())
        );
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

    private Mono<Void> replaceAddresses(UUID customerId, List<Address> addresses) {
        return addressRepository.findByCustomerId(customerId)
                .collectList()
                .flatMap(existing -> {
                    var currentIds = addresses.stream()
                            .map(a -> a.getId().value())
                            .collect(Collectors.toSet());

                    var deleteOps = existing.stream()
                            .filter(e -> !currentIds.contains(e.getId()))
                            .map(e -> addressRepository.deleteByCustomerIdAndId(customerId, e.getId()))
                            .toArray(Mono[]::new);

                    var saveOps = addresses.stream()
                            .map(a -> toAddressEntity(a, customerId))
                            .map(addressRepository::save)
                            .toArray(Mono[]::new);

                    return Mono.when(concatArrays(deleteOps, saveOps));
                });
    }

    private Mono<?>[] concatArrays(Mono<?>[] a, Mono<?>[] b) {
        var result = new Mono<?>[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private AddressEntity toAddressEntity(Address address, UUID customerId) {
        return new AddressEntity(
                address.getId().value(), customerId,
                address.getType().name(),
                address.getLine1().value(),
                address.getLine2() != null ? address.getLine2().value() : null,
                address.getCity(),
                address.getState().value(),
                address.getCountry().value(),
                address.getPostalCode().value(),
                address.isDefault(),
                address.getCreatedAt(),
                address.getUpdatedAt()
        );
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
        e.setProfileImageUrl(row.get("profile_image_url", String.class));
        e.setDateOfBirth(row.get("date_of_birth", java.time.LocalDate.class));
        e.setStatus(row.get("status", String.class));
        e.setFailedLoginAttempts(row.get("failed_login_attempts", Integer.class));
        e.setLockedUntil(row.get("locked_until", java.time.Instant.class));
        e.setLastLoginAt(row.get("last_login_at", java.time.Instant.class));
        e.setCreatedAt(row.get("created_at", java.time.Instant.class));
        e.setUpdatedAt(row.get("updated_at", java.time.Instant.class));
        e.setDeletedAt(row.get("deleted_at", java.time.Instant.class));
        e.setVersion(row.get("version", Integer.class));
        return e;
    }
}
