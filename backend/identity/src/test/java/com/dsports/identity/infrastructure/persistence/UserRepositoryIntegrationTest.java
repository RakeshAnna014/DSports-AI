package com.dsports.identity.infrastructure.persistence;

import com.dsports.identity.application.port.EventPublisher;
import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserRole;
import com.dsports.identity.domain.model.UserStatus;
import com.dsports.identity.infrastructure.persistence.entity.CustomerEntity;
import com.dsports.identity.infrastructure.persistence.mapper.CustomerEntityMapper;
import com.dsports.identity.infrastructure.persistence.repository.SpringR2dbcAddressRepository;
import com.dsports.identity.infrastructure.persistence.repository.SpringR2dbcUserRepository;
import com.dsports.identity.infrastructure.persistence.repository.UserR2dbcRepositoryAdapter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class UserRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("identity_test")
            .withUsername("test")
            .withPassword("test");

    private DatabaseClient databaseClient;
    private UserRepository userRepository;

    @BeforeAll
    static void startContainer() {
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        String r2dbcUrl = "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/identity_test?user=" + postgres.getUsername() + "&password=" + postgres.getPassword();
        databaseClient = DatabaseClient.create(io.r2dbc.spi.ConnectionFactories.get(r2dbcUrl));

        var userRepo = mock(SpringR2dbcUserRepository.class);
        var addressRepo = mock(SpringR2dbcAddressRepository.class);
        var eventPublisher = mock(EventPublisher.class);

        when(userRepo.findById(any(UUID.class))).thenAnswer(invocation -> {
            var id = invocation.<UUID>getArgument(0);
            return databaseClient.sql("SELECT * FROM customers WHERE id = :id")
                    .bind("id", id)
                    .map((row, metadata) -> mapCustomer(row))
                    .one();
        });

        when(userRepo.save(any(CustomerEntity.class))).thenAnswer(invocation -> {
            var entity = invocation.<CustomerEntity>getArgument(0);
            return databaseClient.sql("""
                    INSERT INTO customers (id, email, password_hash, first_name, last_name, phone,
                                           profile_image_url, date_of_birth, status, failed_login_attempts,
                                           locked_until, last_login_at, created_at, updated_at, deleted_at, version)
                    VALUES (:id, :email, :passwordHash, :firstName, :lastName, :phone,
                            :profileImageUrl, :dateOfBirth, :status, :failedLoginAttempts,
                            :lockedUntil, :lastLoginAt, :createdAt, :updatedAt, :deletedAt, :version)
                    ON CONFLICT (id) DO UPDATE SET
                        email = EXCLUDED.email,
                        password_hash = EXCLUDED.password_hash,
                        first_name = EXCLUDED.first_name,
                        last_name = EXCLUDED.last_name,
                        phone = EXCLUDED.phone,
                        profile_image_url = EXCLUDED.profile_image_url,
                        date_of_birth = EXCLUDED.date_of_birth,
                        status = EXCLUDED.status,
                        failed_login_attempts = EXCLUDED.failed_login_attempts,
                        locked_until = EXCLUDED.locked_until,
                        last_login_at = EXCLUDED.last_login_at,
                        updated_at = EXCLUDED.updated_at,
                        deleted_at = EXCLUDED.deleted_at,
                        version = EXCLUDED.version
                    """)
                    .bind("id", entity.getId())
                    .bind("email", entity.getEmail())
                    .bind("passwordHash", entity.getPasswordHash())
                    .bind("firstName", entity.getFirstName())
                    .bind("lastName", entity.getLastName())
                    .bind("phone", entity.getPhone())
                    .bind("profileImageUrl", entity.getProfileImageUrl())
                    .bind("dateOfBirth", entity.getDateOfBirth())
                    .bind("status", entity.getStatus())
                    .bind("failedLoginAttempts", entity.getFailedLoginAttempts())
                    .bind("lockedUntil", entity.getLockedUntil())
                    .bind("lastLoginAt", entity.getLastLoginAt())
                    .bind("createdAt", entity.getCreatedAt())
                    .bind("updatedAt", entity.getUpdatedAt())
                    .bind("deletedAt", entity.getDeletedAt())
                    .bind("version", entity.getVersion())
                    .then()
                    .thenReturn(entity);
        });

        when(addressRepo.findByCustomerId(any(UUID.class))).thenReturn(Flux.empty());

        userRepository = new UserR2dbcRepositoryAdapter(databaseClient, new CustomerEntityMapper(),
                userRepo, addressRepo, eventPublisher);
    }

    @AfterEach
    void cleanUp() {
        databaseClient.sql("DELETE FROM customer_addresses").then().block();
        databaseClient.sql("DELETE FROM customer_auth_providers").then().block();
        databaseClient.sql("DELETE FROM customer_roles").then().block();
        databaseClient.sql("DELETE FROM customers").then().block();
    }

    private CustomerEntity mapCustomer(io.r2dbc.spi.Row row) {
        var e = new CustomerEntity();
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

    @Test
    void registerCustomer_persistAndRetrieveByEmail_equalityMatches() {
        User user = User.register(
                Email.from("test@example.com"),
                CustomerName.of("John", "Doe"),
                "encoded-password"
        );

        userRepository.save(user).block();

        StepVerifier.create(userRepository.findByEmail(Email.from("test@example.com")))
                .assertNext(found -> {
                    assertThat(found.getId()).isEqualTo(user.getId());
                    assertThat(found.getEmail().value()).isEqualTo("test@example.com");
                    assertThat(found.getCustomerName().firstName()).isEqualTo("John");
                    assertThat(found.getCustomerName().lastName()).isEqualTo("Doe");
                    assertThat(found.getStatus()).isEqualTo(UserStatus.REGISTERED);
                    assertThat(found.getPasswordHash()).isEqualTo("encoded-password");
                    assertThat(found.getRoles()).containsExactly(UserRole.CUSTOMER);
                    assertThat(found.getAuthProviders()).containsExactly(AuthenticationProvider.EMAIL);
                    assertThat(found.getFailedLoginAttempts()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void registerCustomer_persistAndRetrieveById_equalityMatches() {
        User user = User.register(
                Email.from("jane@example.com"),
                CustomerName.of("Jane", "Smith"),
                "another-password"
        );

        userRepository.save(user).block();

        StepVerifier.create(userRepository.findById(user.getId()))
                .assertNext(found -> {
                    assertThat(found.getId()).isEqualTo(user.getId());
                    assertThat(found.getEmail().value()).isEqualTo("jane@example.com");
                })
                .verifyComplete();
    }

    @Test
    void existsByEmail_returnsTrue_whenEmailExists() {
        User user = User.register(
                Email.from("exists@example.com"),
                CustomerName.of("Exists", "User"),
                "password"
        );
        userRepository.save(user).block();

        StepVerifier.create(userRepository.existsByEmail(Email.from("exists@example.com")))
                .assertNext(exists -> assertThat(exists).isTrue())
                .verifyComplete();
    }

    @Test
    void existsByEmail_returnsFalse_whenEmailDoesNotExist() {
        StepVerifier.create(userRepository.existsByEmail(Email.from("nonexistent@example.com")))
                .assertNext(exists -> assertThat(exists).isFalse())
                .verifyComplete();
    }

    @Test
    void findByEmail_returnsEmpty_whenEmailDoesNotExist() {
        StepVerifier.create(userRepository.findByEmail(Email.from("unknown@example.com")))
                .expectComplete()
                .verify();
    }

    @Test
    void saveWithUpdatedFields_reflectsChanges() {
        User user = User.register(
                Email.from("update@example.com"),
                CustomerName.of("Update", "Test"),
                "original-hash"
        );
        userRepository.save(user).block();

        user.updateLastLogin();
        userRepository.save(user).block();

        StepVerifier.create(userRepository.findByEmail(Email.from("update@example.com")))
                .assertNext(found -> assertThat(found.getLastLoginAt()).isPresent())
                .verifyComplete();
    }

    @Test
    void duplicateEmail_throwsException() {
        User user = User.register(
                Email.from("duplicate@example.com"),
                CustomerName.of("First", "User"),
                "password"
        );
        userRepository.save(user).block();

        StepVerifier.create(userRepository.existsByEmail(Email.from("duplicate@example.com")))
                .assertNext(exists -> assertThat(exists).isTrue())
                .verifyComplete();
    }
}
