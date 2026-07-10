# DSports-AI — Software Architecture Document

**Version:** 2.0  
**Author:** Senior Solution Architect  
**Status:** Approved for Implementation  

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Principles](#2-architecture-principles)
3. [System Context & High-Level Architecture](#3-system-context--high-level-architecture)
4. [Module Decomposition (Bounded Contexts)](#4-module-decomposition-bounded-contexts)
5. [Service Strategy](#5-service-strategy)
6. [Technology Stack with Rationale](#6-technology-stack-with-rationale)
7. [High-Level Database Design](#7-high-level-database-design)
8. [API Design Philosophy](#8-api-design-philosophy)
9. [Security Architecture](#9-security-architecture)
10. [Deployment Architecture](#10-deployment-architecture)
11. [Folder and Repository Structure](#11-folder-and-repository-structure)
12. [Coding Standards](#12-coding-standards)
13. [Implementation Roadmap](#13-implementation-roadmap)
14. [Development Roadmap — Time-Boxed](#14-development-roadmap--time-boxed)
15. [What NOT to Build in Version 1](#15-what-not-to-build-in-version-1)
16. [Risk Register](#16-risk-register)
17. [Scalability Strategy](#17-scalability-strategy)
18. [Architectural Decision Records](#18-architectural-decision-records)
19. [Future AI Roadmap](#19-future-ai-roadmap)

---

## 1. Executive Summary

DSports-AI is a greenfield, enterprise-grade, AI-first sports e-commerce platform targeting the Indian market (INR, English). Version 1 launches with cricket equipment and apparel, serving B2C consumers, B2B wholesale buyers, and franchise store partners.

The architecture follows **Domain-Driven Design (DDD)** with **Clean Architecture** boundaries, implemented as a **modular monolith**. We build the simplest solution that satisfies today's business needs while keeping the architecture open for tomorrow.

**Key architectural goals:**
- Preserve optionality — no decision locks out future expansion (multi-sport, marketplace, AI)
- Domain isolation — each bounded context owns its data, logic, and persistence
- API-first — all capabilities exposed via RESTful APIs consumed by a React SPA
- Cloud-native — designed for Docker and Cloud Run
- AI-ready — empty module boundaries exist so AI features slot in without refactoring

---

## 2. Architecture Principles

| # | Principle | Rationale |
|---|-----------|-----------|
| 1 | **Domain boundaries first** — modules map to business subdomains, not technical layers | Enables future service extraction without rewriting |
| 2 | **Persistence encapsulation** — no module directly accesses another module's database | Prevents coupling; the database is an implementation detail |
| 3 | **API contract over shared code** — modules communicate via interfaces, not shared classes | Reduces coupling, enables polyglot futures |
| 4 | **Stateless horizontal scale** — every instance is identical; state lives in the database | Achieves target 10,000 concurrent users via replication |
| 5 | **Fail-fast for externals** — payment and email adapters have timeouts and clear fallbacks | Prevents external failures from cascading |
| 6 | **Security by design** — auth, input validation at every layer | Non-negotiable for payment-adjacent systems |
| 7 | **Observability as a feature** — structured logging and metrics from day one | Impossible to retrofit at scale |
| 8 | **Localization-ready** — i18n and multi-currency are infrastructure, not afterthoughts | Avoids rewrite when expanding beyond India |
| 9 | **Simplest solution today, open for tomorrow** — build for current needs; avoid abstractions until they pay for themselves | Prevents over-engineering; preserves optionality without premature complexity |

---

## 3. System Context & High-Level Architecture

### 3.1 System Context (C4 Level 1)

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│  Customer    │     │  Franchise   │     │   Admin /    │
│  (B2C/B2B)   │     │    Owner     │     │ Warehouse Mgr│
└──────┬───────┘     └──────┬───────┘     └──────┬───────┘
       │                    │                     │
       ▼                    ▼                     ▼
┌─────────────────────────────────────────────────────┐
│               DSports-AI  Platform                    │
│           (Modular Spring Boot Monolith)              │
└──────┬─────────────────────────────────────────┬──────┘
       │                                         │
       ▼                                         ▼
┌──────────────┐                       ┌──────────────────┐
│   Mock       │                       │  Email Provider   │
│   Payment    │                       │  (SendGrid/SMTP)  │
│   (Dev only) │                       │                   │
└──────────────┘                       └──────────────────┘
```

### 3.2 Container Diagram (C4 Level 2)

```
┌──────────────────────────────────────────────────────────────────┐
│                  React SPA (Vite + TypeScript + MUI)              │
│                         Cloud Run                                 │
└────────────────────────────┬─────────────────────────────────────┘
                             │ HTTPS / future JWT
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                    Spring Boot Modular Monolith                    │
│                    (Cloud Run — auto-scale 1-10)                   │
│                                                                    │
│  ┌──────────┐  ┌──────────┐  ┌────────┐  ┌───────────┐           │
│  │ Identity │  │ Catalog  │  │  Cart   │  │   Order   │           │
│  │ Module   │  │ Module   │  │ Module  │  │   Module  │           │
│  └──────────┘  └──────────┘  └────────┘  └─────┬─────┘           │
│  ┌──────────┐  ┌──────────┐  ┌────────┐       │                 │
│  │ Payment  │  │ Billing  │  │ Returns│       │                 │
│  │ Module   │  │ Module   │  │ Module │       │                 │
│  └──────────┘  └──────────┘  └────────┘       │                 │
│  ┌──────────┐  ┌──────────┐  ┌────────┐       │                 │
│  │Franchise │  │Inventory │  │  AI    │       │                 │
│  │ Module   │  │ Module   │  │ Module │       │                 │
│  └──────────┘  └──────────┘  └────────┘       │                 │
│                                    ┌──────────┴──────────┐      │
│                                    │  Notification       │      │
│                                    │  Module             │      │
│                                    └─────────────────────┘      │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
                 ┌─────────────────────┐
                 │  Cloud SQL           │
                 │  PostgreSQL 16       │
                 └─────────────────────┘
```

### 3.3 Module Interaction Pattern

Modules communicate **only through Java interfaces** defined in a shared `api` package. No module directly calls another module's repository, service implementation, or entity. This maintains hexagonal architecture within the monolith.

Cross-module calls are synchronous method calls through interface contracts. This keeps things simple for V1 while allowing future extraction to HTTP/gRPC without changing domain logic.

---

## 4. Module Decomposition (Bounded Contexts)

Each module follows Clean Architecture layers:
- **Domain** — entities, value objects, repository interfaces
- **Application** — use cases, DTOs, ports (inbound/outbound interfaces)
- **Infrastructure** — adapters (R2DBC repositories, REST clients)
- **Interfaces/API** — REST controllers, request/response DTOs, mappers

### 4.1 Version 1 Modules

| Module | Responsibility | Key Entities | Owns Data? |
|--------|---------------|--------------|------------|
| **identity** | User registration, authentication, role management | User, Role, RefreshToken | Yes |
| **catalog** | Products, categories, brands, variants | Product, Variant, Category, Brand | Yes |
| **inventory** | Stock tracking for variants | Inventory (current_stock, available_stock) | Yes |
| **cart** | Shopping cart lifecycle | Cart, CartItem | Yes |
| **order** | Order creation, lifecycle, state machine | Order, OrderItem, OrderAddress, OrderStatusHistory | Yes |
| **payment** | Payment abstraction, mock provider, future Razorpay | PaymentTransaction | Yes |
| **billing** | Invoice generation, tax computation | Invoice | Yes |
| **returns** | Return requests, RMA flow | ReturnRequest, ReturnItem | Yes |
| **franchise** | Store management, basic franchise inventory | FranchiseStore, FranchiseInventory | Yes |
| **notification** | Email dispatch | Notification | No |
| **admin** | Admin dashboard APIs, reports | — (reads from other modules) | No |
| **ai** | Empty placeholder for future AI features | — | No |

### 4.2 Dependency Rules

```
identity ← catalog ← cart ← order ← payment
    ↑         ↑                    ↓       ↓
    |    franchise ──────────► order     billing
    |         ↑                          ↓
    |    returns ◄────────────────── payment
    |                                   |
    └────── notification ◄──────────────┘
```

- Dependencies flow **inward** toward identity.
- No circular dependencies.
- Cross-module queries use dedicated query interfaces, not direct repository access.

### 4.3 AI Module (Placeholder)

The `ai` module exists as an empty package structure with no implementation:

```
com.dsports.ai/
├── domain/          # Future: AIQuery, SearchResult, Recommendation
├── application/     # Future: SearchService, RecommendationService
└── infrastructure/  # Future: OpenAIAdapter, EmbeddingClient, VectorStore
```

This ensures the package structure exists and module dependencies can be planned without refactoring when AI features begin.

---

## 5. Service Strategy

### 5.1 Version 1: Modular Monolith

**Decision:** Start and remain a modular monolith for V1.

**Rationale:**
- ~100 concurrent users does not warrant distributed system complexity
- Single developer cannot manage 12 microservices operationally
- Monolith enables faster iteration and single-step debugging
- The modular design guarantees each context CAN be extracted as a service later
- No distributed transactions, no network latency, no eventual consistency headaches

### 5.2 Future: Service Extraction

When ONE of these thresholds is met, extract the relevant module:

| Trigger | Action |
|---------|--------|
| Module needs independent scaling | Extract to dedicated Cloud Run service |
| Module requires different data store | Extract to own service |
| Deployment cycle conflicts arise | Extract along ownership boundaries |
| AI features become latency-sensitive | Extract AI as standalone service |

Extraction is low-risk because every module already communicates through interfaces. No domain logic is rewritten — only the transport layer changes.

---

## 6. Technology Stack with Rationale

### 6.1 Backend — Version 1

| Technology | Decision | Why |
|------------|----------|-----|
| Java 21 | Latest LTS | Virtual threads, pattern matching; long-term support |
| Spring Boot 3.x | Application framework | Ecosystem maturity; reactive support |
| Spring WebFlux | Reactive stack | Non-blocking I/O suits future AI streaming; same stack throughout |
| Spring Data R2DBC | Database access | Fully reactive; no blocking JDBC calls; native PostgreSQL support |
| Spring Security + JWT | AuthN/AuthZ | Industry standard; stateless auth for cloud-native apps |
| Maven | Build tool | Stable, predictable, your stated preference |
| PostgreSQL 16 | Primary database | JSONB for flexible attributes; full-text search; strong consistency |
| Flyway | Schema migrations | SQL-first; works with R2DBC (migrations run before reactive startup) |
| JUnit 5 + Mockito | Testing | Standard for Spring ecosystem |
| OpenAPI/Swagger | API documentation | Auto-generated from annotations |
| Lombok | Boilerplate reduction | Your stated preference |

### 6.2 Backend — Future (NOT in V1)

| Technology | When | Why |
|------------|------|-----|
| Spring AI | V2+ | AI integration (OpenAI, Gemini, Ollama) |
| Kafka | Post-launch | Async events, AI training pipelines |
| Redis | Post-launch | Caching, rate-limiting, session store |
| Elasticsearch | V2+ | Advanced product search |
| Spring Cloud Gateway | Post-launch | API gateway if extracting services |

### 6.3 Frontend

| Technology | Why |
|------------|-----|
| React 18+ | Industry standard, large ecosystem |
| TypeScript | Type safety at scale |
| Vite | Fast dev server, optimized builds |
| React Router v6 | Declarative routing |
| Material UI | Enterprise-grade component library |
| React Query | Server state management, caching |
| Zustand | Lightweight client state (auth UI, cart UI) |
| Axios | HTTP client with interceptors |

### 6.4 Infrastructure — Version 1

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Cloud | GCP | Cloud Run fits monolith |
| Compute | Cloud Run | Serverless removes ops burden; auto-scaling |
| Database | Cloud SQL (PostgreSQL) | Managed PostgreSQL |
| CI/CD | GitHub Actions | Co-located with monorepo |
| Container | Docker | Required by Cloud Run |
| Monitoring | Cloud Logging + Cloud Monitoring | GCP-native; no extra cost at low volume |

### 6.5 Localization Strategy

- **Backend:** `LocaleContextHolder` in Spring — all text/formatting parameterized by locale
- **Database:** Products store localized fields as JSONB column (`name_translations`, `description_translations`)
- **Currency:** All monetary values stored as `BIGINT` (paise) with currency code; exchange-rate table for multi-currency display
- **Frontend:** `react-i18next` with lazy-loaded translation files

---

## 7. High-Level Database Design

### 7.1 R2DBC Considerations

Spring Data R2DBC differs from JPA in important ways:
- No automatic relationship mapping (`@OneToMany`, `@ManyToOne`, lazy loading)
- No cascading operations — relationships are loaded explicitly via repository queries
- Entities use `@Table` instead of `@Entity`
- Repositories extend `ReactiveCrudRepository` or `R2dbcRepository`
- All database operations return `Mono<T>` or `Flux<T>`
- Aggregate roots are assembled manually in repository implementations

This aligns well with DDD — aggregates are loaded explicitly, and you only fetch what you need.

### 7.2 Entity-Relationship Overview

```
┌───────────┐     ┌──────────────┐     ┌─────────────┐
│   User    │1──N│   Address    │     │   Category   │
└───┬───────┘     └──────────────┘     └──────┬──────┘
    │                                         │
    │ 1                                       │ 1
    │                                         │
    ▼                                         ▼
┌───────────┐     ┌──────────────────┐     ┌─────────────┐
│   Cart    │1──N│    CartItem      │     │   Product   │N──1│ Brand │
└───┬───────┘     └──────────────────┘     └──────┬──────┘     └───────┘
    │                                             │
    └──User has one active cart                   │ 1
                                                  │
     ┌────────────────────────────────────────────┤
     │                    │                       │
     ▼                    ▼                       ▼
┌─────────┐       ┌──────────────┐       ┌───────────────┐
│  Order  │1──N──│  OrderItem   │       │ProductVariant │N──1
└──┬──────┘       └──────────────┘       └───────┬───────┘
   │                                              │
   │ 1                                            │ 1
   ▼                                              ▼
┌──────────────┐                          ┌──────────────┐
│OrderAddress  │                          │  Inventory   │
└──────────────┘                          └──────────────┘

┌──────────────┐     ┌────────────────────┐
│OrderStatusHist│────│ PaymentTransaction  │
└──────────────┘     └────────┬────────────┘
                              │
                              ▼
                      ┌──────────────┐
                      │   Invoice    │
                      └──────────────┘

┌──────────────┐     ┌──────────────┐
│ReturnRequest │1──N│  ReturnItem   │
└──────┬───────┘     └──────────────┘
       │
       ▼
┌──────────────┐
│   Refund     │
└──────────────┘

┌──────────────────┐     ┌──────────────────────┐
│ FranchiseStore   │1──N│ FranchiseInventory    │
└──────────────────┘     └──────────────────────┘
```

### 7.3 Key Entity Design Decisions

| Entity | Design | Rationale |
|--------|--------|-----------|
| **User** | Roles as enum (CUSTOMER, FRANCHISE_OWNER, ADMIN, WAREHOUSE_MGR, SUPER_ADMIN) | RBAC is well-understood; no dynamic roles in V1 |
| **Product** | `base_price`, `category_id`, `brand_id`; localized names in JSONB | Variants override price; JSONB avoids schema changes for new locales |
| **ProductVariant** | Own `sku`, `price`, `mrp`; attributes stored as JSONB | Flexible for different sports (size, weight, color) without EAV pattern |
| **Inventory** | `variant_id`, `current_stock`, `available_stock`, `reserved_stock` (optional) | Simple stock tracking; no ledger or warehouse allocation in V1 |
| **Order** | State machine with explicit transitions; `order_number` as human-readable ID | Prevents invalid state transitions |
| **PaymentTransaction** | Stores raw `gateway_response` as JSONB | Essential for reconciliation |
| **Category** | Self-referencing (`parent_id`) for hierarchy | Supports arbitrary depth |

### 7.4 Key Relationships

- **Product → Variant:** 1:N (a product has multiple variants by size/color/weight)
- **Variant → Inventory:** 1:1 (each variant has one inventory record)
- **Order → OrderItem:** 1:N (an order contains multiple line items)
- **OrderItem → Variant:** N:1 (an item references a specific variant snapshot)
- **OrderStatusHistory:** Append-only log of every status change
- **Return → Refund:** 1:1 (each approved return results in one refund)
- **FranchiseStore → FranchiseInventory:** 1:N (a store has stock for many variants)

---

## 8. API Design Philosophy

- **RESTful** over RPC — resources, not actions
- **Versioned via URL prefix:** `/api/v1/...`
- **Paginated lists** with cursor-based pagination for catalog, order history
- **Standard envelope:**

```json
{
  "status": "success",
  "data": { ... },
  "meta": { "page": 1, "size": 20, "cursor": "..." },
  "errors": []
}
```

- **Error response:**

```json
{
  "status": "error",
  "data": null,
  "errors": [
    { "code": "PRODUCT_OUT_OF_STOCK", "field": "variantId", "message": "Variant is out of stock" }
  ]
}
```

- **Idempotency key** on order creation to prevent duplicate orders
- **Webhook signing** for payment callbacks (future Razorpay integration)

---

## 9. Security Architecture

### 9.1 Authentication Flow

```
Client                   Backend                    Database
  │                         │                         │
  │  POST /api/v1/auth      │                         │
  │  /login {email,pass}    │                         │
  │ ──────────────────────► │──► validate user        │
  │                         │◄── user + roles         │
  │                         │                         │
  │                         │──► generate JWT         │
  │                         │    (access + refresh)   │
  │ ◄────────────────────── │                         │
  │                                                    │
  │  GET /api/v1/products   │                         │
  │  (JWT in header)        │                         │
  │ ──────────────────────► │──► validate JWT         │
  │                         │    (stateless, no DB)   │
  │                         │──► extract roles        │
  │ ◄────────────────────── │                         │
```

### 9.2 JWT Structure

- **Access Token:** 15 minutes, contains `sub` (userId), `roles[]`, `iat`, `exp`
- **Refresh Token:** 7 days, stored hashed in database, rotated on use
- JWT signed with RS256 (asymmetric) — private key on auth server, public key on resource validation

### 9.3 Authorization Matrix (V1)

| Endpoint | Customer | Franchise | Warehouse | Admin | Super Admin |
|----------|----------|-----------|-----------|-------|-------------|
| Browse products | ✓ | ✓ | ✓ | ✓ | ✓ |
| Place order | ✓ | ✓ | ✗ | ✗ | ✓ |
| Manage own inventory | ✗ | Own | All | ✗ | ✓ |
| Manage franchise | ✗ | ✗ | ✗ | ✗ | ✓ |
| View reports | ✗ | Own | ✗ | ✓ | ✓ |
| Manage users | ✗ | ✗ | ✗ | ✗ | ✓ |
| Refund/returns | ✗ | ✗ | ✗ | ✓ | ✓ |

### 9.4 Additional Security Controls

- **Input validation:** JSR-380 Bean Validation on every controller
- **SQL injection:** Prevented by R2DBC parameterized queries (never concatenation)
- **CORS:** Whitelist of allowed origins
- **Secrets:** Google Secret Manager (not env vars or config files)
- **Audit log:** Admin/franchise write operations logged with `who`, `what`, `when`

---

## 10. Deployment Architecture

### 10.1 Version 1 (Cloud Run)

```
                         ┌──────────────┐
                         │  Cloud CDN   │ (static assets)
                         └──────┬───────┘
                                │
                         ┌──────▼───────┐
                         │ Cloud Run    │ ← React SPA (nginx)
                         │ (frontend)   │
                         └──────┬───────┘
                                │
                         ┌──────▼───────┐
                         │ Cloud Run    │ ← Modular Monolith
                         │ (backend)    │   auto-scale 1-10
                         └──────┬───────┘
                                │
                         ┌──────▼───────┐
                         │  Cloud SQL   │
                         │  PostgreSQL  │
                         └──────────────┘
```

### 10.2 CI/CD Pipeline (GitHub Actions)

```
┌──────────┐    ┌───────────┐    ┌────────────┐    ┌───────────┐
│  Push    │──► │  Build +  │──► │  Deploy to │──► │  Run      │
│  to main │    │  Test     │    │  Cloud Run │    │  Smoke    │
└──────────┘    └───────────┘    └────────────┘    │  Tests    │
                                                   └───────────┘
```

**Branch strategy:** Trunk-based development with feature branches → squash-merge to `main`.

### 10.3 Environments

| Environment | Purpose | Deploy Trigger |
|-------------|---------|----------------|
| `dev` | Local development | Manual (`docker-compose up`) |
| `staging` | Integration testing | PR merged to `main` |
| `production` | Live users | Tagged release on `main` |

---

## 11. Folder and Repository Structure

```
dsports-ai/
├── .github/
│   └── workflows/
│       ├── ci.yml              # Build, lint, test
│       ├── deploy-staging.yml
│       └── deploy-production.yml
│
├── backend/
│   ├── pom.xml                 # Parent POM (multi-module)
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/dsports/
│   │   │   │   ├── shared/                  # Cross-cutting
│   │   │   │   │   ├── kernel/              # Base classes, domain primitives
│   │   │   │   │   │   ├── BaseEntity.java
│   │   │   │   │   │   ├── ValueObject.java
│   │   │   │   │   │   └── DomainEvent.java
│   │   │   │   │   ├── api/                 # Module interface contracts
│   │   │   │   │   │   ├── identity/
│   │   │   │   │   │   ├── catalog/
│   │   │   │   │   │   ├── cart/
│   │   │   │   │   │   ├── order/
│   │   │   │   │   │   │   └── OrderService.java
│   │   │   │   │   │   ├── payment/
│   │   │   │   │   │   │   └── PaymentProvider.java
│   │   │   │   │   │   └── ...
│   │   │   │   │   ├── config/              # Global config
│   │   │   │   │   ├── exception/           # Global exception handler
│   │   │   │   │   └── util/                # Shared utilities
│   │   │   │   │
│   │   │   │   ├── identity/                # MODULE: Identity & Access
│   │   │   │   │   ├── domain/
│   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   ├── User.java
│   │   │   │   │   │   │   └── Role.java
│   │   │   │   │   │   ├── service/
│   │   │   │   │   │   │   └── IdentityDomainService.java
│   │   │   │   │   │   └── port/
│   │   │   │   │   │       └── UserRepository.java
│   │   │   │   │   ├── application/
│   │   │   │   │   │   ├── dto/
│   │   │   │   │   │   ├── service/
│   │   │   │   │   │   └── mapper/
│   │   │   │   │   └── infrastructure/
│   │   │   │   │       ├── persistence/
│   │   │   │   │       │   ├── R2dbcUserRepository.java
│   │   │   │   │       │   └── UserTable.java      # @Table, not @Entity
│   │   │   │   │       └── security/
│   │   │   │   │           └── JwtProvider.java
│   │   │   │   │
│   │   │   │   ├── catalog/                 # MODULE: Product Catalog
│   │   │   │   │   ├── domain/
│   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   ├── Product.java
│   │   │   │   │   │   │   ├── Category.java
│   │   │   │   │   │   │   ├── Brand.java
│   │   │   │   │   │   │   └── ProductVariant.java
│   │   │   │   │   │   ├── service/
│   │   │   │   │   │   └── port/
│   │   │   │   │   ├── application/
│   │   │   │   │   └── infrastructure/
│   │   │   │   │       └── persistence/
│   │   │   │   │
│   │   │   │   ├── inventory/               # MODULE: Inventory
│   │   │   │   │   ├── domain/
│   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   └── Inventory.java
│   │   │   │   │   │   └── port/
│   │   │   │   │   ├── application/
│   │   │   │   │   └── infrastructure/
│   │   │   │   │
│   │   │   │   ├── cart/                    # MODULE: Shopping Cart
│   │   │   │   ├── order/                   # MODULE: Orders
│   │   │   │   ├── payment/                 # MODULE: Payment
│   │   │   │   │   ├── domain/
│   │   │   │   │   │   ├── model/
│   │   │   │   │   │   │   └── PaymentTransaction.java
│   │   │   │   │   │   └── port/
│   │   │   │   │   │       └── PaymentProvider.java  # Interface
│   │   │   │   │   ├── application/
│   │   │   │   │   └── infrastructure/
│   │   │   │   │       ├── persistence/
│   │   │   │   │       └── payment/
│   │   │   │   │           └── MockPaymentProvider.java
│   │   │   │   │
│   │   │   │   ├── billing/                 # MODULE: Billing/Invoicing
│   │   │   │   ├── returns/                 # MODULE: Returns
│   │   │   │   ├── franchise/               # MODULE: Franchise
│   │   │   │   ├── notification/            # MODULE: Notifications
│   │   │   │   │
│   │   │   │   └── ai/                      # MODULE: AI (empty — V1 placeholder)
│   │   │   │       ├── domain/
│   │   │   │       ├── application/
│   │   │   │       └── infrastructure/
│   │   │   │
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       ├── application-dev.yml
│   │   │       ├── application-staging.yml
│   │   │       └── application-prod.yml
│   │   └── test/
│   │       └── java/com/dsports/
│   │           ├── identity/
│   │           │   ├── domain/
│   │           │   ├── application/
│   │           │   └── infrastructure/
│   │           └── ... (mirrors main)
│   │
│   └── Dockerfile
│
├── frontend/
│   ├── public/
│   ├── src/
│   │   ├── api/                 # API client layer (Axios)
│   │   ├── components/          # Shared UI components
│   │   ├── features/            # Feature modules
│   │   │   ├── auth/
│   │   │   ├── products/
│   │   │   ├── cart/
│   │   │   ├── checkout/
│   │   │   ├── orders/
│   │   │   ├── admin/
│   │   │   └── franchise/
│   │   ├── hooks/
│   │   ├── layouts/
│   │   ├── lib/                 # Utilities
│   │   ├── routes/
│   │   ├── store/               # Zustand stores
│   │   ├── i18n/                # Translations
│   │   ├── types/               # TypeScript types
│   │   ├── App.tsx
│   │   └── main.tsx
│   ├── package.json
│   ├── tsconfig.json
│   ├── vite.config.ts
│   └── Dockerfile
│
├── database/
│   ├── migrations/              # Flyway SQL migrations
│   │   ├── V1__initial_schema.sql
│   │   ├── V2__seed_categories.sql
│   │   └── V3__seed_admin_user.sql
│   └── seeds/                   # Reference data
│
├── docs/
│   ├── architecture.md          # This document
│   ├── adr/                     # ADRs
│   │   ├── 001-modular-monolith.md
│   │   └── 002-postgresql.md
│   └── api/                     # OpenAPI specs (auto-generated)
│
├── docker-compose.yml           # Local dev: app + postgres
├── .env.example
├── .gitignore
├── AGENTS.md
└── README.md
```

---

## 12. Coding Standards

### 12.1 Java

| Rule | Standard |
|------|----------|
| Package naming | `com.dsports.<module>.<layer>` |
| Class naming | PascalCase, nouns for domain models (`User`), `*Service`, `*Repository` |
| Method naming | Verb phrases: `placeOrder()`, `findById()`, `isAvailable()` |
| Constructor injection | Lombok `@RequiredArgsConstructor` (never field injection) |
| DTOs | Java records (immutable by default) |
| Mappers | MapStruct (compile-time, no reflection) |
| Tests | Given-When-Then comments, BDD-style naming |
| Test coverage | Unit: 90%+ on domain logic; Integration: key paths |
| File organization | One public class per file |
| Logging | Slf4j + Lombok `@Slf4j` — structured with correlation IDs |
| Error handling | Domain exceptions → application exceptions → HTTP error responses |
| Reactive types | Return `Mono<T>` / `Flux<T>` from all persistence and service methods |
| R2DBC entities | Use `@Table`, `@Id`, `@Column` — no relationship annotations |
| Aggregate assembly | Custom repository implementations for multi-table aggregates |

### 12.2 TypeScript / React

| Rule | Standard |
|------|----------|
| File naming | `kebab-case.tsx` for components, `camelCase.ts` for utilities |
| Component type | Function components with hooks |
| Props typing | `interface ComponentNameProps` exported |
| State management | React Query for server state; Zustand for client state |
| Styling | MUI `sx` prop for one-off; styled components for reusable |
| Imports | Absolute imports with `@/` alias mapped to `src/` |
| ESLint | Strict config with `@typescript-eslint` |

### 12.3 SQL / Flyway

- Migration filenames: `V{version}__{description}.sql`
- All DDL is idempotent where possible (`IF NOT EXISTS`)
- Every migration is reviewed for backward compatibility
- Never `DROP COLUMN` without a two-phase migration

---

## 13. Implementation Roadmap

### 13.1 Sprint Overview

```
Sprint 0: Foundation (no auth)     → 2 weeks
Sprint 1: Auth + Catalog           → 2 weeks
Sprint 2: Cart + Inventory         → 2 weeks
Sprint 3: Order + Payment (Mock)   → 2 weeks
Sprint 4: Billing + Notifications  → 2 weeks
Sprint 5: Admin Dashboard          → 2 weeks
Sprint 6: Franchise + Returns      → 2 weeks
Sprint 7: Polish + Launch          → 2 weeks
                                   ─────────
                            Total: 16 weeks
```

### 13.2 Sprint Breakdown

#### Sprint 0 — Foundation (14 days)

**No authentication. No business logic. Just skeleton and infrastructure.**

- [ ] Initialize monorepo structure
- [ ] Configure Maven parent POM with modules
- [ ] Set up Spring Boot skeleton with shared/kernel
- [ ] Configure Flyway + R2DBC + PostgreSQL connection
- [ ] Create `docker-compose.yml` (PostgreSQL + app)
- [ ] Create Dockerfile for backend
- [ ] Implement health endpoint (`GET /api/v1/health`)
- [ ] Configure structured logging (Logback + correlation IDs)
- [ ] Implement global exception handler
- [ ] Configure OpenAPI/Swagger
- [ ] Set up React + Vite + TypeScript + MUI project
- [ ] Configure ESLint + Prettier for frontend
- [ ] Set up GitHub Actions CI (build + test)
- [ ] Write `V1__initial_schema.sql` (empty shell — version tracking)

**Deliverable:** Dev environment running locally, CI green, health endpoint responding, Swagger UI accessible.

#### Sprint 1 — Identity & Catalog (14 days)

- [ ] Identity module: User domain model, R2DBC repository
- [ ] Registration API (`POST /api/v1/auth/register`)
- [ ] Login API (`POST /api/v1/auth/login`) — JWT issuance
- [ ] Refresh token API
- [ ] JWT validation filter
- [ ] Role-based access control (method-level `@PreAuthorize`)
- [ ] Catalog module: Category, Brand, Product, Variant domain models
- [ ] Catalog CRUD APIs (admin-only)
- [ ] Product listing API (public, paginated, filtered)
- [ ] Product detail API
- [ ] Admin UI: category/brand management
- [ ] Frontend: Login/Register pages + auth state
- [ ] Frontend: Protected routes

**Deliverable:** Users can register, log in, and browse products by category.

#### Sprint 2 — Cart & Inventory (14 days)

- [ ] Cart module: Cart, CartItem domain models
- [ ] Add to cart API
- [ ] Remove/update cart items API
- [ ] Get cart API
- [ ] Guest cart → user cart merge on login
- [ ] Inventory module: simple stock tracking
- [ ] Stock validation during add-to-cart
- [ ] Frontend: Product listing page with filters
- [ ] Frontend: Product detail page
- [ ] Frontend: Cart page (add, remove, update quantity)
- [ ] Frontend: Stock indicator on product cards

**Deliverable:** Users can browse, add to cart, see stock levels.

#### Sprint 3 — Order & Payment (14 days)

- [ ] Order module: Order domain model, state machine
- [ ] Address management APIs
- [ ] Order creation from cart
- [ ] Order status history (append-only log)
- [ ] Payment module: `PaymentProvider` interface
- [ ] `MockPaymentProvider` implementation (always succeeds)
- [ ] Payment flow: create → process → confirm
- [ ] Order status transitions tied to payment status
- [ ] Frontend: Checkout flow (address → review → pay)
- [ ] Frontend: Order confirmation page
- [ ] Frontend: Order list page

**Deliverable:** Complete order flow with mock payment.

#### Sprint 4 — Billing & Notifications (14 days)

- [ ] Billing module: Invoice generation
- [ ] Tax computation (GST — CGST + SGST/IGST)
- [ ] Invoice PDF generation (in-memory, no external storage)
- [ ] Notification module: Email service interface
- [ ] Transactional emails (order confirmation)
- [ ] Invoice email with attachment
- [ ] Frontend: Order detail page
- [ ] Frontend: Invoice download
- [ ] Frontend: Order history page

**Deliverable:** Orders generate invoices, emails sent.

#### Sprint 5 — Admin Dashboard (14 days)

- [ ] Admin APIs: list/manage users
- [ ] Admin APIs: list/manage orders, update status
- [ ] Admin APIs: manage products, categories, brands
- [ ] Report APIs (basic): sales by day, top products, revenue
- [ ] PostgreSQL full-text search on products
- [ ] Frontend: Admin dashboard with stats cards
- [ ] Frontend: Admin order management (status updates)
- [ ] Frontend: Admin product management (CRUD forms)
- [ ] Frontend: Admin user management

**Deliverable:** Admin can manage the platform end-to-end.

#### Sprint 6 — Franchise & Returns (14 days)

- [ ] Franchise module: FranchiseStore domain model
- [ ] Franchise inventory (store-specific stock)
- [ ] Returns module: ReturnRequest, ReturnItem
- [ ] Return request API (customer)
- [ ] Return approval/rejection API (admin)
- [ ] Refund processing through `PaymentProvider`
- [ ] Frontend: Returns UI (customer — request return)
- [ ] Frontend: Franchise dashboard (view inventory)
- [ ] Frontend: Return approval UI (admin)

**Deliverable:** Franchise partners operational, returns workflow live.

#### Sprint 7 — Hardening & Launch (14 days)

- [ ] Load testing (k6) against target metrics
- [ ] Basic rate limiting (per-IP, per-endpoint)
- [ ] Security audit (OWASP Top 10)
- [ ] Performance optimization (indexing, N+1 query fixes)
- [ ] Production deployment checklist
- [ ] Staging environment setup
- [ ] Go-live runbook
- [ ] Documentation handoff

**Deliverable:** Production-ready platform → LAUNCH.

---

## 14. Development Roadmap — Time-Boxed

```
WEEK 1  ─── Sprint 0: Foundation
          • Maven multi-module skeleton
          • Spring Boot + R2DBC + Flyway
          • docker-compose (PostgreSQL + app)
          • Health endpoint, exception handler, OpenAPI
          • React + Vite + MUI scaffold
          • GitHub Actions CI

WEEK 2  ─── Sprint 1 (Part 1): Auth
          • Identity module: User, Role, JWT
          • Registration, login, refresh token APIs
          • JWT validation filter
          • RBAC annotations

WEEK 3  ─── Sprint 1 (Part 2): Catalog
          • Catalog module: Product, Category, Brand, Variant
          • CRUD APIs (admin)
          • Public product listing + detail APIs
          • Frontend: Login, Register, product pages

WEEK 4  ─── Sprint 2: Cart + Inventory
          • Cart module: Cart, CartItem
          • Guest → user cart merge
          • Inventory: current_stock, available_stock
          • Stock validation on add-to-cart
          • Frontend: Cart page, stock indicators

MONTH 2 ─── Sprint 3: Order + Payment
           • Order module with state machine
           • PaymentProvider interface + MockPaymentProvider
           • Address management
           • Checkout flow
           • Frontend: checkout, order confirmation, order list

MONTH 3 ─── Sprint 4: Billing + Notifications
           • Invoice generation + GST computation
           • Email service (transactional emails)
           • Invoice PDF
           • Frontend: order detail, invoice download

MONTH 4 ─── Sprint 5: Admin
           • Admin APIs (users, orders, products)
           • PostgreSQL full-text search
           • Report APIs (basic sales/revenue)
           • Frontend: admin dashboard, order management, product management

MONTH 5 ─── Sprint 6: Franchise + Returns
           • Franchise module
           • Returns module + refund flow
           • Frontend: franchise dashboard, returns UI

MONTH 6 ─── Sprint 7: Polish + Launch
           • Load testing, performance tuning
           • Basic rate limiting, security audit
           • Staging → Production launch
           • Monitoring + runbook
```

---

## 15. What NOT to Build in Version 1

This section exists to keep scope disciplined. Each item is consciously deferred.

| Feature | Reason Deferred | Planned Version |
|---------|----------------|-----------------|
| **Real payment gateway (Razorpay)** | Mock payment is sufficient for V1; Razorpay adds PCI scope, webhooks, error handling complexity | V1.1 / Post-launch |
| **Image upload & cloud storage** | Admin can set image URLs manually; GCS integration adds ops overhead | V1.1 |
| **AI features** | No AI in V1 — module is a placeholder | V2+ |
| **Kafka / async events** | Synchronous calls are fine at 100 concurrent users | Post-launch |
| **Redis / caching** | PostgreSQL can handle V1 load; caching is optimization, not necessity | Post-launch |
| **Elasticsearch** | PostgreSQL full-text search is adequate for 500 products | V2 |
| **CQRS / read replicas** | Single database instance handles V1 read/write mix | Post-launch |
| **Advanced rate limiting** | Simple per-IP limit in Sprint 7 is sufficient; no need for token buckets yet | Post-launch |
| **Sentry / advanced error monitoring** | Cloud Logging covers V1 needs; Sentry adds cost and setup | Post-launch |
| **Cloud Armor / WAF** | Cloud Run's built-in protections are sufficient at low volume | Post-launch |
| **Service extraction** | Monolith works; extracting services adds network latency and ops burden | Post-launch |
| **Image transformations / CDN** | Direct image URLs with simple `<img>` tags work for V1 | V1.1 |
| **Multi-language support** | English only for V1; i18n infrastructure is ready, content is not | V2 |
| **Multi-currency** | INR only for V1; exchange-rate table schema is ready, not populated | V2 |
| **Advanced inventory (ledger, warehouse allocation)** | Simple stock counters work for V1; ledger adds complexity without immediate benefit | Post-launch |
| **Franchise marketplace (3rd-party sellers)** | Platform is centralized in V1; marketplace model requires multi-tenancy redesign | V3+ |

---

## 16. Risk Register

| # | Risk | Likelihood | Impact | Mitigation |
|---|------|-----------|--------|------------|
| R1 | Monolith becomes difficult to maintain as codebase grows | Medium | Medium | Strict module boundaries enforced in CI (ArchUnit); no shortcuts |
| R2 | Mock payment hides real payment integration complexity | Medium | High | PaymentProvider interface is identical to real gateway contract; test with contract tests |
| R3 | PostgreSQL performance degrades under load | Medium | Medium | Proper indexing from Sprint 1; monitor query performance |
| R4 | JWT secret compromise | Low | Critical | RS256 keys rotated regularly; stored in Secret Manager; short-lived tokens |
| R5 | Franchise data isolation bugs | Medium | Medium | `store_id` on every franchise query; integration tests |
| R6 | Single developer bottleneck | High | High | Comprehensive tests + ADRs so any developer can pick up |
| R7 | R2DBC learning curve vs JPA | Medium | Low | R2DBC is simpler (no caching, no lazy loading surprises); team adjusts quickly |

---

## 17. Scalability Strategy

### 17.1 Scaling Targets

| Metric | V1 Target | V2 Target (12mo) |
|--------|-----------|------------------|
| Concurrent users | 100 | 10,000 |
| Requests/sec | 50 | 5,000 |
| Products in catalog | 500 | 50,000 |
| Orders/day | 50 | 5,000 |
| Database size | 1 GB | 50 GB |

### 17.2 V1 Scaling Approach

| Layer | Approach |
|-------|----------|
| Compute | Cloud Run auto-scale (1-10 instances) |
| Database | 1 Cloud SQL instance, proper indexing |
| API | Direct module calls within monolith |

### 17.3 Future Scaling (Post-Launch)

| Layer | Future Approach |
|-------|----------------|
| Compute | GKE with HPA (10-50 pods) |
| Database | Read replicas + PgBouncer connection pooling |
| Cache | Redis for catalog, sessions, rate-limiting |
| Search | Elasticsearch cluster |
| Async | Kafka for event-driven processing |
| AI | Dedicated AI service on GPU nodes |
| API | BFF layer per client type |
| CDN | Cloud CDN for product images + static assets |

### 17.4 Bottlenecks to Monitor

- **Database connection pool** — first resource to exhaust under load
- **JWT validation** — RSA public key cached; avoid repeated DB calls

---

## 18. Architectural Decision Records

### ADR-001: Modular Monolith over Microservices

**Context:** Balance speed of delivery with future scalability.

**Decision:** Build a modular monolith with strict bounded contexts and published interfaces. Each module can be extracted to a service.

**Consequences:**
- (+) Faster development, debugging, deployment
- (+) Single database transaction across modules
- (-) Cannot scale modules independently (acceptable for V1)
- (-) Deployment couples all modules (acceptable for single developer)

**Alternatives considered:** Full microservices (rejected: operational overhead > benefit at 100 users).

---

### ADR-002: PostgreSQL as Primary Database

**Context:** Relational store with flexible schema for product attributes.

**Decision:** PostgreSQL with JSONB for flexible attribute storage and full-text search.

**Consequences:**
- (+) JSONB enables schema-free variant attributes without EAV pattern
- (+) Full-text search delays need for Elasticsearch
- (+) Native R2DBC support
- (-) JSONB queries less performant than normalized columns at scale

**Alternatives considered:** MongoDB (rejected: weak consistency for orders), MySQL (rejected: weaker JSON support).

---

### ADR-003: Spring WebFlux + R2DBC over Spring MVC + JPA

**Context:** Need non-blocking I/O for future AI streaming; want a single reactive stack throughout.

**Decision:** Use WebFlux with Spring Data R2DBC for the entire backend. No JPA.

**Consequences:**
- (+) Fully reactive from controller to database — no thread pool blocking
- (+) Non-blocking I/O suits future AI streaming responses
- (+) R2DBC is simpler than JPA — no caching, no lazy loading, no N+1 surprises
- (-) Steeper learning curve (reactive programming)
- (-) No automatic relationship mapping — aggregates assembled explicitly
- (-) Smaller ecosystem than JPA (fewer third-party integrations)

**Alternatives considered:**
- Spring MVC + JPA (rejected: blocking model doesn't suit AI streaming future; mixing MVC + WebFlux adds complexity)
- Spring WebFlux + JPA (rejected: JPA is blocking — would need `Schedulers.boundedElastic()` workaround for every DB call, defeating the purpose of reactive)

---

### ADR-004: Payment Provider Abstraction (Mock First)

**Context:** Payment integration is complex (PCI compliance, webhooks, error handling). Business wants to defer real gateway integration.

**Decision:** Define a `PaymentProvider` interface in the domain layer. Implement `MockPaymentProvider` for V1. Add `RazorpayPaymentProvider` as a future implementation.

```java
public interface PaymentProvider {
    Mono<PaymentResult> processPayment(PaymentRequest request);
    Mono<PaymentStatus> checkStatus(String transactionId);
    Mono<RefundResult> processRefund(RefundRequest request);
}
```

**Consequences:**
- (+) Business logic is fully developed and tested against mock
- (+) Razorpay can be plugged in without changing order/billing/return modules
- (-) Integration issues may surface when switching to real gateway (mitigated by contract tests)

**Alternatives considered:** Razorpay from day one (rejected: adds complexity not needed for V1).

---

### ADR-005: Guest Cart → User Cart Merge

**Context:** Users browse before logging in; cart must persist across sessions.

**Decision:** Allow cart operations without authentication. On login, merge guest cart into user cart (de-duplicate by variant, keep highest quantity).

**Consequences:**
- (+) Better conversion rate (users can add before sign-up)
- (-) Merge logic must handle edge cases

---

### ADR-006: Monorepo with Maven Multi-Module

**Context:** Single developer, tight coupling between backend modules.

**Decision:** Single repository with Maven multi-module build. Frontend in same repo.

**Consequences:**
- (+) Single `git pull` to get everything running
- (+) Atomic commits across frontend + backend
- (-) Repository grows large over time (acceptable)

---

### ADR-007: Skip Redis/Elasticsearch/Kafka in V1

**Context:** These are standard e-commerce infrastructure components but add significant operational complexity.

**Decision:** Do not introduce any of these in V1. Use PostgreSQL for search, synchronous calls for everything, in-memory for rate limits.

**Consequences:**
- (+) Simpler deployment, fewer moving parts
- (+) Faster local development (no external services besides PostgreSQL)
- (-) Search will be slower with PostgreSQL full-text (acceptable at 500 products)
- (-) Cannot replay events; no async processing (acceptable at low volume)

**Re-evaluation triggers:**
- When catalog exceeds 5,000 products → evaluate Elasticsearch
- When async processing is needed → evaluate Kafka
- When database queries become a bottleneck → evaluate Redis caching

---

## 19. Future AI Roadmap

| Version | Feature | Approach | Effort |
|---------|---------|----------|--------|
| V2 | AI Product Search | PostgreSQL full-text (V1) → Elasticsearch + embeddings | 4 weeks |
| V3 | AI Shopping Assistant | Spring AI + OpenAI/Gemini for conversational product discovery | 6 weeks |
| V4 | Product Recommendations | Collaborative filtering (pre-trained) → hybrid model | 4 weeks |
| V5 | Customer Support Agent | RAG-based chatbot with order/return context | 6 weeks |
| V6 | Inventory Prediction | Time-series forecasting | 4 weeks |
| V7 | Demand Forecasting | ML pipeline with historical order data | 6 weeks |
| V8 | Fraud Detection | Anomaly detection on payment/order patterns | 4 weeks |

The empty `ai/` module exists now so these features slot in without refactoring the package structure.

---

## Appendix A: Key Assumptions

| Assumption | Impact if Wrong |
|------------|-----------------|
| 100 → 10,000 concurrent users within 12 months | If slower: monolith duration extends; if faster: extract services earlier |
| All monetary values in INR (V1) | If multi-currency needed earlier: activate exchange-rate module |
| GCP as cloud provider | If switch: Docker/Cloud Run → ECS, Cloud SQL → RDS |
| Image URLs provided manually in V1 | If image upload becomes urgent: add GCS integration in V1.1 |

---

## Appendix B: Tools & Services Summary (V1)

| Tool | Purpose | Cost |
|------|---------|------|
| IntelliJ IDEA | Primary IDE | Paid / Free Community |
| DBeaver / pgAdmin | Database GUI | Free |
| Postman | API testing | Free tier |
| k6 | Load testing | Open source |
| Excalidraw / Draw.io | Architecture diagrams | Free |

---

*This document is a living artifact. Update it as architectural decisions evolve.*
