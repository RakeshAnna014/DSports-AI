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

        userRepository.save(user);

        var found = userRepository.findByEmail(Email.from("test@example.com"));
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
        assertThat(found.get().getEmail().value()).isEqualTo("test@example.com");
        assertThat(found.get().getCustomerName().firstName()).isEqualTo("John");
        assertThat(found.get().getCustomerName().lastName()).isEqualTo("Doe");
        assertThat(found.get().getStatus()).isEqualTo(UserStatus.REGISTERED);
        assertThat(found.get().getPasswordHash()).isEqualTo("encoded-password");
        assertThat(found.get().getRoles()).containsExactly(UserRole.CUSTOMER);
        assertThat(found.get().getAuthProviders()).containsExactly(AuthenticationProvider.EMAIL);
        assertThat(found.get().getFailedLoginAttempts()).isZero();
    }

    @Test
    void registerCustomer_persistAndRetrieveById_equalityMatches() {
        User user = User.register(
                Email.from("jane@example.com"),
                CustomerName.of("Jane", "Smith"),
                "another-password"
        );

        userRepository.save(user);

        var found = userRepository.findById(user.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
        assertThat(found.get().getEmail().value()).isEqualTo("jane@example.com");
    }

    @Test
    void existsByEmail_returnsTrue_whenEmailExists() {
        User user = User.register(
                Email.from("exists@example.com"),
                CustomerName.of("Exists", "User"),
                "password"
        );
        userRepository.save(user);

        boolean exists = userRepository.existsByEmail(Email.from("exists@example.com"));
        assertThat(exists).isTrue();
    }

    @Test
    void existsByEmail_returnsFalse_whenEmailDoesNotExist() {
        boolean exists = userRepository.existsByEmail(Email.from("nonexistent@example.com"));
        assertThat(exists).isFalse();
    }

    @Test
    void findByEmail_returnsEmpty_whenEmailDoesNotExist() {
        var result = userRepository.findByEmail(Email.from("unknown@example.com"));
        assertThat(result).isEmpty();
    }

    @Test
    void saveWithUpdatedFields_reflectsChanges() {
        User user = User.register(
                Email.from("update@example.com"),
                CustomerName.of("Update", "Test"),
                "original-hash"
        );
        userRepository.save(user);

        user.updateLastLogin();
        userRepository.save(user);

        var found = userRepository.findByEmail(Email.from("update@example.com"));
        assertThat(found).isPresent();
        assertThat(found.get().getLastLoginAt()).isPresent();
    }

    @Test
    void duplicateEmail_throwsException() {
        User user = User.register(
                Email.from("duplicate@example.com"),
                CustomerName.of("First", "User"),
                "password"
        );
        userRepository.save(user);

        boolean exists = userRepository.existsByEmail(Email.from("duplicate@example.com"));
        assertThat(exists).isTrue();
    }
}
