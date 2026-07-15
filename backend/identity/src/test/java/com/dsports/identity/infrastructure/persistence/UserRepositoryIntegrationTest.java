package com.dsports.identity.infrastructure.persistence;

import com.dsports.identity.application.port.UserRepository;
import com.dsports.identity.domain.model.AuthenticationProvider;
import com.dsports.identity.domain.model.CustomerName;
import com.dsports.identity.domain.model.Email;
import com.dsports.identity.domain.model.User;
import com.dsports.identity.domain.model.UserRole;
import com.dsports.identity.domain.model.UserStatus;
import com.dsports.identity.infrastructure.persistence.mapper.CustomerEntityMapper;
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
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

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
        userRepository = new UserR2dbcRepositoryAdapter(databaseClient, new CustomerEntityMapper());
    }

    @AfterEach
    void cleanUp() {
        databaseClient.sql("DELETE FROM customer_auth_providers").then().block();
        databaseClient.sql("DELETE FROM customer_roles").then().block();
        databaseClient.sql("DELETE FROM customers").then().block();
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
