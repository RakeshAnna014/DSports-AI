# Identity & Access Management (IAM) — Business Requirement Analysis & Domain Design

**Document Version:** 1.0  
**Module:** Identity (`dsports-identity`)  
**Status:** Draft — Pending Architecture Review  
**Author:** Senior Solution Architect  

---

## Table of Contents

1. [Business Requirement Analysis](#1-business-requirement-analysis)
2. [Domain Analysis](#2-domain-analysis)
3. [Authentication Architecture](#3-authentication-architecture)
4. [User Lifecycle](#4-user-lifecycle)
5. [Role Design](#5-role-design)
6. [Registration Flows](#6-registration-flows)
7. [User Profile](#7-user-profile)
8. [Validation Rules](#8-validation-rules)
9. [REST API Contract](#9-rest-api-contract)
10. [Database Design](#10-database-design)
11. [Security Review](#11-security-review)
12. [Testing Strategy](#12-testing-strategy)

---

## 1. Business Requirement Analysis

### 1.1 Actors

| Actor | Description | System Interaction |
|-------|-------------|-------------------|
| **Guest** | Unauthenticated visitor browsing products | Browse catalog, add to cart (guest cart), but cannot checkout |
| **Customer (B2C)** | Registered individual buyer | Full purchase flow, order history, profile management |
| **Customer (B2B)** | Registered wholesale buyer | Same as B2C + bulk pricing, credit limits, invoice billing |
| **Franchise Owner** | Store partner managing their franchise | Manage store inventory, view store reports, manage store orders |
| **Warehouse Manager** | Staff managing physical inventory | Inventory adjustments, stock transfers, fulfillment |
| **Inventory Manager** | Staff managing stock levels | Stock monitoring, reorder alerts, inventory reports |
| **Support Executive** | Customer support staff | View user profiles, process returns, handle order issues |
| **Admin** | Operations administrator | Manage products, users, orders, content, reports |
| **Super Admin** | System owner with full access | All admin capabilities + role management, system config, audit |
| **System** | Automated processes, scheduled jobs | Token cleanup, session expiration, email dispatch |

### 1.2 Business Goals

1. **Secure user identity** — Ensure that every user is who they claim to be before granting access to the platform.
2. **Frictionless onboarding** — Minimize barriers to registration. Every extra click reduces conversion by ~20%.
3. **Role-based access control** — Different users see and do different things. A franchise owner should never see another franchise's financial data.
4. **Multi-provider authentication** — Users choose how they log in. Supporting Google Sign-In alone can increase registration by 30-50%.
5. **Future-proof authentication** — The design must support Apple, Microsoft, Facebook, and mobile OTP login without rewriting the module.
6. **Enterprise compliance** — Audit trails, data retention, GDPR right to deletion, and consent management.

### 1.3 Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-01 | User can register using email and password | P0 |
| FR-02 | User can register using Google OAuth | P0 |
| FR-03 | User receives verification email after registration | P0 |
| FR-04 | User can verify email by clicking a link | P0 |
| FR-05 | User can log in using email and password | P0 |
| FR-06 | User can log in using Google OAuth | P0 |
| FR-07 | User can request password reset | P0 |
| FR-08 | User can reset password using emailed link | P0 |
| FR-09 | User can link Google account to existing email account | P1 |
| FR-10 | User can view and update their profile | P0 |
| FR-11 | User can manage multiple addresses | P0 |
| FR-12 | User can view active sessions | P2 |
| FR-13 | User can revoke individual sessions | P2 |
| FR-14 | User can delete their account (soft delete) | P1 |
| FR-15 | Admin can view all users with filtering | P0 |
| FR-16 | Admin can change user status (lock, disable, activate) | P1 |
| FR-17 | Admin can assign roles to users | P1 |
| FR-18 | Admin can delete users (soft delete) | P1 |
| FR-19 | System locks account after 5 failed login attempts | P0 |
| FR-20 | System logs every authentication event | P0 |
| FR-21 | System automatically refreshes tokens | P0 |
| FR-22 | System cleans up expired tokens | P2 |

### 1.4 Non-Functional Requirements

| ID | Requirement | Target | Rationale |
|----|-------------|--------|-----------|
| NFR-01 | Authentication response time | < 500ms (p95) | Users won't wait for login |
| NFR-02 | Password hashing time | 200-300ms | Enough to slow attackers, fast enough for UX |
| NFR-03 | Token validation time | < 50ms | Validated on every request |
| NFR-04 | Concurrent authenticated users | 10,000 | V2 target |
| NFR-05 | Stateless authentication | No server-side session store | Horizontal scaling on Cloud Run |
| NFR-06 | Audit trail retention | 7 years | Enterprise compliance |
| NFR-07 | Password storage | Argon2 or BCrypt (cost ≥ 12) | Industry best practice |
| NFR-08 | Token signature | RS256 (asymmetric) | Enables future service extraction |
| NFR-09 | Rate limiting on auth endpoints | 10 req/min per IP | Prevent brute force |
| NFR-10 | Account lockout duration | 15 minutes (incremental) | Balance security and UX |

### 1.5 Business Rules

| Rule | Description |
|------|-------------|
| BR-01 | Email must be unique across all users (including deleted — prevents reuse) |
| BR-02 | Password must meet complexity policy (see Section 8) |
| BR-03 | Account locks after 5 consecutive failed login attempts within 15 minutes |
| BR-04 | Locked accounts unlock automatically after 15 minutes (or admin can manually unlock) |
| BR-05 | Email must be verified before first order can be placed |
| BR-06 | Verification token expires after 24 hours |
| BR-07 | Password reset token expires after 15 minutes |
| BR-08 | Token is single-use (consumed after verification/reset) |
| BR-09 | Refresh token expires after 7 days of inactivity |
| BR-10 | A user can have at most 10 active refresh tokens |
| BR-11 | A user can link at most 5 OAuth providers to one account |
| BR-12 | Soft-deleted accounts retain data for 90 days, then anonymized |
| BR-13 | Role hierarchy: SUPER_ADMIN > ADMIN > SUPPORT > all others |
| BR-14 | A user must have at least one role |
| BR-15 | Guest cart must be merged into user cart on login |

### 1.6 Future Requirements

| ID | Requirement | Planned Version |
|----|-------------|-----------------|
| FR-23 | Apple Login (Sign in with Apple) | V2 |
| FR-24 | Microsoft Login (Entra ID / Live) | V2 |
| FR-25 | Facebook Login | V2 |
| FR-26 | Mobile OTP Login (via SMS) | V2 |
| FR-27 | Multi-Factor Authentication (TOTP) | V2 |
| FR-28 | Multi-Factor Authentication (SMS OTP) | V2 |
| FR-29 | API Token management for programmatic access | V2 |
| FR-30 | Admin impersonation (login as user for support) | V2 |
| FR-31 | Session management UI (view/revoke devices) | V2 |
| FR-32 | Account recovery with identity verification | V3 |
| FR-33 | User consent management (GDPR) | V2 |
| FR-34 | Single Sign-On (SSO) for enterprise B2B | V3 |

---

## 2. Domain Analysis

### 2.1 Domain Overview

The Identity & Access Management domain is responsible for:

- **Who you are** (identity — registration, profile, authentication)
- **What you can do** (authorization — roles, permissions)
- **How you prove it** (credentials — passwords, OAuth tokens, JWTs)

The core concept is the **User Aggregate** — the consistency boundary within which all invariants (business rules that must always hold true) are enforced atomically.

---

### 📘 Learning Note: What is an Entity?

An **Entity** is an object that has a **continuous identity** — it remains the same object even if its attributes change.

- **What is it?** A domain object defined by its identity (usually an ID), not its attributes.
- **Why does it exist?** Because in real business domains, things change over time but need to be tracked as "the same thing."
- **Real-world analogy:** You are the same person from birth to adulthood, even though your height, weight, name (maybe), and knowledge change. Your identity (who you are) persists.
- **DSports-AI example:** A `User` is an Entity. The user's `firstName` might change (marriage), their `phone` might change, but it's still the same User identified by `userId`.
- **When NOT to use it:** If two objects with the same attributes are considered interchangeable, it's a Value Object, not an Entity. Example: `Money` — $10 is $10 regardless of which banknote you hold.
- **Interview explanation:** "Entities are objects in our domain that have a distinct identity that runs through time and different states. We identify them by an ID, not by their data. If you change the name on a User, it's still the same User. If you change the price on a Product, it's still the same Product."

#### Identity Module Entities

| Entity | Identifier | Mutable State | Description |
|--------|------------|---------------|-------------|
| **User** | `userId` (UUID) | Name, email, phone, status, password hash, roles, preferences | The person using the system. The central entity of the IAM module. |
| **Role** | `role` (enum) | None (immutable by design) | A named set of permissions. |
| **RefreshToken** | `tokenId` (UUID) | `revoked` flag | Represents an active user session. |
| **EmailVerificationToken** | `tokenId` (UUID) | `verifiedAt` | One-time use token for email verification. |
| **PasswordResetToken** | `tokenId` (UUID) | `usedAt` | One-time use token for password reset. |
| **UserAddress** | `addressId` (UUID) | All address fields | A physical address belonging to a user. |
| **LoginAttempt** | `attemptId` (UUID) | Immutable (append-only log) | Record of a login attempt (success or failure). |

---

### 📘 Learning Note: What is a Value Object?

A **Value Object** is an immutable object that describes some characteristic or attribute, with no identity.

- **What is it?** An object defined entirely by the value of its attributes. Two Value Objects with the same attributes are considered equal.
- **Why does it exist?** Primitive obsession — using raw strings/ints for domain concepts leads to scattered validation, unclear intent, and bugs. A Value Object encapsulates both data and behavior.
- **Real-world analogy:** An `Address` on a letter — if two letters have the same street, city, and zip, they go to the same place. The address doesn't have an "identity" — it's just a value.
- **DSports-AI example:** `Email` is a Value Object. It contains the email string, validates format on construction, provides methods like `getDomain()`, `isDisposable()`, and normalizes to lowercase.
- **When NOT to use it:** If you need to track the history of changes to the object, or if the object has a life cycle independent of its parent, it might be an Entity.
- **Interview explanation:** "Value Objects are immutable, self-validating domain primitives. Instead of passing around a raw String for email everywhere, I create an Email Value Object that guarantees any Email in the system is valid, normalized, and has domain-relevant behavior. They reduce bugs at the type level — a method that expects Email can't accidentally receive a random string."

#### Identity Module Value Objects

| Value Object | Fields | Behavior | Why not a primitive? |
|-------------|--------|----------|---------------------|
| **Email** | `value: String` | Validation, normalization to lowercase, domain extraction, disposable check | Raw strings can't enforce valid format; 100+ places would duplicate validation |
| **Password** | `hash: String` | Hash creation (BCrypt/Argon2), verification against plaintext | Raw strings invite plaintext storage; centralizes hashing algorithm choice |
| **PhoneNumber** | `countryCode`, `number` | E.164 formatting, validation | Different countries, different formats; centralizes normalization |
| **FullName** | `firstName`, `lastName` | Display name generation, validation | Separation enables personalized greeting ("Hi John") and formal use |
| **UserStatus** | Enum value | Transition validation (can ACTIVE → LOCKED? yes. Can DELETED → ACTIVE? no) | Invalid state transitions are bugs; an enum with transition rules prevents them |
| **RoleType** | Enum value | Hierarchy comparison (is SUPER_ADMIN > ADMIN? yes) | String roles invite typo bugs; hierarchy logic lives in one place |
| **AuthProvider** | Enum value: GOOGLE, EMAIL | Strategy selection | New providers = new enum value + new strategy class |
| **Address** | line1, line2, city, state, zip, country | Formatting, validation | Normalizes address across shipping, billing, and profiles |

---

### 📘 Learning Note: What is an Aggregate?

An **Aggregate** is a cluster of domain objects (Entities + Value Objects) that must be treated as a single unit for data changes. Each Aggregate has a **root** (the only object external clients can reference directly).

- **What is it?** A consistency boundary. Everything inside the boundary must be consistent when a transaction completes.
- **Why does it exist?** To enforce invariants (business rules that must always be true) within a well-defined boundary. Without aggregates, invariants leak across the system and become impossible to guarantee.
- **Real-world analogy:** A shopping cart and its items. If you remove the cart, the items should also be removed. You never add an item without a cart. The cart (Aggregate Root) is the "gatekeeper" — all operations go through it.
- **DSports-AI example:** The `User` is an Aggregate Root. It contains `UserAddresses` (entities), `RefreshTokens` (entities), and the current `UserStatus` (value object). If a user is deleted (soft-delete), ALL their addresses and tokens should become inactive. This invariant is enforced by the User Aggregate.
- **When NOT to use it:** When entities can exist independently. A `Product` and a `Category` are separate aggregates. A category can exist without a product; a product can be moved to a different category.
- **Interview explanation:** "Aggregates are transactional boundaries. I ask: 'When I save this object, what else must be saved atomically to maintain business rules?' That set of objects is the aggregate. The Aggregate Root is the only object clients interact with — they never get a direct reference to internal entities. This keeps the boundary clear and prevents inconsistent states."

#### Identity Aggregate

```
User (Aggregate Root)
├── userId (UUID)
├── email: Email (Value Object)
├── password: Password (Value Object, nullable for OAuth-only)
├── name: FullName (Value Object)
├── status: UserStatus (Value Object)
├── phone: PhoneNumber (Value Object, optional)
├── preferences: UserPreferences (Value Object)
│   ├── language
│   ├── timezone
│   ├── marketingConsent
│   └── aiPreferences
├── addresses: UserAddress[] (Entities)
│   └── Each address has its own identity
├── roles: RoleType[] (Value Objects)
├── refreshTokens: RefreshToken[] (Entities)
├── authProviders: AuthProviderLink[] (Entities)
│   └── provider, providerUserId, linkedAt
└── loginAttempts: LoginAttempt[] (Entities, append-only)
```

**Invariants enforced by the User Aggregate:**

1. Email must be unique across the system (database-level + domain validation)
2. A user must have at least one authentication method (password or OAuth provider)
3. A user cannot have more than 5 active refresh tokens
4. A user cannot be ACTIVE without a verified email (unless registered via trusted OAuth)
5. Status transitions must follow the state machine (Section 4)
6. At most 5 OAuth providers can be linked
7. Login attempts tracking resets on successful login

---

### 📘 Learning Note: What is a Domain Service?

A **Domain Service** is a stateless object that holds business logic that doesn't naturally fit inside an Entity or Value Object.

- **What is it?** An operation that involves multiple domain objects, or a computation that has no obvious "owner" entity.
- **Why does it exist?** To prevent "anemic domain models" where business logic leaks into application services or, worse, controllers.
- **Real-world analogy:** A bank transfer. Which Entity "owns" the transfer? The source account? The destination? Neither — the transfer logic is a service that coordinates both accounts.
- **DSports-AI example:** `AuthenticationService` orchestrates login across multiple providers. It takes an email and password (or an OAuth token), validates credentials against the right provider, checks the user's status, creates a JWT, and returns the result. This logic doesn't belong in the User Entity — the User shouldn't know how to authenticate itself.
- **When NOT to use it:** When the logic can be placed on an Entity or Value Object without violating Single Responsibility. Always prefer putting behavior on the Entity that "owns" it.
- **Interview explanation:** "I use Domain Services when an operation in the ubiquitous language involves multiple domain objects and doesn't have a natural home. The key test: if I ask 'Who does this belong to?' and the answer is ambiguous, it's probably a Domain Service. I keep them stateless and focused on a single business capability."

#### Identity Domain Services

| Domain Service | Responsibility | Why not on an Entity? |
|---------------|---------------|----------------------|
| **AuthenticationService** | Validates credentials, delegates to provider strategy, issues JWT, records attempt | Involves User, Provider, JwtIssuer, LoginAttempt — no single owner |
| **UserRegistrationService** | Creates user, sends verification email, logs event | Coordinates User creation, EmailService, EventPublisher |
| **PasswordValidationService** | Validates password against policy | Pure computation, no state — could even be a static method |
| **AccountLinkingService** | Links OAuth provider to existing email account | Coordinates User, AuthProvider, authentication validation |
| **UserLifecycleService** | Handles status transitions with business rules | Encapsulates the complex state machine logic across the User aggregate |

---

### 📘 Learning Note: What is a Domain Event?

A **Domain Event** captures something that happened in the domain that other parts of the system should know about.

- **What is it?** An immutable record of a past occurrence within the domain.
- **Why does it exist?** To decouple side effects from core business logic. When a user registers, multiple things need to happen (send email, log audit, update analytics). The registration logic shouldn't know about all of them.
- **Real-world analogy:** When a wedding happens (event), many things follow: marriage certificate issued, name change processed, bank accounts updated. The wedding ceremony itself doesn't need to know about bank processes.
- **DSports-AI example:** When a `UserRegisteredEvent` fires, the Notification module picks it up and sends the verification email. The Admin module picks it up and logs metrics. The Identity module doesn't need to import anything from Notification or Admin.
- **When NOT to use it:** For simple, synchronous side effects that are core to the operation. If sending the verification email is a functional requirement (not a side effect), consider keeping it in the service, not dispatching an event.
- **Interview explanation:** "Domain Events let my core domain communicate with the rest of the system without coupling. The Identity module publishes events without knowing who subscribes. In a modular monolith, these can be in-process events. In a distributed system, they'd be published to a message broker. The domain model stays clean either way."

#### Identity Domain Events

| Event | Trigger | Payload | Subscribers |
|-------|---------|---------|-------------|
| **UserRegisteredEvent** | User completes registration | userId, email, registrationMethod (EMAIL/GOOGLE) | Notification → send verification email; Shared → metrics |
| **EmailVerifiedEvent** | User clicks verification link | userId, email | Identity → activate user; Notification → send welcome email |
| **UserLoggedInEvent** | Successful login | userId, ipAddress, userAgent, provider | Shared → audit log, metrics |
| **UserLoginFailedEvent** | Failed login attempt | email (if exists), ipAddress, failureReason | Identity → increment failed count, lock if threshold reached |
| **UserLockedEvent** | Account locked | userId, reason, lockedUntil | Notification → send lockout notification email |
| **UserUnlockedEvent** | Account unlocked | userId, reason (auto/admin) | Notification → send unlock notification |
| **PasswordChangedEvent** | Password reset or change | userId | Identity → invalidate all refresh tokens; Notification → send confirmation |
| **AccountLinkedEvent** | OAuth provider linked | userId, provider | Shared → audit log |
| **AccountDeletedEvent** | User requests deletion | userId | All modules → anonymize or mark user data for cleanup |

---

### 📘 Learning Note: What is a Domain Policy (Specification Pattern)?

A **Domain Policy** (often implemented as the Specification pattern) is a business rule that can be evaluated independently and reused across contexts.

- **What is it?** A boolean evaluation that answers "does this object satisfy the rule?" — often with a descriptive reason when it doesn't.
- **Why does it exist?** To extract complex business rules from Entities/Services into focused, testable, composable objects.
- **Real-world analogy:** Airport security check. Instead of one massive checklist at the gate, there are separate policies: "valid passport?" "liquid < 100ml?" "no prohibited items?" Each is independently tested and can be combined.
- **DSports-AI example:** `PasswordComplexityPolicy` answers "does this password meet our requirements?" It's used during registration, password reset, and password change. It's independently testable with 10+ test cases.
- **When NOT to use it:** For simple validations (non-null, max length) that are handled by framework annotations. Use Specification for domain-specific rules that encode business decisions.
- **Interview explanation:** "Policies let me model business rules as first-class citizens. Instead of an if-else chain inside a service, I create a Policy object that tells me not just pass/fail, but WHY it failed. This makes the rules visible, testable, and changeable without touching the core logic."

#### Identity Domain Policies

| Policy | Rule | Used By |
|--------|------|---------|
| **PasswordComplexityPolicy** | Min 8 chars, upper + lower + digit + special, not common, not containing username | Registration, password reset, password change |
| **AccountLockoutPolicy** | Lock after 5 failures in 15 minutes, auto-unlock after 15 min | AuthenticationService |
| **EmailVerificationPolicy** | Token valid for 24 hours, single-use, user must be in REGISTERED status | UserRegistrationService |
| **PasswordResetPolicy** | Token valid for 15 minutes, single-use, user must be ACTIVE | PasswordResetService |
| **RoleAssignmentPolicy** | Only SUPER_ADMIN can assign SUPER_ADMIN; a user must have at least one role | UserLifecycleService |

---

### 📘 Learning Note: What is a Factory?

A **Factory** encapsulates the logic of creating complex domain objects, especially Aggregates.

- **What is it?** An object or method whose sole responsibility is creating other domain objects.
- **Why does it exist?** When constructing an Aggregate, multiple invariants must be satisfied, child entities created, and default values applied. Putting all this in a constructor leads to complex, hard-to-test construction logic.
- **Real-world analogy:** A passport office. You don't just "construct" a passport — you verify identity, check eligibility, create child records, and issue the document. The passport office (factory) handles this complexity.
- **DSports-AI example:** `UserFactory` creates a fully initialized User Aggregate. For email registration, it creates the User entity, sets status to REGISTERED, generates an EmailVerificationToken, and assigns the CUSTOMER role. For OAuth registration, it creates the User entity, sets status to ACTIVE (email is trusted), creates the AuthProviderLink, and assigns the CUSTOMER role.
- **When NOT to use it:** When the constructor is simple (plain data, no logic). If creating the object is just setting fields, a factory adds indirection without value.
- **Interview explanation:** "Factories in DDD are about encapsulating creation logic that involves domain rules. When creating an entity means making decisions about initial state, generating child objects, or enforcing invariants, a factory keeps the constructor clean and the creation logic testable in isolation."

#### Identity Factories

| Factory | Creates | Why a factory? |
|---------|---------|----------------|
| **UserFactory** | User Aggregate | Different creation paths (email vs OAuth) with different initial states |
| **OAuthUserFactory** | User from OAuth data | Extracts provider-specific data, normalizes to User model, creates AuthProviderLink |

---

## 3. Authentication Architecture

### 3.1 Option A: Email + Password

**How it works:**
1. User submits email + password
2. System looks up user by email
3. System verifies password hash using BCrypt/Argon2
4. On success: issue JWT access token + refresh token
5. On failure: increment failed login counter

**Advantages:**
- Complete control — no dependency on third-party services
- Works offline (no external API call during login)
- Universal — every user has an email
- Full audit trail without relying on external provider logs
- No cost per authentication (OAuth providers may rate-limit or charge at scale)
- Privacy — no data shared with Google/Facebook/Apple

**Disadvantages:**
- Password management burden — users forget passwords, need reset flows
- Security surface area — password hashing, brute force protection, credential stuffing
- Higher friction during registration — user must create and remember a password
- ~20% lower registration conversion compared to social login
- Requires email verification to prevent fake accounts

**Security considerations:**
- Hash with BCrypt (cost ≥ 12) or Argon2
- Rate-limit login attempts (10 req/min per IP)
- Account lockout after 5 failures
- No timing attacks — use constant-time comparison
- No password logging — ever, anywhere

### 3.2 Option B: Google OAuth

**How it works:**
1. User clicks "Sign in with Google"
2. Frontend redirects to Google's OAuth consent screen
3. User authenticates with Google (may already be logged in)
4. Google redirects back with authorization code
5. Backend exchanges code for ID token + access token
6. Backend validates token signature, extracts email + name
7. If new email → create account (auto-verified, ACTIVE)
8. If existing email → login (if already linked) or prompt for linking

**Advantages:**
- No password to remember — lower friction
- Higher conversion rate (30-50% improvement over email-only)
- Google handles security — MFA, suspicious login detection, password policies
- Email is pre-verified — no verification email needed
- Profile data (name, avatar) pre-populated
- Users trust the "Sign in with Google" button

**Disadvantages:**
- Vendor lock-in — Google's availability affects your login
- Google outage = your users can't log in
- Privacy concerns — users may not want to use Google
- Not universal — not everyone has a Google account
- Rate limits on token exchange endpoints
- Requires HTTPS (mandatory for OAuth)
- More complex initial setup (client ID, client secret, redirect URIs, consent screen)

**Security considerations:**
- Validate the ID token signature using Google's public keys (JWKS)
- Verify `aud` (audience) = your client ID
- Verify `iss` (issuer) = `https://accounts.google.com`
- Use PKCE (Proof Key for Code Exchange) — prevents authorization code interception
- Never trust the frontend — always exchange the code on the backend
- Store `sub` (Google's user ID) not just email — email can change in Google

### 3.3 Recommended Architecture

**Strategy Pattern with Abstract Authentication**

Instead of choosing one, we support BOTH through a common abstraction:

```
                     ┌─────────────────────────────┐
                     │    AuthenticationStrategy    │
                     │   (interface — the contract) │
                     ├─────────────────────────────┤
                     │  + authenticate(request)     │
                     │  + validate(credentials)     │
                     │  + identifier()              │
                     └──────────┬──────────────────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                   │
              ▼                 ▼                   ▼
   ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
   │EmailPasswordAuth │ │   GoogleOAuth    │ │  AppleOAuth    │
   │   Strategy       │ │   Strategy       │ │  Strategy (future)│
   ├──────────────────┤ ├──────────────────┤ ├──────────────────┤
   │ - email/password │ │ - OAuth token    │ │ - Apple token    │
   │ - BCrypt verify  │ │ - JWKS validation│ │ - JWKS validation│
   │ - rate limit     │ │ - profile fetch  │ │ - profile fetch  │
   └──────────────────┘ └──────────────────┘ └──────────────────┘
```

```java
// Conceptual contract — NOT code to implement
interface AuthenticationStrategy {
    AuthenticationResult authenticate(AuthenticationRequest request);
    boolean supports(AuthenticationRequest request);
    String provider();
}
```

**Why Strategy Pattern?**

| Concern | Email/Password | Google OAuth | Apple OAuth (future) |
|---------|---------------|--------------|---------------------|
| Credential validation | BCrypt hash check | JWKS signature verify | JWKS signature verify |
| User lookup | By email | By (provider, sub) | By (provider, sub) |
| Auto-create user | No | Yes (trusted provider) | Yes (trusted provider) |
| Email verification | Required | Not required (provider-verified) | Not required (provider-verified) |
| Profile data | From registration form | From Google token | From Apple token |
| Failure response | "Invalid credentials" | "Google authentication failed" | "Apple authentication failed" |

**Trade-off analysis:**

- **Complexity:** Strategy Pattern adds one interface + N implementations. Low overhead.
- **Extensibility:** Apple Login in V2? Add `AppleOAuthStrategy` implementing `AuthenticationStrategy`. Zero changes to existing code. Open/Closed Principle satisfied.
- **Testability:** Each strategy is independently testable with mocked external calls.
- **Runtime selection:** `AuthenticationService` selects strategy via `supports(request)` — can be a simple registry (`Map<String, AuthenticationStrategy>`).

**Recommendation:**

Use the Strategy Pattern with a shared `AuthenticationStrategy` interface. Start with two implementations:
1. `EmailPasswordStrategy` — email/password login
2. `GoogleOAuthStrategy` — Google Sign-In

Both feed into a single `AuthenticationService` that:
1. Receives the authentication request (contains provider type + credentials)
2. Selects the right strategy
3. Delegates authentication
4. Records the login attempt (success/failure)
5. Issues JWT + refresh token on success

This design adds ~15% effort upfront but eliminates 100% effort of redesigning when a new provider is added.

---

## 4. User Lifecycle

### 4.1 State Machine

```
                    ┌──────────────────────────────────────────┐
                    │                                          │
                    ▼                                          │
              ┌──────────┐    Register      ┌────────────────┐ │
              │  GUEST   │ ───────────────► │  REGISTERED     │ │
              │(browser) │                  │ (unverified)    │ │
              └──────────┘                  └───────┬────────┘ │
                                                    │           │
                                          Send      │           │
                                        verification│           │
                                          email     ▼           │
                                              ┌────────────────┐ │
                                              │  PENDING_       │ │
                                              │  VERIFICATION   │ │
                                              └───────┬────────┘ │
                                                      │           │
                                              Click    │           │
                                            verify link│          │
                                                      ▼           │
                                              ┌────────────────┐  │
                                   ┌────────► │    ACTIVE      │──┘
                                   │          └───┬────┬───────┘
                                   │              │    │
                                   │     5 failed │    │ Admin
                                   │     logins   │    │ disable
                                   │              ▼    │
                                   │     ┌────────────┐ │
                                   │     │   LOCKED   │ │
                                   │     └──────┬─────┘ │
                                   │            │       │
                                   │     Auto   │       │
                                   │    (15 min)│       │
                                   │     or     │       │
                                   │   admin    │       │
                                   │     unlock │       │
                                   │            ▼       ▼
                                   │     ┌──────────────────────┐
                                   │     │      DISABLED        │
                                   │     │  (admin action)      │
                                   │     └──────────┬───────────┘
                                   │                │
                                   │         Admin   │
                                   │         restore │
                                   │                ▼
                                   │     ┌──────────────────────┐
                                   │     │      ACTIVE           │
                                   │     └──────────────────────┘
                                   │
                                   │             Any state
                                   │           (user or admin)
                                   │                │
                                   │                ▼
                                   │     ┌──────────────────────┐
                                   └─────│      DELETED          │
                                         │   (soft delete)      │
                                         └──────────────────────┘
                                                      │
                                             90 days  │
                                             (cron)   │
                                                      ▼
                                         ┌──────────────────────┐
                                         │    ANONYMIZED        │
                                         │   (PII removed)     │
                                         └──────────────────────┘
```

### 4.2 State Transitions and Business Rules

| From | To | Trigger | Business Rules |
|------|----|---------|----------------|
| GUEST | REGISTERED | User submits registration form | Email unique, password meets policy, valid profile data |
| REGISTERED | PENDING_VERIFICATION | System sends verification email | Verification token generated with 24h expiry |
| PENDING_VERIFICATION | ACTIVE | User clicks verification link | Token valid, not expired, not already used |
| PENDING_VERIFICATION | ACTIVE | OAuth registration complete | OAuth provider is trusted (email pre-verified) |
| ACTIVE | LOCKED | 5 consecutive failed logins within 15 minutes | Failed attempts counter resets on successful login |
| ACTIVE | LOCKED | Admin manually locks | Admin must provide reason (audited) |
| LOCKED | ACTIVE | Auto-unlock after lockout duration | 15 minute lockout period elapsed |
| LOCKED | ACTIVE | Admin manually unlocks | Admin must provide reason (audited) |
| ACTIVE | DISABLED | Admin disables account | Admin must provide reason. User cannot login. Data preserved. |
| DISABLED | ACTIVE | Admin restores account | Admin must provide reason |
| ACTIVE | DELETED | User requests deletion | Soft delete — data retained for 90 days |
| ACTIVE | DELETED | Admin deletes account | Admin must provide reason; audit logged |
| LOCKED | DELETED | Admin deletes account | Same as above |
| DISABLED | DELETED | Admin deletes account | Same as above |
| DELETED | ANONYMIZED | Scheduled job (cron) after 90 days | Email replaced with hash, name removed, addresses anonymized |

### 4.3 State-Dependent Behavior

| Endpoint | GUEST | REGISTERED | PENDING_VERIFIC. | ACTIVE | LOCKED | DISABLED | DELETED |
|----------|-------|------------|------------------|--------|--------|----------|---------|
| Browse products | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| Add to cart | ✓ (guest) | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ |
| Checkout | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Login | N/A | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ |
| View profile | N/A | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| Change password | N/A | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| Delete account | N/A | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ |
| Admin override | N/A | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

---

## 5. Role Design

### 5.1 Role Hierarchy

```
SUPER_ADMIN ── Full system ownership
    │
    ├── ADMIN ── Operational administration
    │   ├── SUPPORT_EXECUTIVE ── Customer support
    │   ├── INVENTORY_MANAGER ── Stock management
    │   └── WAREHOUSE_MANAGER ── Warehouse operations
    │
    ├── FRANCHISE_OWNER ── Store partner (store-scoped)
    │
    ├── CUSTOMER_B2B ── Wholesale buyer
    │
    └── CUSTOMER ── Retail buyer (default registration role)
```

**Key principle:** Roles are additive and hierarchical. Higher roles inherit permissions of lower roles. A SUPER_ADMIN can do everything an ADMIN can do. An ADMIN can do everything a CUSTOMER can do.

### 5.2 Role Responsibilities

| Role | Scope | Responsibilities | Can Delegate To |
|------|-------|------------------|-----------------|
| **CUSTOMER** | Self | Browse catalog, add to cart, place orders, view own order history, manage own profile and addresses, create return requests, manage own cart | Nobody |
| **CUSTOMER_B2B** | Self + Company | Everything CUSTOMER can do + bulk ordering, credit limit management (within assigned limit), view invoices, company address book | Sub-users in their company |
| **FRANCHISE_OWNER** | Own store | Manage store inventory, view store sales reports, manage store orders, view store-specific analytics | Store staff |
| **WAREHOUSE_MANAGER** | Warehouse(s) | Manage physical inventory, process incoming shipments, process outgoing shipments, adjust stock levels (with reason), view inventory reports | Warehouse staff |
| **INVENTORY_MANAGER** | Global inventory | Monitor stock across all locations, set reorder thresholds, manage stock transfers between locations, reconcile inventory discrepancies | None (read-only delegation to warehouse) |
| **SUPPORT_EXECUTIVE** | Users + Orders | View user profiles (read-only), process return requests, handle order issues (cancel, modify shipping), communicate with customers via system | Nobody |
| **ADMIN** | System-wide | Manage products, categories, brands, users, and orders; view reports (sales, revenue, user growth); process refunds; manage site content | Support staff for limited actions |
| **SUPER_ADMIN** | System-wide + Configuration | Everything ADMIN can do + manage admins, assign roles, system configuration (payment gateways, tax rates, shipping rules), access audit logs, view all data without restriction, delete users | Admins for operational tasks |

### 5.3 Role Assignment Rules

| Rule | Description |
|------|-------------|
| RAR-01 | Every registered user gets CUSTOMER role by default |
| RAR-02 | Only SUPER_ADMIN can assign SUPER_ADMIN and ADMIN roles |
| RAR-03 | ADMIN can assign FRANCHISE_OWNER, SUPPORT_EXECUTIVE, INVENTORY_MANAGER, WAREHOUSE_MANAGER |
| RAR-04 | No role can be self-assigned |
| RAR-05 | A user can hold multiple roles (e.g., both CUSTOMER and FRANCHISE_OWNER) |
| RAR-06 | Role change is audited with who, what, when, and why |
| RAR-07 | At least one user must always have SUPER_ADMIN role (prevents lockout) |

---

## 6. Registration Flows

### 6.1 Email Registration Flow

```
User                    Frontend              Backend                     Database             Email Service
 │                        │                      │                          │                     │
 │  1. Submit form        │                      │                          │                     │
 │  (email, password,     │                      │                          │                     │
 │   firstName, lastName) │                      │                          │                     │
 │───────────────────────►│                      │                          │                     │
 │                        │  2. POST /register   │                          │                     │
 │                        │─────────────────────►│                          │                     │
 │                        │                      │  3. Validate email       │                     │
 │                        │                      │     format + uniqueness  │                     │
 │                        │                      │  4. Validate password    │                     │
 │                        │                      │     against policy       │                     │
 │                        │                      │  5. Hash password        │                     │
 │                        │                      │     (BCrypt cost 12)     │                     │
 │                        │                      │  6. Create User entity   │                     │
 │                        │                      │     (status: REGISTERED) │                     │
 │                        │                      │────────────────────────►│                     │
 │                        │                      │     INSERT user          │                     │
 │                        │                      │◄────────────────────────│                     │
 │                        │                      │                          │                     │
 │                        │                      │  7. Generate verification│                     │
 │                        │                      │     token (24h expiry)   │                     │
 │                        │                      │────────────────────────►│                     │
 │                        │                      │     INSERT email_verif   │                     │
 │                        │                      │◄────────────────────────│                     │
 │                        │                      │                          │                     │
 │                        │                      │  8. Publish              │                     │
 │                        │                      │     UserRegisteredEvent  │                     │
 │                        │                      │──────────────────────────│────────────────────►│
 │                        │                      │                          │                     │
 │                        │                      │                          │  9. Send verification│
 │                        │                      │                          │     email            │
 │                        │                      │                          │                     │
 │                        │  10. Return success  │                          │                     │
 │                        │◄─────────────────────│                          │                     │
 │ 11. Show "Check your   │                      │                          │                     │
 │     email" message     │                      │                          │                     │
 │◄───────────────────────│                      │                          │                     │
 │                        │                      │                          │                     │
 │ 12. User opens email   │                      │                          │                     │
 │     clicks verify link │                      │                          │                     │
 │──────────────────────────────────────────────►│                          │                     │
 │                        │                      │  13. Validate token      │                     │
 │                        │                      │      - exists?           │                     │
 │                        │                      │      - expired?          │                     │
 │                        │                      │      - already used?     │                     │
 │                        │                      │      - user in REGISTERED?│                     │
 │                        │                      │                          │                     │
 │                        │                      │  14. Update status       │                     │
 │                        │                      │      → ACTIVE            │                     │
 │                        │                      │  15. Mark token as used  │                     │
 │                        │                      │  16. Assign CUSTOMER role│                     │
 │                        │                      │────────────────────────►│                     │
 │                        │                      │                          │                     │
 │                        │                      │  17. Publish             │                     │
 │                        │                      │      EmailVerifiedEvent   │                     │
 │                        │                      │                          │                     │
 │                        │  18. Show success    │                          │                     │
 │                        │◄─────────────────────│                          │                     │
 │ 19. Redirect to login  │                      │                          │                     │
 │◄───────────────────────│                      │                          │                     │
```

### 6.2 Google OAuth Registration/Login Flow

```
User                     Frontend                    Backend                 Google              Database
 │                         │                          │                       │                   │
 │ 1. Click "Sign in      │                          │                       │                   │
 │    with Google"         │                          │                       │                   │
 │───────────────────────►│                          │                       │                   │
 │                         │ 2. Redirect to Google   │                       │                   │
 │                         │    OAuth consent screen  │                       │                   │
 │                         │────────────────────────────────────────────────►│                   │
 │                         │                          │                       │                   │
 │ 3. User authenticates   │                          │                       │                   │
 │    with Google          │                          │                       │                   │
 │◄──────────────────────────────────────────────────│                       │                   │
 │                         │                          │                       │                   │
 │ 4. Google redirects     │                          │                       │                   │
 │    with auth code       │                          │                       │                   │
 │───────────────────────►│                          │                       │                   │
 │                         │ 5. POST /auth/login     │                       │                   │
 │                         │    /google {code}        │                       │                   │
 │                         │─────────────────────────►│                       │                   │
 │                         │                          │ 6. Exchange code for  │                   │
 │                         │                          │    ID token           │                   │
 │                         │                          │──────────────────────────────────────────►│
 │                         │                          │◄──────────────────────────────────────────│
 │                         │                          │                       │                   │
 │                         │                          │ 7. Validate ID token  │                   │
 │                         │                          │    - signature (JWKS) │                   │
 │                         │                          │    - aud (client ID)  │                   │
 │                         │                          │    - iss (Google)     │                   │
 │                         │                          │    - exp (not expired)│                   │
 │                         │                          │                       │                   │
 │                         │                          │ 8. Extract:           │                   │
 │                         │                          │    - sub (Google UID) │                   │
 │                         │                          │    - email            │                   │
 │                         │                          │    - name             │                   │
 │                         │                          │    - picture          │                   │
 │                         │                          │                       │                   │
 │                         │                          │ 9. Check auth_providers│                  │
 │                         │                          │    WHERE provider=    │                   │
 │                         │                          │    GOOGLE AND         │                   │
 │                         │                          │    providerUserId=sub │                   │
 │                         │                          │──────────────────────►│                   │
 │                         │                          │                       │                   │
```

**Branch: New User (no existing link)**

```
 │                         │                          │                       │                   │
 │                         │                          │ 10. Check users by     │                   │
 │                         │                          │     email             │                   │
 │                         │                          │──────────────────────►│                   │
 │                         │                          │◄──────────────────────│ (not found)       │
 │                         │                          │                       │                   │
 │                         │                          │ 11. Create User        │                   │
 │                         │                          │     - status: ACTIVE   │                   │
 │                         │                          │       (Google-verified)│                   │
 │                         │                          │     - name from Google │                   │
 │                         │                          │     - avatar from Google│                  │
 │                         │                          │     - no password      │                   │
 │                         │                          │     (OAuth-only)       │                   │
 │                         │                          │ 12. Create             │                   │
 │                         │                          │     AuthProviderLink   │                   │
 │                         │                          │ 13. Assign CUSTOMER   │                   │
 │                         │                          │──────────────────────►│                   │
 │                         │                          │                       │                   │
 │                         │                          │ 14. Generate JWT      │                   │
 │                         │                          │ 15. Generate refresh  │                   │
 │                         │                          │──────────────────────►│                   │
 │                         │                          │                       │                   │
 │                         │  16. {jwt, refresh,     │                       │                   │
 │                         │      user, isNew: true}  │                       │                   │
 │                         │◄─────────────────────────│                       │                   │
 │ 17. Store JWT,          │                          │                       │                   │
 │     redirect to home    │                          │                       │                   │
 │◄────────────────────────│                          │                       │                   │
```

**Branch: Existing User (linked already)**

```
 │                         │                          │                       │                   │
 │                         │                          │ 10. auth_provider     │                   │
 │                         │                          │     FOUND → get userId│                   │
 │                         │                          │──────────────────────►│                   │
 │                         │                          │◄──────────────────────│                   │
 │                         │                          │                       │                   │
 │                         │                          │ 11. Check user status │                   │
 │                         │                          │     (must be ACTIVE)  │                   │
 │                         │                          │ 12. Generate JWT      │                   │
 │                         │                          │──────────────────────►│                   │
 │                         │                          │                       │                   │
 │                         │  13. {jwt, refresh,     │                       │                   │
 │                         │      user, isNew: false} │                       │                   │
 │                         │◄─────────────────────────│                       │                   │
```

### 6.3 Account Linking Flow (same email, different provider)

```
User                     Frontend                    Backend                 Database
 │                         │                          │                       │
 │ 1. User registers via   │                          │                       │
 │    email (existing      │                          │                       │
 │    account, ACTIVE)     │                          │                       │
 │                         │                          │                       │
 │ 2. User tries Google    │                          │                       │
 │    login next day       │                          │                       │
 │                         │  POST /auth/login/google │                       │
 │                         │─────────────────────────►│                       │
 │                         │                          │                       │
 │                         │                          │ 3. Google auth →      │
 │                         │                          │    email found in     │
 │                         │                          │    users table        │
 │                         │                          │    but auth_providers │
 │                         │                          │    has no GOOGLE link │
 │                         │                          │                       │
 │                         │                          │ 4. Return:            │
 │                         │                          │    "Account exists    │
 │                         │                          │    with this email.   │
 │                         │                          │    Link accounts?"    │
 │                         │                          │    + temp_token       │
 │                         │◄─────────────────────────│                       │
 │                         │                          │                       │
 │ 5. Show "Link accounts?"│                          │                       │
 │    dialog               │                          │                       │
 │◄────────────────────────│                          │                       │
 │                         │                          │                       │
 │ 6. User confirms        │                          │                       │
 │───────────────────────►│                          │                       │
 │                         │  POST /auth/link-account │                       │
 │                         │  {tempToken, password}  │                       │
 │                         │─────────────────────────►│                       │
 │                         │                          │                       │
 │                         │                          │ 7. Verify password    │
 │                         │                          │    (proves ownership  │
 │                         │                          │    of email account)  │
 │                         │                          │ 8. Create             │
 │                         │                          │    AuthProviderLink   │
 │                         │                          │──────────────────────►│
 │                         │                          │                       │
 │                         │                          │ 9. Generate JWT      │
 │                         │                          │──────────────────────►│
 │                         │                          │                       │
 │                         │  10. {jwt, refresh}     │                       │
 │                         │◄─────────────────────────│                       │
```

### 6.4 Forgot Password Flow

```
User                     Frontend                    Backend                 Database            Email Service
 │                         │                          │                       │                     │
 │ 1. Click "Forgot       │                          │                       │                     │
 │    Password"            │                          │                       │                     │
 │───────────────────────►│                          │                       │                     │
 │                         │  2. Enter email          │                       │                     │
 │                         │─────────────────────────►│                       │                     │
 │                         │                          │  3. Validate email    │                     │
 │                         │                          │     format            │                     │
 │                         │                          │  4. Check user exists │                     │
 │                         │                          │     (don't reveal if  │                     │
 │                         │                          │      not — security)  │                     │
 │                         │                          │  5. Generate reset    │                     │
 │                         │                          │     token (15 min)    │                     │
 │                         │                          │──────────────────────►│                     │
 │                         │                          │  6. Publish           │                     │
 │                         │                          │     PasswordResetEvent│                     │
 │                         │                          │───────────────────────────────────────────►│
 │                         │                          │                       │                     │
 │                         │  7. Return "If email     │                       │                     │
 │                         │      exists, check it"  │                       │                     │
 │                         │◄─────────────────────────│                       │                     │
 │ 8. Show generic success │                          │                       │                     │
 │    message              │                          │                       │                     │
 │◄────────────────────────│                          │                       │                     │
 │                         │                          │                       │                     │
 │ 9. User clicks reset    │                          │                       │                     │
 │    link in email        │                          │                       │                     │
 │──────────────────────────────────────────────────►│                       │                     │
 │                         │                          │ 10. Validate token    │                     │
 │                         │                          │     - exists?         │                     │
 │                         │                          │     - expired?        │                     │
 │                         │                          │     - already used?   │                     │
 │                         │                          │     - user is ACTIVE? │                     │
 │                         │                          │                       │                     │
 │                         │                          │ 11. Show password     │                     │
 │                         │                          │     reset form        │                     │
 │                         │                          │◄──────────────────────│                     │
 │                         │                          │                       │                     │
 │                         │  12. Submit new password │                       │                     │
 │                         │─────────────────────────►│                       │                     │
 │                         │                          │ 13. Validate password │                     │
 │                         │                          │     against policy    │                     │
 │                         │                          │ 14. Hash new password │                     │
 │                         │                          │ 15. Update user       │                     │
 │                         │                          │──────────────────────►│                     │
 │                         │                          │                       │                     │
 │                         │                          │ 16. Mark token used   │                     │
 │                         │                          │──────────────────────►│                     │
 │                         │                          │                       │                     │
 │                         │                          │ 17. Invalidate ALL    │                     │
 │                         │                          │     refresh tokens    │                     │
 │                         │                          │ 18. Publish           │                     │
 │                         │                          │     PasswordChangedEvent                   │
 │                         │                          │───────────────────────────────────────────►│
 │                         │                          │                       │                     │
 │                         │  19. "Password reset     │                       │                     │
 │                         │       successful"        │                       │                     │
 │                         │◄─────────────────────────│                       │                     │
 │ 20. Redirect to login   │                          │                       │                     │
 │◄────────────────────────│                          │                       │                     │
```

### 6.5 Returning User Flow (Email Login)

```
User                     Frontend                    Backend                 Database
 │                         │                          │                       │
 │ 1. Enter email +        │                          │                       │
 │    password             │                          │                       │
 │───────────────────────►│                          │                       │
 │                         │  POST /auth/login        │                       │
 │                         │  {email, password}       │                       │
 │                         │─────────────────────────►│                       │
 │                         │                          │                       │
 │                         │                          │ 2. Select strategy    │
 │                         │                          │    by provider type   │
 │                         │                          │    → EmailPassword    │
 │                         │                          │                       │
 │                         │                          │ 3. Lookup user by     │
 │                         │                          │    email (normalized) │
 │                         │                          │──────────────────────►│
 │                         │                          │◄──────────────────────│
 │                         │                          │                       │
 │                         │                          │ 4. User not found?    │
 │                         │                          │    → generic error    │
 │                         │                          │    (don't reveal)     │
 │                         │                          │                       │
 │                         │                          │ 5. Check user status  │
 │                         │                          │    LOCKED → "Account  │
 │                         │                          │    locked. Try again  │
 │                         │                          │    later or contact   │
 │                         │                          │    support."          │
 │                         │                          │    DISABLED → "Account│
 │                         │                          │    disabled. Contact  │
 │                         │                          │    support."          │
 │                         │                          │    DELETED → generic  │
 │                         │                          │    error              │
 │                         │                          │                       │
 │                         │                          │ 6. Verify password    │
 │                         │                          │    hash with BCrypt   │
 │                         │                          │                       │
 │                         │                          │ 7. FAILED:            │
 │                         │                          │    - Increment failed │
 │                         │                          │      attempt count    │
 │                         │                          │    - If count >= 5 →  │
 │                         │                          │      LOCKED (15 min)  │
 │                         │                          │    - Record LoginAttempt│
 │                         │                          │    - Return generic   │
 │                         │                          │      "Invalid credentials"│
 │                         │                          │                       │
 │                         │                          │ 8. SUCCESS:           │
 │                         │                          │    - Reset failed count│
 │                         │                          │    - Update last_login │
 │                         │                          │    - Record LoginAttempt│
 │                         │                          │    - Generate JWT      │
 │                         │                          │    - Generate refresh  │
 │                         │                          │──────────────────────►│
 │                         │                          │                       │
 │                         │  9. {jwt, refresh,      │                       │
 │                         │      user}               │                       │
 │                         │◄─────────────────────────│                       │
 │ 10. Store JWT,          │                          │                       │
 │     redirect to home    │                          │                       │
 │◄────────────────────────│                          │                       │
```

---

## 7. User Profile

### 7.1 Profile Schema

| Field | Type | Required | Mutable | Why it belongs here |
|-------|------|----------|---------|---------------------|
| **userId** | UUID | ✓ | ✗ | Primary identifier — generated, never changes |
| **email** | Email (VO) | ✓ | ✓ (with verification) | Primary identifier for login; changing requires reverification |
| **passwordHash** | String | Conditional | ✓ | Only for email-login users. Null for OAuth-only users |
| **firstName** | String | ✓ | ✓ | Required for personalization ("Hi John") and shipping labels |
| **lastName** | String | ✓ | ✓ | Required for formal communication and shipping labels |
| **phone** | PhoneNumber (VO) | ✗ | ✓ | Contact for delivery logistics; future OTP login |
| **avatarUrl** | URL | ✗ | ✓ | Profile display; pre-populated from OAuth providers |
| **status** | UserStatus | ✓ | Via lifecycle | Current user state; managed by state machine, not direct update |
| **preferredLanguage** | Locale | ✗ | ✓ | Localization — UI language, date formats, currency formatting |
| **timezone** | ZoneId | ✗ | ✓ | Correct date/time display (order times, promotions) |
| **createdAt** | Instant | ✓ | ✗ | Audit — when the account was created |
| **updatedAt** | Instant | ✓ | ✓ | Audit — last profile update |
| **lastLoginAt** | Instant | ✗ | System-updated | Security — detect unusual patterns |
| **deletedAt** | Instant | ✗ | System-set | Soft delete marker |

### 7.2 Addresses (Collection)

| Field | Type | Required | Why |
|-------|------|----------|-----|
| **addressId** | UUID | ✓ | Entity identity |
| **type** | Enum: HOME, WORK, SHIPPING, BILLING | ✓ | Separates use cases — shipping vs billing address can differ |
| **line1** | String | ✓ | Street address |
| **line2** | String | ✗ | Apartment, suite, building |
| **city** | String | ✓ | City for logistics |
| **state** | String | ✓ | State for logistics and tax computation (GST) |
| **zip** | String | ✓ | Postal code for logistics and tax computation |
| **country** | String (ISO 3166-1 alpha-2) | ✓ | Country for logistics; default "IN" for V1 |
| **phone** | PhoneNumber | ✓ for shipping | Contact number for delivery |
| **isDefault** | Boolean | ✗ | One address per type can be default (auto-selected during checkout) |

### 7.3 Communication Preferences

| Preference | Type | Default | Why |
|------------|------|---------|-----|
| **orderUpdates** | Boolean | true | Transactional — must be true for order confirmations and shipping updates |
| **promotionalEmails** | Boolean | true (opt-out) | Marketing — new products, sales, offers. Users can unsubscribe |
| **smsNotifications** | Boolean | false (opt-in) | Delivery updates, OTP. Requires phone number |
| **pushNotifications** | Boolean | false (opt-in) | Browser push — real-time order updates |

**Design decision:** Order updates are NOT optional. They are transactional, not marketing. GDPR allows transactional emails without consent. Promotional emails are opt-out by default (common in India) but must include unsubscribe link.

### 7.4 Marketing Preferences

| Preference | Type | Default | Why |
|------------|------|---------|-----|
| **newsletterSubscribed** | Boolean | true (opt-out) | Weekly product highlights, seasonal offers |
| **productRecommendations** | Boolean | true | Personalized recommendations on homepage and email |
| **dataForPersonalization** | Boolean | true | Use browsing history, order history for personalization |

### 7.5 Future AI Preferences

| Preference | Type | Default | Why |
|------------|------|---------|-----|
| **aiSearchEnabled** | Boolean | true | Enable AI-powered product search (V2) |
| **aiShoppingAssistant** | Boolean | true | Enable conversational shopping assistant (V3) |
| **aiRecommendations** | Boolean | true | Enable AI recommendation engine (V4) |
| **dataForTraining** | Boolean | false (opt-in) | Consent to use anonymized data for ML model training |

**Design rationale:** AI preferences default to enabled (product benefits) but with clear control. DataForTraining defaults to disabled (ethical AI — explicit consent required). All AI features respect these preferences at the domain level — the AI module queries them before processing.

---

## 8. Validation Rules

### 8.1 Email

| Rule | Rule Type | Error Code |
|------|-----------|------------|
| Must not be null or empty | Constraint | VALIDATION_REQUIRED |
| Must match RFC 5322 simplified pattern: `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$` | Format | VALIDATION_INVALID_EMAIL |
| Maximum 254 characters | Constraint | VALIDATION_MAX_LENGTH |
| Must not already exist in users table (case-insensitive) | Business Rule | AUTH_EMAIL_ALREADY_EXISTS |
| Normalized to lowercase before storage | Normalization | — |
| Must not be a disposable email domain (optional check) | Business Rule | VALIDATION_DISPOSABLE_EMAIL |

**Why:** Email is the primary user identifier. Uniqueness prevents duplicate accounts. Normalization prevents login issues (John@Example.com ≠ john@example.com). Disposable domain check prevents spam accounts.

### 8.2 Password

| Rule | Rule Type | Error Code |
|------|-----------|------------|
| Must not be null or empty | Constraint | VALIDATION_REQUIRED |
| Minimum 8 characters | Policy | AUTH_WEAK_PASSWORD |
| Maximum 128 characters | Constraint | VALIDATION_MAX_LENGTH |
| At least 1 uppercase letter (A-Z) | Policy | AUTH_WEAK_PASSWORD |
| At least 1 lowercase letter (a-z) | Policy | AUTH_WEAK_PASSWORD |
| At least 1 digit (0-9) | Policy | AUTH_WEAK_PASSWORD |
| At least 1 special character (!@#$%^&* etc.) | Policy | AUTH_WEAK_PASSWORD |
| Must not contain the email username part | Policy | AUTH_WEAK_PASSWORD |
| Must not be a common password (top 10,000 list) | Policy | AUTH_WEAK_PASSWORD |
| Must not be the same as current password (on change) | Policy | AUTH_PASSWORD_SAME |
| Future: Must not match last 3 passwords | Policy | AUTH_PASSWORD_REUSED |

**Why:** Complexity rules prevent dictionary attacks and credential stuffing. Common password check prevents "password123". Checking against email prevents predictable patterns. Password reuse prevention (future) prevents token compromise from affecting new sessions.

### 8.3 Phone

| Rule | Rule Type | Error Code |
|------|-----------|------------|
| Must match E.164 format: `^\+[1-9]\d{1,14}$` | Format | VALIDATION_INVALID_PHONE |
| Must be valid Indian mobile number for V1: `^\+91[6-9]\d{9}$` | Business Rule | VALIDATION_INVALID_PHONE |
| Maximum 15 digits | Constraint | VALIDATION_MAX_LENGTH |

**Why:** E.164 is the international standard for phone numbers. India-specific validation for V1 ensures deliverability. Format consistency is essential for SMS OTP (future).

### 8.4 Name

| Rule | Rule Type | Error Code |
|------|-----------|------------|
| firstName must not be null or empty | Constraint | VALIDATION_REQUIRED |
| lastName must not be null or empty | Constraint | VALIDATION_REQUIRED |
| Maximum 100 characters each | Constraint | VALIDATION_MAX_LENGTH |
| Must not contain HTML/script tags (XSS prevention) | Security | VALIDATION_INVALID_INPUT |

**Why:** Names are displayed throughout the system (UI, emails, shipping labels). XSS prevention is essential when rendering user-provided names. 100 characters accommodates long Indian names while preventing abuse.

### 8.5 Address

| Rule | Rule Type | Error Code |
|------|-----------|------------|
| line1 must not be null or empty | Constraint | VALIDATION_REQUIRED |
| Maximum 255 characters for line1, line2 | Constraint | VALIDATION_MAX_LENGTH |
| city must not be null or empty | Constraint | VALIDATION_REQUIRED |
| Maximum 100 characters for city | Constraint | VALIDATION_MAX_LENGTH |
| state must not be null or empty | Constraint | VALIDATION_REQUIRED |
| Maximum 100 characters for state | Constraint | VALIDATION_MAX_LENGTH |
| zip must not be null or empty | Constraint | VALIDATION_REQUIRED |
| zip must match format for country (IN: 6 digits `^\d{6}$`) | Business Rule | VALIDATION_INVALID_ZIP |
| country must be valid ISO 3166-1 alpha-2 code | Format | VALIDATION_INVALID_COUNTRY |
| phone must not be null for shipping type | Business Rule | VALIDATION_REQUIRED |
| Maximum 10 addresses per user | Business Rule | VALIDATION_MAX_COUNT |

**Why:** Address validation prevents failed deliveries. Country-specific zip format (India: 6-digit pincode) ensures logistics compatibility. Maximum 10 prevents abuse (address list as data store).

### 8.6 Validation Architecture

```
Request
   │
   ▼
┌───────────────────────────────────────────────────────┐
│  Framework Validation (Bean Validation / JSR-380)     │
│  ─────────────────────────────────────────────────    │
│  • @NotBlank, @Email, @Size, @Pattern                │
│  • Catches format errors early                       │
│  • Fast-fail — no need to reach domain               │
└───────────────────────┬───────────────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────────────┐
│  Application Validation (Use Case Level)              │
│  ────────────────────────────────────────────────     │
│  • Uniqueness checks (email already exists?)          │
│  • State validation (can this user do X?)            │
│  • Cross-field validation (password vs email)         │
│  • Calls Domain Policies                              │
└───────────────────────┬───────────────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────────────┐
│  Domain Validation (Policy Level)                     │
│  ────────────────────────────────────────────────     │
│  • PasswordComplexityPolicy.isSatisfiedBy(password)   │
│  • Returns NOT just boolean — returns reason          │
│  • Pure logic — no framework dependencies             │
└───────────────────────┬───────────────────────────────┘
                        │
                        ▼
┌───────────────────────────────────────────────────────┐
│  Infrastructure Validation (Database Level)           │
│  ────────────────────────────────────────────────     │
│  • UNIQUE constraint on email                         │
│  • NOT NULL constraints                               │
│  • Final safety net — should never fire if above work │
└───────────────────────────────────────────────────────┘
```

---

## 9. REST API Contract

### 9.1 Base URL

```
https://api.dsports.com/api/v1/
```

### 9.2 Versioning Strategy

- **Version prefix:** `/api/v1/` in the URL path
- **Version lifecycle:**
  - V1 is the current version
  - Breaking changes increment the version (e.g., v2)
  - Old versions are deprecated with 6 months notice
  - Header-based versioning not used — URL is explicit and cacheable

### 9.3 Authentication Endpoints

#### POST /api/v1/auth/register

Register a new user with email and password.

**Request:**
```json
{
  "email": "john.doe@example.com",
  "password": "SecurePass123!",
  "firstName": "John",
  "lastName": "Doe"
}
```

**Success Response (201):**
```json
{
  "status": "success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "email": "john.doe@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "status": "REGISTERED",
    "message": "Registration successful. Please check your email to verify your account."
  }
}
```

**Error Responses:**

| HTTP Status | Error Code | Condition |
|-------------|------------|-----------|
| 409 | AUTH_EMAIL_ALREADY_EXISTS | Email already registered |
| 422 | AUTH_WEAK_PASSWORD | Password fails policy |
| 422 | VALIDATION_INVALID_EMAIL | Invalid email format |
| 422 | VALIDATION_REQUIRED | Missing required field |

```json
{
  "status": "error",
  "errors": [
    {
      "code": "AUTH_EMAIL_ALREADY_EXISTS",
      "field": "email",
      "message": "An account with this email already exists"
    }
  ]
}
```

---

#### POST /api/v1/auth/login

Login with email and password.

**Request:**
```json
{
  "email": "john.doe@example.com",
  "password": "SecurePass123!"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "refreshToken": "dGhpcyBpcyBhIHJlZnJl...",
    "expiresIn": 900,
    "tokenType": "Bearer",
    "user": {
      "userId": "550e8400-e29b-41d4-a716-446655440000",
      "email": "john.doe@example.com",
      "firstName": "John",
      "lastName": "Doe",
      "roles": ["CUSTOMER"]
    }
  }
}
```

**Error Responses:**

| HTTP Status | Error Code | Condition |
|-------------|------------|-----------|
| 401 | AUTH_INVALID_CREDENTIALS | Wrong email or password |
| 403 | AUTH_ACCOUNT_LOCKED | Account locked due to failed attempts |
| 403 | AUTH_ACCOUNT_DISABLED | Account disabled by admin |
| 403 | AUTH_EMAIL_NOT_VERIFIED | Email not yet verified |
| 429 | AUTH_RATE_LIMITED | Too many attempts |

```json
{
  "status": "error",
  "errors": [
    {
      "code": "AUTH_ACCOUNT_LOCKED",
      "field": null,
      "message": "Account is temporarily locked due to multiple failed login attempts. Please try again later."
    }
  ]
}
```

---

#### POST /api/v1/auth/login/google

Login or register using Google OAuth.

**Request:**
```json
{
  "code": "4/0AX4XfWiS5a...",
  "redirectUri": "https://dsports.com/auth/callback"
}
```

**Success Response (200) — Returning User:**
```json
{
  "status": "success",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "refreshToken": "dGhpcyBpcyBhIHJlZnJl...",
    "expiresIn": 900,
    "tokenType": "Bearer",
    "isNewUser": false,
    "user": {
      "userId": "550e8400-e29b-41d4-a716-446655440000",
      "email": "john.doe@gmail.com",
      "firstName": "John",
      "lastName": "Doe",
      "avatarUrl": "https://lh3.googleusercontent.com/...",
      "roles": ["CUSTOMER"]
    }
  }
}
```

**Success Response (201) — New User:**
```json
{
  "status": "success",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "refreshToken": "dGhpcyBpcyBhIHJlZnJl...",
    "expiresIn": 900,
    "tokenType": "Bearer",
    "isNewUser": true,
    "user": {
      "userId": "660e8400-e29b-41d4-a716-446655440001",
      "email": "jane.doe@gmail.com",
      "firstName": "Jane",
      "lastName": "Doe",
      "avatarUrl": "https://lh3.googleusercontent.com/...",
      "roles": ["CUSTOMER"]
    }
  }
}
```

**Error Responses:**

| HTTP Status | Error Code | Condition |
|-------------|------------|-----------|
| 409 | AUTH_ACCOUNT_EXISTS | Email exists without Google link (see 6.3 Account Linking) |
| 401 | AUTH_INVALID_OAUTH_TOKEN | Invalid or expired OAuth code |

**Special Response — Account Linking Required (409):**
```json
{
  "status": "error",
  "errors": [
    {
      "code": "AUTH_ACCOUNT_EXISTS",
      "field": null,
      "message": "An account with this email already exists using email login. Would you like to link your Google account?",
      "linkingToken": "temp_abc123"
    }
  ]
}
```

---

#### POST /api/v1/auth/link-account

Link an OAuth provider to an existing email account.

**Request:**
```json
{
  "linkingToken": "temp_abc123",
  "password": "ExistingPassword123!"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "message": "Accounts linked successfully. You can now log in with either method.",
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "refreshToken": "dGhpcyBpcyBhIHJlZnJl..."
  }
}
```

---

#### POST /api/v1/auth/refresh

Refresh an expired access token.

**Request:**
```json
{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJl..."
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "accessToken": "eyJhbGciOiJSUzI1NiIs...",
    "refreshToken": "cm90YXRlZCByZWZyZXNo...",
    "expiresIn": 900,
    "tokenType": "Bearer"
  }
}
```

**Error Responses:**

| HTTP Status | Error Code | Condition |
|-------------|------------|-----------|
| 401 | AUTH_INVALID_TOKEN | Token not found or malformed |
| 401 | AUTH_EXPIRED_TOKEN | Token expired and cannot be refreshed (re-login required) |
| 401 | AUTH_TOKEN_REVOKED | Token was revoked (possible security issue) |

---

#### POST /api/v1/auth/forgot-password

Request a password reset email.

**Request:**
```json
{
  "email": "john.doe@example.com"
}
```

**Success Response (200):** (Always returns success — prevents email enumeration)
```json
{
  "status": "success",
  "data": {
    "message": "If an account with this email exists, a password reset link has been sent."
  }
}
```

---

#### POST /api/v1/auth/reset-password

Reset password using token from email.

**Request:**
```json
{
  "token": "reset_token_from_email",
  "newPassword": "NewSecurePass456!"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "message": "Password reset successful. Please log in with your new password."
  }
}
```

**Error Responses:**

| HTTP Status | Error Code | Condition |
|-------------|------------|-----------|
| 400 | AUTH_INVALID_TOKEN | Invalid or malformed token |
| 400 | AUTH_EXPIRED_TOKEN | Token has expired (> 15 minutes) |
| 400 | AUTH_TOKEN_ALREADY_USED | Token was already used |
| 422 | AUTH_WEAK_PASSWORD | New password fails policy |

---

#### POST /api/v1/auth/verify-email

Verify email address using token.

**Request:**
```json
{
  "token": "verification_token_from_email"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "message": "Email verified successfully. You can now log in."
  }
}
```

**Error Responses:**

| HTTP Status | Error Code | Condition |
|-------------|------------|-----------|
| 400 | AUTH_INVALID_TOKEN | Invalid or malformed token |
| 400 | AUTH_EXPIRED_TOKEN | Token has expired (> 24 hours) |
| 400 | AUTH_TOKEN_ALREADY_USED | Token was already used |

---

#### POST /api/v1/auth/resend-verification

Resend verification email.

**Request:**
```json
{
  "email": "john.doe@example.com"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "message": "If the account exists and is unverified, a new verification email has been sent."
  }
}
```

---

#### POST /api/v1/auth/logout

Invalidate the current refresh token.

**Headers:** `Authorization: Bearer <accessToken>`

**Request:**
```json
{
  "refreshToken": "dGhpcyBpcyBhIHJlZnJl..."
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "message": "Logged out successfully."
  }
}
```

### 9.4 User Profile Endpoints

#### GET /api/v1/users/me

Get the authenticated user's profile.

**Headers:** `Authorization: Bearer <accessToken>`

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "email": "john.doe@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "phone": "+919876543210",
    "avatarUrl": null,
    "status": "ACTIVE",
    "roles": ["CUSTOMER"],
    "preferredLanguage": "en-IN",
    "timezone": "Asia/Kolkata",
    "createdAt": "2026-07-01T10:30:00Z",
    "lastLoginAt": "2026-07-11T08:15:00Z",
    "communicationPreferences": {
      "orderUpdates": true,
      "promotionalEmails": true,
      "smsNotifications": false,
      "pushNotifications": false
    },
    "marketingPreferences": {
      "newsletterSubscribed": true,
      "productRecommendations": true,
      "dataForPersonalization": true
    },
    "aiPreferences": {
      "aiSearchEnabled": true,
      "aiShoppingAssistant": true,
      "aiRecommendations": true,
      "dataForTraining": false
    }
  }
}
```

#### PUT /api/v1/users/me

Update the authenticated user's profile.

**Request:**
```json
{
  "firstName": "John",
  "lastName": "Smith",
  "phone": "+919876543211",
  "preferredLanguage": "en-IN",
  "timezone": "Asia/Kolkata"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "firstName": "John",
    "lastName": "Smith",
    "phone": "+919876543211",
    "message": "Profile updated successfully."
  }
}
```

#### PUT /api/v1/users/me/password

Change password (requires current password).

**Request:**
```json
{
  "currentPassword": "OldPass123!",
  "newPassword": "NewPass456!"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "message": "Password changed successfully. You will need to log in again on other devices."
  }
}
```

#### GET /api/v1/users/me/addresses

Get all saved addresses.

**Success Response (200):**
```json
{
  "status": "success",
  "data": [
    {
      "addressId": "770e8400-e29b-41d4-a716-446655440002",
      "type": "HOME",
      "line1": "42, MG Road",
      "line2": "Indiranagar",
      "city": "Bangalore",
      "state": "Karnataka",
      "zip": "560038",
      "country": "IN",
      "phone": "+919876543210",
      "isDefault": true
    }
  ]
}
```

#### POST /api/v1/users/me/addresses

Add a new address.

**Request:**
```json
{
  "type": "WORK",
  "line1": "123, Brigade Road",
  "city": "Bangalore",
  "state": "Karnataka",
  "zip": "560001",
  "country": "IN",
  "phone": "+919876543210",
  "isDefault": false
}
```

**Success Response (201):**
```json
{
  "status": "success",
  "data": {
    "addressId": "880e8400-e29b-41d4-a716-446655440003",
    "message": "Address added successfully."
  }
}
```

### 9.5 Admin Endpoints

#### GET /api/v1/admin/users

List users with filtering, sorting, and pagination.

**Query Parameters:**
- `page` (default: 0)
- `size` (default: 20, max: 100)
- `status` (filter by UserStatus)
- `role` (filter by RoleType)
- `search` (search by email or name)
- `sort` (field: asc|desc, default: createdAt:desc)

**Success Response (200):**
```json
{
  "status": "success",
  "data": [
    {
      "userId": "550e8400-e29b-41d4-a716-446655440000",
      "email": "john.doe@example.com",
      "firstName": "John",
      "lastName": "Doe",
      "status": "ACTIVE",
      "roles": ["CUSTOMER"],
      "createdAt": "2026-07-01T10:30:00Z",
      "lastLoginAt": "2026-07-11T08:15:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 150,
    "totalPages": 8
  }
}
```

#### PUT /api/v1/admin/users/{userId}/status

Change user status (lock, unlock, disable, activate).

**Request:**
```json
{
  "status": "LOCKED",
  "reason": "Suspicious activity detected — multiple IP addresses"
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "previousStatus": "ACTIVE",
    "currentStatus": "LOCKED",
    "message": "User status updated successfully."
  }
}
```

#### PUT /api/v1/admin/users/{userId}/roles

Assign or remove roles.

**Request:**
```json
{
  "roles": ["CUSTOMER", "FRANCHISE_OWNER"]
}
```

**Success Response (200):**
```json
{
  "status": "success",
  "data": {
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "roles": ["CUSTOMER", "FRANCHISE_OWNER"],
    "message": "Roles updated successfully."
  }
}
```

### 9.6 Standard Envelope

All responses follow the standard envelope:
```json
{
  "status": "success" | "error",
  "data": { ... } | null,
  "meta": { ... } | null,    // Pagination info
  "errors": [ ... ] | null   // Error details
}
```

### 9.7 Error Code Reference

| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| AUTH_INVALID_CREDENTIALS | 401 | Wrong email or password |
| AUTH_ACCOUNT_LOCKED | 403 | Account temporarily locked |
| AUTH_ACCOUNT_DISABLED | 403 | Account disabled by admin |
| AUTH_EMAIL_NOT_VERIFIED | 403 | Email verification required |
| AUTH_INVALID_TOKEN | 401 | Token not found or malformed |
| AUTH_EXPIRED_TOKEN | 401 | Token has expired |
| AUTH_TOKEN_REVOKED | 401 | Token has been revoked |
| AUTH_TOKEN_ALREADY_USED | 400 | One-time token already consumed |
| AUTH_EMAIL_ALREADY_EXISTS | 409 | Email already registered |
| AUTH_WEAK_PASSWORD | 422 | Password fails policy |
| AUTH_ACCOUNT_EXISTS | 409 | Email exists without OAuth link |
| AUTH_INVALID_OAUTH_TOKEN | 401 | Invalid OAuth code |
| AUTH_RATE_LIMITED | 429 | Too many requests |
| AUTH_PASSWORD_SAME | 422 | New password same as current |
| VALIDATION_REQUIRED | 422 | Required field missing |
| VALIDATION_INVALID_EMAIL | 422 | Invalid email format |
| VALIDATION_INVALID_PHONE | 422 | Invalid phone format |
| VALIDATION_INVALID_ZIP | 422 | Invalid postal code |
| VALIDATION_INVALID_COUNTRY | 422 | Invalid country code |
| VALIDATION_INVALID_INPUT | 422 | Invalid input (XSS, etc.) |
| VALIDATION_MAX_LENGTH | 422 | Exceeds maximum length |
| VALIDATION_MAX_COUNT | 422 | Exceeds maximum collection size |
| VALIDATION_DISPOSABLE_EMAIL | 422 | Disposable email not allowed |
| USER_NOT_FOUND | 404 | User does not exist |
| FORBIDDEN | 403 | Insufficient permissions |

---

## 10. Database Design

### 10.1 Entity Relationship Diagram (High-Level)

```
┌──────────────────────┐
│        users         │
├──────────────────────┤
│ PK  id               │──┐
│     email (UQ)       │  │
│     password_hash    │  │
│     first_name       │  │
│     last_name        │  │
│     phone            │  │
│     avatar_url       │  │
│     status           │  │
│     last_login_at    │  │
│     failed_attempts  │  │
│     locked_until     │  │
│     preferred_lang   │  │
│     timezone         │  │
│     created_at       │  │
│     updated_at       │  │
│     deleted_at       │  │
└──────────────────────┘  │
                          │
                          ├─────────────────────────────────────┐
                          │                                     │
                          ▼                                     ▼
┌──────────────────────────────┐        ┌──────────────────────────────┐
│       user_roles             │        │      auth_providers          │
├──────────────────────────────┤        ├──────────────────────────────┤
│ PK  id                       │        │ PK  id                       │
│ FK  user_id                  │        │ FK  user_id                  │
│     role (enum)              │        │     provider (enum)          │
│     created_at               │        │     provider_user_id         │
│                              │        │     provider_email           │
│ (UQ: user_id + role)         │        │     created_at               │
└──────────────────────────────┘        │                              │
                                        │ (UQ: provider + provider_uid)│
                                        └──────────────────────────────┘
                          │
                          ├─────────────────────────────────────┐
                          │                                     │
                          ▼                                     ▼
┌──────────────────────────────┐        ┌──────────────────────────────┐
│     refresh_tokens           │        │      user_addresses          │
├──────────────────────────────┤        ├──────────────────────────────┤
│ PK  id                       │        │ PK  id                       │
│ FK  user_id                  │        │ FK  user_id                  │
│     token_hash (UQ)          │        │     type (enum)              │
│     expires_at               │        │     line1                    │
│     revoked                  │        │     line2                    │
│     device_info              │        │     city                     │
│     created_at               │        │     state                    │
│                              │        │     zip                      │
│ (UQ: token_hash)             │        │     country                  │
│ (IX: user_id)                │        │     phone                    │
└──────────────────────────────┘        │     is_default               │
                                        │     created_at               │
                                        │     updated_at               │
                                        │                              │
                                        │ (IX: user_id)                │
                                        └──────────────────────────────┘
                          │
                          ├─────────────────────────────────────┐
                          │                                     │
                          ▼                                     ▼
┌──────────────────────────────┐        ┌──────────────────────────────┐
│  email_verification_tokens   │        │   password_reset_tokens      │
├──────────────────────────────┤        ├──────────────────────────────┤
│ PK  id                       │        │ PK  id                       │
│ FK  user_id                  │        │ FK  user_id                  │
│     token_hash (UQ)          │        │     token_hash (UQ)          │
│     expires_at               │        │     expires_at               │
│     verified_at (nullable)   │        │     used_at (nullable)       │
│     created_at               │        │     created_at               │
│                              │        │                              │
│ (UQ: token_hash)             │        │ (UQ: token_hash)             │
│ (IX: user_id)                │        │ (IX: user_id)                │
└──────────────────────────────┘        └──────────────────────────────┘

┌──────────────────────────────┐
│       login_audit            │
├──────────────────────────────┤
│ PK  id                       │
│ FK  user_id (nullable)       │
│     email                    │
│     provider                 │
│     ip_address               │
│     user_agent               │
│     success                  │
│     failure_reason (nullable)│
│     created_at               │
│                              │
│ (IX: user_id + created_at)   │
│ (IX: created_at)             │
└──────────────────────────────┘
```

### 10.2 Table Design Decisions

| Decision | Why |
|----------|-----|
| **UUID primary keys** | No sequential IDs exposed in URLs; easier to shard/extract to microservices later |
| **token_hash stored, not token** | If the tokens table is leaked, tokens cannot be used (they only exist as client-side values) |
| **login_audit is append-only** | Never modify audit records — they are evidence |
| **user_id nullable in login_audit** | Failed attempts on non-existent emails should still be logged (track enumeration attempts) |
| **device_info in refresh_tokens** | Enables "show active sessions" feature — user sees "Chrome on Windows, 2 hours ago" |
| **deleted_at for soft delete** | GDPR data retention — data is preserved for 90 days, then anonymized |
| **verified_at nullable** | Null = not verified, non-null = verified at timestamp; avoids extra boolean |

### 10.3 Index Strategy

| Index | Type | Why |
|-------|------|-----|
| users.email | UNIQUE B-tree | Primary lookup — every login starts here |
| users.status | B-tree | Admin filtering: "show me all locked users" |
| users.deleted_at | B-tree | Soft-delete queries: "show me active users" (WHERE deleted_at IS NULL) |
| user_roles.user_id + role | UNIQUE composite | Prevents duplicate role assignment |
| auth_providers.provider + provider_user_id | UNIQUE composite | OAuth lookup: "has this Google user registered before?" |
| auth_providers.user_id | B-tree | "Find all OAuth providers linked to this user" |
| refresh_tokens.token_hash | UNIQUE B-tree | Token validation — every refresh request uses this |
| refresh_tokens.user_id | B-tree | "Revoke all tokens for this user" |
| email_verification_tokens.token_hash | UNIQUE B-tree | Verify lookup |
| password_reset_tokens.token_hash | UNIQUE B-tree | Reset lookup |
| login_audit.user_id + created_at | Composite B-tree | "Show recent login attempts for this user" |
| login_audit.created_at | B-tree | Cleanup: "delete audit records older than 7 years" |

### 10.4 Constraints

| Table | Constraint | Type | Why |
|-------|-----------|------|-----|
| users | email UNIQUE | UNIQUE | Business rule — no duplicate emails |
| users | email NOT NULL | NOT NULL | Business requirement |
| users | status NOT NULL (DEFAULT 'REGISTERED') | CHECK/DEFAULT | Every user starts in registered state |
| user_roles | UNIQUE(user_id, role) | UNIQUE | A user cannot have the same role twice |
| auth_providers | UNIQUE(provider, provider_user_id) | UNIQUE | A Google user cannot be linked twice |
| refresh_tokens | token_hash UNIQUE | UNIQUE | No two tokens can have the same hash (collision resistance) |
| all token tables | expires_at > created_at | CHECK | Token cannot expire before creation |
| all audit tables | created_at has DEFAULT | DEFAULT | Timestamp automated |

---

## 11. Security Review

### 11.1 Password Hashing

**Decision: BCrypt with cost factor 12**

| Algorithm | Status | Why |
|-----------|--------|-----|
| BCrypt | ✅ Chosen | Adaptive hash function with configurable cost; Spring Security native support |
| Argon2 | ⬜ Future upgrade | More memory-hard, resistant to GPU/ASIC attacks. Upgrade when Spring Security improves support |
| PBKDF2 | ❌ Rejected | Lower memory hardness; less resistant to GPU attacks |
| SHA-256 | ❌ Rejected | Fast to compute — attackers can try billions of passwords per second |
| MD5 | ❌ Rejected | Broken — can be cracked in milliseconds |

**Implementation rules:**
1. Hash on the server, never on the client
2. Use BCrypt's built-in salt (random, per-password)
3. Cost factor 12 = ~250ms per hash. Balance of security vs UX
4. Never log the password (even in error messages)
5. Never store the plaintext password (even temporarily)
6. Constant-time comparison for password verification (BCrypt handles this)

### 11.2 OAuth Readiness

**Design principles:**
1. State parameter with PKCE — prevents CSRF and authorization code interception
2. Code exchange on backend only — never trust the frontend with client secrets
3. Token validation against provider's JWKS endpoint
4. Each provider has its own Strategy implementation
5. Provider configuration (client_id, client_secret) comes from Secret Manager, not config files

**Supported in V1:**
- **Google OAuth 2.0** with OpenID Connect (OIDC)
  - Validate `aud` = our client ID
  - Validate `iss` = `https://accounts.google.com`
  - Validate `exp` not expired
  - Use `sub` (Google user ID) as the stable identifier

**Future providers will follow the same pattern:**
- Validate JWKS signature
- Extract `sub` (or equivalent stable ID)
- Extract `email` and `name`
- Map to our `AuthProvider` + `providerUserId` model

### 11.3 JWT Readiness

**Decision: RS256 (asymmetric signing)**

| Aspect | Design | Why |
|--------|--------|-----|
| Algorithm | RS256 (RSA + SHA-256) | Asymmetric — private key signs, public key verifies. Enables future service extraction |
| Alternatives | HS256 (rejected — symmetric, same key signs and verifies, can't share across services) |
| Access token expiry | 15 minutes | Short-lived limits damage if token is stolen |
| Refresh token expiry | 7 days | Balances UX (don't log in daily) and security |
| JWT claims | `sub` (userId), `roles`[], `iat`, `exp`, `jti` | Minimal — no sensitive data in token |
| JWKS endpoint | `/.well-known/jwks.json` | Future — other services can verify tokens without knowing the private key |
| Token in header | `Authorization: Bearer <token>` | Industry standard |
| Token storage (client) | HTTP-only cookie (preferred) or `Authorization` header | HTTP-only cookie prevents XSS from stealing token |

**JWT payload structure:**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "roles": ["CUSTOMER"],
  "iat": 1720692000,
  "exp": 1720692900,
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

### 11.4 Refresh Tokens

**Design:**

| Aspect | Decision | Why |
|--------|----------|-----|
| Storage | Hashed in database (SHA-256) | If database leaks, tokens cannot be used |
| Rotation | New token issued on every refresh; old one revoked | Limits window for token theft |
| Max active tokens | 10 per user | Prevents token hoarding; user must revoke old sessions |
| Expiry | 7 days | Balance security and UX |
| Revocation | Admin can revoke all tokens for a user | Security incident response |
| Device tracking | Store device_info (User-Agent) | User can identify and revoke specific sessions |

**Refresh token rotation flow:**
```
Client                    Backend                    Database
  │                         │                          │
  │ POST /auth/refresh      │                          │
  │ {refreshToken: "xxx"}  │                          │
  │────────────────────────►│                          │
  │                         │ Hash incoming token      │
  │                         │ Look up hash in DB       │
  │                         │────────────────────────►│
  │                         │◄────────────────────────│
  │                         │                          │
  │                         │ Verify:                  │
  │                         │ - Token exists?          │
  │                         │ - Token revoked?         │
  │                         │ - Token expired?         │
  │                         │                          │
  │                         │ Revoke OLD token         │
  │                         │────────────────────────►│
  │                         │                          │
  │                         │ Create NEW token         │
  │                         │ (rotate — new hash,      │
  │                         │  new expiry)             │
  │                         │────────────────────────►│
  │                         │                          │
  │ ◄────────────────────────│                          │
  │ {newAccessToken,        │                          │
  │  newRefreshToken}       │                          │
```

### 11.5 Session Management

**Design:** Stateless sessions via JWT.

| Aspect | Decision | Why |
|--------|----------|-----|
| Server-side session | None | Stateless — scales horizontally without shared session store |
| "Session" = refresh token | Yes | Each refresh token represents one active session |
| View sessions | GET /users/me/sessions | Returns list of active refresh tokens (device, last used) |
| Revoke session | DELETE /users/me/sessions/{id} | Marks single refresh token as revoked |
| Revoke all | Admin action on user | Marks all user's refresh tokens as revoked |

### 11.6 Future MFA Readiness

The architecture is designed to add MFA without redesign:

1. **User model already supports MFA:** Add fields: `mfa_enabled`, `mfa_secret` (for TOTP), `mfa_method` (TOTP, SMS)
2. **Authentication flow modification:** After successful primary auth, if `mfa_enabled`, issue a temporary token (valid 5 min) that requires MFA verification
3. **Strategy pattern extends:** `MfaAwareStrategy` interface extends `AuthenticationStrategy`
4. **Backup codes:** Generate 10 one-time backup codes on MFA enrollment, stored hashed

**Conceptual flow:**
```
POST /auth/login → password correct → mfa_enabled? YES → 200 {mfaRequired: true, mfaToken: temp_xxx}
POST /auth/mfa/verify → {mfaToken, code} → code valid → 200 {accessToken, refreshToken}
```

### 11.7 Additional Security Measures

| Measure | Implementation | Why |
|---------|---------------|-----|
| **Rate limiting** | 10 req/min per IP on /auth/* endpoints | Prevent brute force and credential stuffing |
| **Account lockout** | 5 failures → 15 min lock | Prevent brute force with incremental backoff |
| **Generic error messages** | "Invalid credentials" (not "email not found" or "wrong password") | Prevent email enumeration |
| **Constant-time comparison** | BCrypt handles this natively | Prevent timing attacks |
| **HTTPS only** | Enforced at Cloud Run load balancer | Encrypt all traffic |
| **CORS** | Whitelist known origins | Prevent unauthorized cross-origin requests |
| **Secret management** | Google Secret Manager (not env vars or config files) | Prevent credential leakage |
| **SQL injection** | Parameterized queries via R2DBC (never string concatenation) | Prevent data exfiltration |
| **XSS prevention** | Input sanitization on all user-provided text | Prevent script injection |
| **Audit logging** | All auth events logged with who, what, when, from IP | Forensic capability |
| **No sensitive data in JWT** | Only userId and roles in token | Token could be decoded (signed, not encrypted) |

---

## 12. Testing Strategy

### 12.1 Business Tests

These tests validate that the domain behaves correctly according to business requirements.

| Test Case | Scenario | Expected Behavior |
|-----------|----------|-------------------|
| User registration with valid data | Email + password + name | User created in REGISTERED state, verification token generated |
| User registration with existing email | Same email used twice | Error: AUTH_EMAIL_ALREADY_EXISTS |
| Email verification with valid token | Click link in email | Status changes to ACTIVE |
| Email verification with expired token | Wait > 24 hours | Error: AUTH_EXPIRED_TOKEN |
| Login with correct credentials | Valid email + password | JWT issued, refresh token created, failed attempts reset |
| Login with wrong password | Wrong password | Error: AUTH_INVALID_CREDENTIALS, failed attempt incremented |
| Account lockout after 5 failures | 5 wrong attempts in row | Account LOCKED, error: AUTH_ACCOUNT_LOCKED |
| Auto-unlock after lockout duration | Wait 15 minutes | Can log in again, failed attempts reset |
| Forgot password for existing email | Known email | Reset token generated, email sent |
| Forgot password for non-existing email | Unknown email | Generic success message (no enumeration) |
| Password reset with valid token | Valid token + new password | Password changed, all refresh tokens revoked |
| Password reset with expired token | Token > 15 min old | Error: AUTH_EXPIRED_TOKEN |
| Google OAuth new user | First time Google login | User created ACTIVE, AuthProviderLink created, CUSTOMER role assigned |
| Google OAuth returning user | Already linked Google | Login successful, JWT issued |
| Google OAuth account linking | Email exists, no Google link | 409 with linking token |
| Account linking confirmed | Correct password + linking token | AuthProviderLink created |
| Password change | Current + new password | Password updated, all other sessions revoked |
| User profile update | Change name and phone | Profile updated, timestamps updated |
| Address CRUD | Create, read, update, delete address | Address lifecycle managed correctly |
| Admin unlock | Admin unlocks locked user | Status changed to ACTIVE, audit logged |
| Admin disable | Admin disables user | Status DISABLED, user cannot log in |
| Admin role assignment | ADMIN assigns FRANCHISE_OWNER | Role added, user now has FRANCHISE_OWNER permissions |
| User deletion (soft delete) | User deletes own account | Status DELETED, data retained, cannot log in |
| Super Admin role protection | Super Admin tries to remove own SUPER_ADMIN role | Error: at least one SUPER_ADMIN must exist |

### 12.2 Validation Tests

These tests validate that input constraints are enforced.

| Test Case | Input | Expected Error |
|-----------|-------|----------------|
| Empty email | "" | VALIDATION_REQUIRED |
| Invalid email format | "notanemail" | VALIDATION_INVALID_EMAIL |
| Email too long | 255+ chars | VALIDATION_MAX_LENGTH |
| Missing password | null | VALIDATION_REQUIRED |
| Password too short | "Ab1!" (4 chars) | AUTH_WEAK_PASSWORD |
| Password no uppercase | "lowercase1!" | AUTH_WEAK_PASSWORD |
| Password no lowercase | "UPPERCASE1!" | AUTH_WEAK_PASSWORD |
| Password no digit | "NoDigits!" | AUTH_WEAK_PASSWORD |
| Password no special | "NoSpecial1" | AUTH_WEAK_PASSWORD |
| Password contains email username | email: "john@x.com", password: "JohnPass1!" | AUTH_WEAK_PASSWORD |
| Common password | "Password123" | AUTH_WEAK_PASSWORD |
| Invalid phone format | "12345" | VALIDATION_INVALID_PHONE |
| Empty first name | null | VALIDATION_REQUIRED |
| Empty last name | null | VALIDATION_REQUIRED |
| Address missing city | null city | VALIDATION_REQUIRED |
| Invalid zip code | "abc" | VALIDATION_INVALID_ZIP |
| Address XSS payload | `<script>alert(1)</script>` as name | VALIDATION_INVALID_INPUT |
| Too many addresses | 11th address | VALIDATION_MAX_COUNT |

### 12.3 Security Tests

These tests validate that security measures are effective.

| Test Case | Scenario | Expected Behavior |
|-----------|----------|-------------------|
| Password stored hashed | View database user row | password_hash is BCrypt hash (starts with $2a$12$) |
| No plaintext password in logs | Trigger registration error | Password never appears in log output |
| Token invalid after logout | Use revoked refresh token | Error: AUTH_TOKEN_REVOKED |
| Token expiry enforced | Use expired access token | Error: 401 Unauthorized |
| No email enumeration | Wrong email vs wrong password | Same generic error message |
| Rate limiting on login | 11 requests in 1 minute | Error: 429 Too Many Requests |
| Brute force lockout | 5 wrong passwords | Account locked after 5th attempt |
| JWT tamper detection | Modify JWT payload | Signature invalid → 401 |
| SQL injection attempt | Email: `' OR 1=1--` | Parameterized query rejects, no SQL injection |
| XSS in profile fields | `<img onerror=alert(1) src=x>` | Sanitized or rejected |
| Role escalation attempt | Customer tries admin endpoint | 403 Forbidden |
| OAuth token replay | Use same Google auth code twice | Second attempt returns error (code is one-time use) |
| Access another user's data | Change userId in request | 403 Forbidden or 404 (no data leak) |
| CORS violation | Request from unknown origin | Blocked by CORS policy |
| Refresh token replay | Use old refresh token after rotation | Token already revoked → error |

### 12.4 Negative Tests

These tests validate graceful handling of unexpected scenarios.

| Test Case | Scenario | Expected Behavior |
|-----------|----------|-------------------|
| Database connection failure | DB goes down during registration | 500 error, user-friendly message, logged |
| Google OAuth server down | Google returns 502 | Graceful error: "Google authentication temporarily unavailable" |
| Malformed JWT in request | Random string as token | 401 Unauthorized |
| Expired JWT | Token past expiry | 401 with AUTH_EXPIRED_TOKEN |
| Missing Authorization header | No header | 401 Unauthorized |
| Concurrent registration | Same email, simultaneous requests | One succeeds, one gets 409 (DB unique constraint) |
| Very long password | 200 characters | VALIDATION_MAX_LENGTH |
| Very long name | 500 characters | VALIDATION_MAX_LENGTH |
| Unicode in email | "üser@example.com" | Depends on policy — validate or reject |
| Empty request body | POST /auth/login with `{}` | VALIDATION_REQUIRED for all mandatory fields |
| Invalid JSON | Malformed request body | 400 Bad Request |

### 12.5 Integration Tests

These tests validate the full flow across layers.

| Test Case | Flow | Validates |
|-----------|------|-----------|
| Complete registration flow | Register → verify email → login → access protected resource | Controller → Service → Domain → Repository → Database |
| OAuth registration flow | Google OAuth → auto-create → login → access resource | Strategy → Service → Repository |
| Password reset flow | Forgot password → receive token → reset → login with new password | Full lifecycle |
| Account linking flow | Email register → Google login (link) → confirm → login with Google | Cross-provider linking |
| Refresh token rotation | Login → refresh → verify old token revoked, new token valid | Token lifecycle |
| Account lockout → unlock | 5 failed logins → locked → wait → login succeeds | State machine |
| Admin user lifecycle | Create → activate → lock → unlock → disable → restore → delete | Admin workflows |
| Concurrent token management | 10 active sessions → try 11th → oldest revoked | Business rule enforcement |
| Guest → user cart merge | Browse as guest (add items) → register → login → cart preserved | Cross-module (Identity + Cart) |
| Multi-role access | User with CUSTOMER + FRANCHISE_OWNER → access both sets of endpoints | Role-based authorization |

### 12.6 Test Pyramid

```
Integration Tests (15%)
├── Full registration flow
├── OAuth flow
├── Password reset flow
├── Account linking
├── Token rotation
└── Admin workflows

Domain/Service Tests (60%)
├── AuthenticationService
├── UserRegistrationService
├── PasswordValidationService
├── AccountLockoutPolicy
├── PasswordComplexityPolicy
├── UserFactory
└── All Value Object tests

Unit Tests (25%)
├── Value Objects (Email, Password, Phone, FullName, Address)
├── UserStatus transitions
├── Role hierarchy
└── Utility functions
```

---

## Document Status

**Version:** 1.0  
**Status:** Pending Architecture Review  
**Reviewers Required:**
- [ ] Product Owner — validate business requirements
- [ ] Senior Developer — validate technical feasibility
- [ ] Security Lead — validate security decisions
- [ ] QA Lead — validate test strategy

**Next Steps:**
1. Architecture Review (scheduled)
2. Implementation Sprint Planning
3. ADR for Identity Module design decisions
4. Sprint 1 development begins

---

*End of Document*
