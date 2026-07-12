# Identity Module — Testing Strategy

## 1. Unit Testing Strategy

### Scope

All domain objects and application use cases tested in isolation. No Spring context, no database, no network.

### Domain Layer Tests

| Class | Test Focus | Coverage |
|-------|-----------|----------|
| `Email` | Valid formats, invalid formats, normalization, null/blank, max length | All validation paths |
| `CustomerName` | Valid names, invalid characters (HTML/script), null/blank, max length | All validation paths |
| `PhoneNumber` | Valid E.164, India-specific, invalid formats | All validation paths |
| `UserId` | Creation via generate/fromString/fromUUID, equality | All factory methods |
| `UserStatus` | All state transitions (valid + invalid), query helpers (isActive, canLogin, etc.) | Every transition in the state machine |
| `UserRole` | Hierarchy comparisons (isAtLeast, isStrictlyHigherThan), assignment limits | Every role pair |
| `AuthenticationProvider` | isOAuth detection, equality | All enum values |
| `User` | Factory methods (register, registerWithOAuth), lifecycle (verify, lock, unlock, disable, enable, delete), role management, auth provider linking, security (failed login, lockout, last login), domain events, equals/hashCode | Every public behavior method + edge cases |
| `UserRegisteredEvent` | Construction, field accessors | All fields |
| `UserProfileManagementService` | Phone update, password hash update | Happy path only (delegates to User) |
| `IdentityDomainException` | ErrorCode + context map construction | All constructors |

### Application Layer Tests

| Class | Test Focus | Coverage |
|-------|-----------|----------|
| `RegisterUserUseCase` | Successful registration, duplicate email rejection, password encoding delegation, domain event publishing | All branches |
| `AuthenticationUseCase` | Successful login, user not found, account locked, account disabled, account deleted, invalid password, null password hash (OAuth user), failed login recording, last login update | Every failure reason + success path |

### Mocking Approach for Ports

All application-layer tests use **manual mocks** (no Mockito):

```java
// Example: InMemoryUserRepository implements UserRepository
// Used across both use case tests
class InMemoryUserRepository implements UserRepository {
    private final Map<Email, User> store = new HashMap<>();

    @Override
    public Optional<User> findByEmail(Email email) {
        return Optional.ofNullable(store.get(email));
    }

    @Override
    public Optional<User> findById(UserId id) {
        return store.values().stream()
                .filter(u -> u.getId().equals(id))
                .findFirst();
    }

    @Override
    public void save(User user) {
        store.put(user.getEmail(), user);
    }
}
```

Similarly for `PasswordEncoder`, `EventPublisher`, `NotificationGateway`, and `OAuthProviderGateway` — each gets a lightweight in-memory test double.

**Why not Mockito:**
- Port interfaces have few methods — in-memory implementations are trivial to write
- No mocking framework setup needed
- Tests are self-contained and easier to read
- Avoids fragile verify() assertions that over-specify implementation

### Test Structure

```
src/test/java/com/dsports/identity/
├── domain/
│   ├── model/
│   │   ├── EmailTest.java
│   │   ├── CustomerNameTest.java
│   │   ├── PhoneNumberTest.java
│   │   ├── UserIdTest.java
│   │   ├── UserStatusTest.java
│   │   ├── UserRoleTest.java
│   │   ├── AuthenticationProviderTest.java
│   │   └── UserTest.java
│   ├── event/
│   │   └── UserRegisteredEventTest.java
│   └── exception/
│       └── IdentityDomainExceptionTest.java
├── application/
│   ├── usecase/
│   │   ├── RegisterUserUseCaseTest.java
│   │   └── AuthenticationUseCaseTest.java
│   └── port/
│       ├── InMemoryUserRepository.java  (shared test double)
│       ├── InMemoryPasswordEncoder.java (shared test double)
│       └── InMemoryEventPublisher.java  (shared test double)
└── shared/
    └── AbstractUnitTest.java            (optional base class)
```

### Naming Convention

```
[MethodName]_[Scenario]_[ExpectedResult]
```

Examples:
- `register_validInput_createsUserAndPublishesEvent`
- `register_duplicateEmail_throwsIdentityDomainException`
- `execute_correctCredentials_returnsSuccess`
- `execute_wrongPassword_recordsFailedLoginAndReturnsFailure`

---

## 2. Integration Testing Strategy

### Scope

Verify that application use cases work correctly with real infrastructure adapters. Uses **Testcontainers** for PostgreSQL.

### What to Test

| Scenario | Components Involved | Verification |
|----------|-------------------|--------------|
| Register + persist user | RegisterUserUseCase + R2dbcUserRepository + PostgreSQL | User row exists, correct columns |
| Register duplicate email | RegisterUserUseCase + R2dbcUserRepository | DuplicateKeyException or constraint violation |
| Login with valid credentials | AuthenticationUseCase + R2dbcUserRepository + BCryptPasswordEncoder | AuthenticationResult.success |
| Login with wrong password | AuthenticationUseCase + R2dbcUserRepository + BCryptPasswordEncoder | failedLoginAttempts incremented in DB |
| Login with locked account | AuthenticationUseCase + R2dbcUserRepository | AuthenticationResult.failure(ACCOUNT_LOCKED) |

### Configuration

```yaml
# application-test.yml
spring:
  r2dbc:
    url: r2dbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DB:dsports_test}
    username: ${POSTGRES_USER:test}
    password: ${POSTGRES_PASSWORD:test}
```

### Testcontainers Setup

```java
@Testcontainers
class AuthenticationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("dsports_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () ->
                "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                        + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
    }
}
```

### Flyway Migration Verification

Integration tests also verify that Flyway migrations apply cleanly:
- No migration failures
- Schema matches expected structure (email column has UNIQUE constraint, etc.)

---

## 3. Contract Testing Strategy

### Scope

Verify that port interfaces contract is honored by infrastructure adapters.

### Which Ports Need Contract Tests

| Port Interface | Adapter | Contract Test |
|---------------|---------|---------------|
| `UserRepository` | `R2dbcUserRepository` | Save + findById + findByEmail roundtrip |
| `PasswordEncoder` | `BCryptPasswordEncoderAdapter` | encode produces valid BCrypt hash, matches returns true/false |
| `EventPublisher` | `SpringEventPublisherAdapter` | Event is published to Spring ApplicationEventPublisher |
| `NotificationGateway` | `EmailNotificationAdapter` | (requires SMTP — deferred to end-to-end) |
| `OAuthProviderGateway` | `GoogleOAuthAdapter` | (requires Google credentials — deferred) |

### Contract Test Example

```java
abstract class UserRepositoryContractTest {

    protected abstract UserRepository repository();

    @Test
    void saveAndFindById_roundtrip() {
        User user = User.register(Email.from("test@example.com"),
                CustomerName.of("John", "Doe"), "hashed-password");
        repository().save(user);

        var found = repository().findById(user.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo(user.getEmail());
    }

    @Test
    void findByEmail_returnsEmpty_whenNotExists() {
        var result = repository().findByEmail(Email.from("nonexistent@example.com"));
        assertThat(result).isEmpty();
    }

    @Test
    void save_overwritesExistingUser() {
        Email email = Email.from("same@example.com");
        User first = User.register(email, CustomerName.of("First", "User"), "hash1");
        repository().save(first);

        User second = User.register(email, CustomerName.of("Second", "User"), "hash2");
        repository().save(second);

        var found = repository().findByEmail(email);
        assertThat(found).isPresent();
        assertThat(found.get().getCustomerName().firstName()).isEqualTo("Second");
    }
}
```

The concrete test class connects the contract to the real adapter:

```java
class R2dbcUserRepositoryContractTest extends UserRepositoryContractTest {

    private R2dbcUserRepository repository;

    @BeforeEach
    void setUp() {
        // Set up R2dbcUserRepository with Testcontainers-backed connection
    }

    @Override
    protected UserRepository repository() {
        return repository;
    }
}
```

---

## 4. Future Performance Testing

### When to Add

Performance testing is deferred until the authentication endpoints are implemented in the interfaces layer and deployed to a non-production environment.

### Scenarios to Test

| Scenario | Target | Tool |
|----------|--------|------|
| Concurrent user registrations | 100 req/s, < 500ms p95 | Gatling or k6 |
| Login with valid credentials | 500 req/s, < 300ms p95 | Gatling or k6 |
| Login with invalid password | 100 req/s, < 300ms p95 | Gatling or k6 |
| Database constraint violation handling | 50 concurrent duplicate registrations | Gatling or k6 |

### Key Metrics

- Response time (p50, p95, p99)
- Error rate
- Database connection pool saturation
- Password encoding throughput (CPU-bound)

---

## 5. Mocking Approach for Ports

### Principle

Port interfaces in the application layer are designed to be easily mocked. Each interface has a small, focused contract.

| Interface | Methods | In-Memory Test Double |
|-----------|---------|-----------------------|
| `UserRepository` | `findByEmail`, `findById`, `save` | `HashMap<Email, User>` backed |
| `PasswordEncoder` | `encode`, `matches` | Plaintext passthrough for tests |
| `EventPublisher` | `publish`, `publishAll` | `List<DomainEvent>` accumulator |
| `NotificationGateway` | `sendVerificationEmail`, `sendWelcomeEmail` | `List<Notification>` accumulator |
| `OAuthProviderGateway` | (empty — future use) | No-op |

### Example Test Doubles

```java
// InMemoryPasswordEncoder — plaintext for testing, no hashing overhead
class InMemoryPasswordEncoder implements PasswordEncoder {
    @Override
    public String encode(String rawPassword) {
        return "{plain}" + rawPassword;
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return encodedPassword.equals("{plain}" + rawPassword);
    }
}

// InMemoryEventPublisher — captures events for assertion
class InMemoryEventPublisher implements EventPublisher {
    private final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(DomainEvent event) {
        published.add(event);
    }

    @Override
    public void publishAll(List<DomainEvent> events) {
        published.addAll(events);
    }

    public List<DomainEvent> published() {
        return List.copyOf(published);
    }

    public void reset() {
        published.clear();
    }
}
```

### Why Not Mockito (Expanded)

1. **Port interfaces are stable** — they rarely change, so writing a manual double is a one-time cost.
2. **In-memory doubles catch real bugs** — they behave like real implementations, not stubs that return whatever the test configured.
3. **Refactoring resilience** — when a port method signature changes, the in-memory double fails to compile, catching the issue immediately. Mockito tests often pass with outdated expectations.
4. **Test readability** — no `when(...).thenReturn(...)` chains. Setup is just `repository.save(user)`.
5. **No learning curve** — any developer can read and understand a HashMap-backed repository.

### When to Use Mockito (Rare Exceptions)

- Testing error/exception paths from infrastructure (e.g., database connection timeout)
- Verifying specific call sequences across multiple ports
- Legacy code where writing a test double would require significant extraction

For the Identity module, manual in-memory doubles are the primary approach.
