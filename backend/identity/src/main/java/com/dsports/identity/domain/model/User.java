package com.dsports.identity.domain.model;

import com.dsports.identity.domain.event.UserRegisteredEvent;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

// Package-level access for reconstitute() to set private fields directly.
// This is intentional — no reflection needed for Aggregate reconstruction.

public final class User {

    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_AUTH_PROVIDERS = 5;

    private final UserId id;
    private Email email;
    // TODO-SPRINT-2: Extract passwordHash into a Credential Aggregate (PasswordCredential,
    //                OAuthCredential, OTPCredential) to support multiple auth mechanisms.
    //                Current approach is acceptable for Sprint 1 — single password hash
    //                on User is sufficient for email-based registration.
    private String passwordHash;
    private CustomerName customerName;
    private PhoneNumber phone;
    private UserStatus status;
    private final Set<UserRole> roles;
    private final Set<AuthenticationProvider> authProviders;
    private int failedLoginAttempts;
    private Instant lockedUntil;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private User(UserId id, Email email, String passwordHash, CustomerName customerName,
                 UserStatus status, Set<UserRole> roles, Set<AuthenticationProvider> authProviders) {
        this.id = Objects.requireNonNull(id, "userId must not be null");
        this.email = Objects.requireNonNull(email, "email must not be null");
        this.passwordHash = passwordHash;
        this.customerName = Objects.requireNonNull(customerName, "customerName must not be null");
        this.phone = null;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.roles = new HashSet<>(Objects.requireNonNull(roles, "roles must not be null"));
        this.authProviders = new HashSet<>(Objects.requireNonNull(authProviders, "authProviders must not be null"));
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.lastLoginAt = null;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.deletedAt = null;
    }

    // ============ FACTORY METHODS ============

    public static User register(Email email, CustomerName customerName, String passwordHash) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(customerName, "customerName must not be null");
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash must not be null or empty for email registration");
        }
        User user = new User(
                UserId.generate(),
                email,
                passwordHash,
                customerName,
                UserStatus.REGISTERED,
                Set.of(UserRole.CUSTOMER),
                Set.of(AuthenticationProvider.EMAIL)
        );
        user.recordEvent(new UserRegisteredEvent(user.id, user.email, AuthenticationProvider.EMAIL));
        return user;
    }

    public static User registerWithOAuth(Email email, CustomerName customerName, AuthenticationProvider provider) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(customerName, "customerName must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        if (!provider.isOAuth()) {
            throw new IllegalArgumentException("A valid OAuth provider is required");
        }
        User user = new User(
                UserId.generate(),
                email,
                null,
                customerName,
                UserStatus.ACTIVE,
                Set.of(UserRole.CUSTOMER),
                Set.of(provider)
        );
        user.recordEvent(new UserRegisteredEvent(user.id, user.email, provider));
        return user;
    }

    // Used ONLY by UserPersistenceMapper to rebuild an existing Aggregate from persistent storage.
    // Skips factory rules (no event recording, no ID generation) because the Aggregate already exists.
    public static User reconstitute(UserId id, Email email, String passwordHash,
                                     CustomerName customerName, PhoneNumber phone,
                                     UserStatus status, Set<UserRole> roles,
                                     Set<AuthenticationProvider> authProviders,
                                     int failedLoginAttempts, Instant lockedUntil,
                                     Instant lastLoginAt, Instant createdAt,
                                     Instant updatedAt, Instant deletedAt) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(customerName, "customerName must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(authProviders, "authProviders must not be null");
        User user = new User(id, email, passwordHash, customerName, status, roles, authProviders);
        user.phone = phone;
        user.failedLoginAttempts = failedLoginAttempts;
        user.lockedUntil = lockedUntil;
        user.lastLoginAt = lastLoginAt;
        user.createdAt = createdAt;
        user.updatedAt = updatedAt;
        user.deletedAt = deletedAt;
        return user;
    }

    // ============ LIFECYCLE BEHAVIORS ============

    public void markVerificationSent() {
        this.status = this.status.transitionTo(UserStatus.PENDING_VERIFICATION);
        this.updatedAt = Instant.now();
    }

    public void verifyEmail() {
        this.status = this.status.transitionTo(UserStatus.ACTIVE);
        this.updatedAt = Instant.now();
    }

    public void lock() {
        this.status = this.status.transitionTo(UserStatus.LOCKED);
        this.lockedUntil = Instant.now().plus(LOCK_DURATION);
        this.updatedAt = Instant.now();
    }

    public void unlock() {
        this.status = this.status.transitionTo(UserStatus.ACTIVE);
        this.lockedUntil = null;
        this.failedLoginAttempts = 0;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.status = this.status.transitionTo(UserStatus.DISABLED);
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.status = this.status.transitionTo(UserStatus.ACTIVE);
        this.updatedAt = Instant.now();
    }

    public void delete() {
        this.status = this.status.transitionTo(UserStatus.DELETED);
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // ============ PROFILE BEHAVIORS ============

    public void changeEmail(Email newEmail) {
        Objects.requireNonNull(newEmail, "newEmail must not be null");
        if (status != UserStatus.ACTIVE && status != UserStatus.PENDING_VERIFICATION) {
            throw new IdentityDomainException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot change email while in status " + status);
        }
        this.email = newEmail;
        this.updatedAt = Instant.now();
    }

    // Package-private: called by UserProfileManagementService.
    // Public API removed per domain review — this is pure state mutation without invariant enforcement.
    void updatePhone(PhoneNumber newPhone) {
        Objects.requireNonNull(newPhone, "newPhone must not be null");
        this.phone = newPhone;
        this.updatedAt = Instant.now();
    }

    // Package-private: called by UserProfileManagementService.
    // Public API removed per domain review — this is pure state mutation without invariant enforcement.
    void updatePasswordHash(String newPasswordHash) {
        if (newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new IllegalArgumentException("newPasswordHash must not be null or empty");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAt = Instant.now();
    }

    // ============ ROLE BEHAVIORS ============

    public void assignRole(UserRole role) {
        Objects.requireNonNull(role, "role must not be null");
        if (this.roles.contains(role)) {
            throw new IdentityDomainException(ErrorCode.DUPLICATE_ROLE,
                    "Role " + role.name() + " is already assigned to user " + this.id,
                    Map.of("role", role.name(), "userId", this.id.toString()));
        }
        this.roles.add(role);
        this.updatedAt = Instant.now();
    }

    public void removeRole(UserRole role) {
        Objects.requireNonNull(role, "role must not be null");
        if (!this.roles.contains(role)) {
            throw new IdentityDomainException(ErrorCode.MISSING_ROLE,
                    "Role " + role.name() + " is not assigned to user " + this.id,
                    Map.of("role", role.name(), "userId", this.id.toString()));
        }
        this.roles.remove(role);
        this.updatedAt = Instant.now();
    }

    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }

    public int roleCount() {
        return roles.size();
    }

    // ============ AUTH PROVIDER BEHAVIORS ============

    public void linkAuthenticationProvider(AuthenticationProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        if (this.authProviders.contains(provider)) {
            throw new IdentityDomainException(ErrorCode.OAUTH_PROVIDER_ALREADY_LINKED,
                    "Authentication provider " + provider.name() + " is already linked to user " + this.id,
                    Map.of("provider", provider.name(), "userId", this.id.toString()));
        }
        if (this.authProviders.size() >= MAX_AUTH_PROVIDERS) {
            throw new IdentityDomainException(ErrorCode.MAX_AUTH_PROVIDERS_EXCEEDED,
                    "Cannot link more than " + MAX_AUTH_PROVIDERS + " authentication providers. "
                            + "Current: " + this.authProviders.size());
        }
        this.authProviders.add(provider);
        this.updatedAt = Instant.now();
    }

    public boolean hasAuthenticationProvider(AuthenticationProvider provider) {
        return authProviders.contains(provider);
    }

    public int authProviderCount() {
        return authProviders.size();
    }

    // ============ SECURITY BEHAVIORS ============

    public void recordFailedLogin() {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
            this.status = UserStatus.LOCKED;
            this.lockedUntil = Instant.now().plus(LOCK_DURATION);
        }
        this.updatedAt = Instant.now();
    }

    public void resetFailedLogin() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = Instant.now();
    }

    public void updateLastLogin() {
        this.lastLoginAt = Instant.now();
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
        if (this.status == UserStatus.LOCKED) {
            this.status = UserStatus.ACTIVE;
        }
        this.updatedAt = Instant.now();
    }

    public boolean isLocked() {
        if (this.status == UserStatus.LOCKED && this.lockedUntil != null) {
            if (Instant.now().isAfter(this.lockedUntil)) {
                this.status = UserStatus.ACTIVE;
                this.lockedUntil = null;
                this.failedLoginAttempts = 0;
                this.updatedAt = Instant.now();
                return false;
            }
            return true;
        }
        return false;
    }

    public boolean isActive() {
        return this.status.isActive();
    }

    public boolean canLogin() {
        if (this.status == UserStatus.LOCKED) {
            return !isLocked();
        }
        return this.status.canLogin();
    }

    // ============ DOMAIN EVENTS ============

    public List<DomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    private void recordEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    // ============ GETTERS ============

    public UserId getId() {
        return id;
    }

    public Email getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public CustomerName getCustomerName() {
        return customerName;
    }

    public Optional<PhoneNumber> getPhone() {
        return Optional.ofNullable(phone);
    }

    public UserStatus getStatus() {
        return status;
    }

    public Set<UserRole> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public Set<AuthenticationProvider> getAuthProviders() {
        return Collections.unmodifiableSet(authProviders);
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public Optional<Instant> getLockedUntil() {
        return Optional.ofNullable(lockedUntil);
    }

    public Optional<Instant> getLastLoginAt() {
        return Optional.ofNullable(lastLoginAt);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Optional<Instant> getDeletedAt() {
        return Optional.ofNullable(deletedAt);
    }

    // ============ OBJECT OVERRIDES ============

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User user)) return false;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", email=" + email + ", status=" + status + "}";
    }
}
