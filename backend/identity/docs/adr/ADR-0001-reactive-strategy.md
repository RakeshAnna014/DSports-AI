# ADR-0001: Reactive Architecture Strategy

**Status:** Draft — Decision Required  
**Date:** 2026-07-12  
**Context:** Identity Module Infrastructure Layer Review (PR #5)  
**Scope:** Identity module, but implications affect the entire backend

---

## Current State

The backend uses a **hybrid architecture**:

| Layer | Paradigm | Technology |
|-------|----------|-----------|
| Domain | Synchronous | Pure Java |
| Application | Synchronous | Use cases return `Optional<User>`, `void` |
| Infrastructure | Reactive | Spring Data R2DBC `ReactiveCrudRepository` |
| Persistence | Reactive | R2DBC (non-blocking database driver) |

The bridge is `UserRepositoryAdapter`, which calls `.block()` / `.blockOptional()` on reactive `Mono` / `Flux` types to satisfy the synchronous `UserRepository` port interface.

---

## Problem

The `.block()` bridge creates a tension:

1. **Thread blocking** — blocking a reactive pipeline defeats the purpose of non-blocking IO. The WebFlux event loop thread is blocked during database operations.
2. **Migration uncertainty** — without a clear architectural direction, each module may choose a different approach, creating an inconsistent codebase.
3. **Testing complexity** — reactive tests require `StepVerifier` and reactor setup, while synchronous tests use simple assertions.

Two clean options exist — commit fully to one paradigm.

---

## Option A: Spring MVC + JPA (Fully Synchronous)

### Architecture

```
Controller (MVC) → Use Case (sync) → Repository Adapter (sync) → JPA Repository → JDBC → Database
```

### Technology Stack

- `spring-boot-starter-web` (Tomcat, blocking servlet)
- `spring-boot-starter-data-jpa` (Hibernate, JDBC)
- `spring-boot-starter-data-r2dbc` removed
- Flyway for schema management

### Pros

| Factor | Assessment |
|--------|-----------|
| Simplicity | High — synchronous programming model, no reactive operators |
| Learning curve | Low — most Java developers know JPA/Hibernate |
| Debugging | Easy — stack traces are linear, no reactive assembly |
| Transaction management | Mature — `@Transactional` with JPA works naturally |
| Migration effort | Medium — rewrite infrastructure layer for identity module; all other modules would need the same |
| Existing code reuse | High — domain and application layers are already synchronous; only infrastructure changes |

### Cons

| Factor | Assessment |
|--------|-----------|
| Thread scalability | Lower — each request consumes a thread (Tomcat default: 200 threads) |
| Blocking IO | Database calls block the thread; context switching overhead at high concurrency |
| Long-term scaling | Requires vertical scaling (more CPU/memory) rather than efficient thread utilization |
| Reactive libraries | Cannot use reactive libraries (e.g., reactive Kafka clients) without wrapping |

### When to Choose

- Team has strong JPA/Spring MVC expertise
- Anticipated concurrency is moderate (< 1,000 concurrent users per instance)
- Quick delivery is the priority
- No streaming or backpressure requirements

---

## Option B: Spring WebFlux + R2DBC (End-to-End Reactive)

### Architecture

```
Controller (WebFlux) → Use Case (reactive) → Repository Adapter (reactive) → R2DBC Repository → Database
```

### Technology Stack

- `spring-boot-starter-webflux` (Netty, non-blocking)
- `spring-boot-starter-data-r2dbc`
- R2DBC driver for PostgreSQL
- Flyway or R2DBC migrator for schema management
- Reactor (`Mono` / `Flux`) throughout the application layer

### Pros

| Factor | Assessment |
|--------|-----------|
| Thread scalability | High — Netty event loop handles thousands of connections with few threads |
| Non-blocking IO | Zero thread blocking during database queries |
| Backpressure | Built-in via Reactive Streams specification |
| Future-proof | Aligns with cloud-native, high-concurrency patterns |
| Existing dependency | WebFlux and R2DBC are already in the POM |

### Cons

| Factor | Assessment |
|--------|-----------|
| Learning curve | High — reactive programming is conceptually different (assembly vs execution time) |
| Debugging | Harder — stack traces span reactive assembly chains |
| Application layer rewrite | **Significant** — all use cases must return `Mono<T>` / `Flux<T>` instead of `Optional<T>`, `User`, or `void` |
| Error handling | Complex — reactive error propagation differs from try-catch |
| Transaction management | More complex — R2DBC transaction management is less mature than JPA |
| Integration testing | Requires `StepVerifier` and reactive test infrastructure |
| Migration effort | Very high — touches application, infrastructure, and interface layers in all modules |

### When to Choose

- Anticipated concurrency is high (> 1,000 concurrent connections per instance)
- Team has (or can invest in) reactive programming expertise
- Application has streaming, long-lived connections, or event-driven flows
- Platform runs on container-orchestrated (Kubernetes) auto-scaling

---

## Migration Impact Comparison

| Layer | Option A (Spring MVC + JPA) | Option B (WebFlux + R2DBC) |
|-------|-----------------------------|---------------------------|
| **Domain** | No change | No change |
| **Application (Ports)** | No change | Change to reactive types |
| **Application (Use Cases)** | No change | Rewrite to return `Mono`/`Flux` |
| **Application (Commands/Results)** | No change | No change (records remain) |
| **Infrastructure (Adapters)** | Replace R2DBC with JPA | Minimal change (remove `.block()`) |
| **Infrastructure (Entities)** | Replace R2DBC with JPA annotations | No change |
| **Interface (Controllers)** | Replace WebFlux with MVC | No change (WebFlux stays) |

---

## Complexity Assessment

| Dimension | Option A | Option B |
|-----------|----------|----------|
| Application layer complexity | Low | High |
| Infrastructure complexity | Medium (rewrite) | Low (fix) |
| Testing complexity | Low | Medium |
| Operational complexity | Low | Low |
| Overall effort (identity module) | ~2-3 days | ~3-4 days |
| Overall effort (all modules) | ~3-4 weeks | ~4-6 weeks |

---

## Recommendation

**Option A — Spring MVC + JPA** is recommended for the following reasons:

1. **Domain and Application layers are already synchronous** — forcing them to be reactive would require rewriting every use case, command, result, and port interface across all 14 modules. This is a massive effort with no immediate business benefit.

2. **Team alignment** — the team can focus on delivering business features rather than learning reactive programming and debugging reactor chains.

3. **Sufficient scalability** — for a sports e-commerce platform at anticipated scale (1,000-5,000 concurrent users), Tomcat's thread pool with connection pooling provides adequate throughput. If needed, horizontal scaling (more instances) is simpler and cheaper than reactive optimization.

4. **Migration path** — if reactive becomes necessary later (e.g., real-time event streaming, AI inference pipelines), the Domain layer is already framework-independent. Only the Application and Infrastructure layers need to change.

### Immediate Actions if Option A is Accepted

1. Replace `spring-boot-starter-data-r2dbc` with `spring-boot-starter-data-jpa` in all module POMs
2. Replace `spring-boot-starter-webflux` with `spring-boot-starter-web` in the bootstrap module
3. Create `UserJpaRepository` (Spring Data JPA) and `UserJpaRepositoryAdapter`
4. Convert `UserEntity` from R2DBC annotations to JPA annotations
5. Remove `.block()` calls — synchronous end-to-end

### Actions if Option B is Accepted

1. Convert `UserRepository` port interface from `Optional<User>` to `Mono<User>`
2. Convert use cases from synchronous to reactive
3. Update `RegisterUserResult` and `AuthenticationResult` to work with reactive return types
4. Remove `.block()` from adapters
5. Keep current infrastructure (R2DBC, WebFlux) and extend to all layers

---

## References

- PR #4: Application Layer (synchronous use cases, `Optional<T>` returns)
- PR #5: Infrastructure Layer (hybrid: reactive repository + `.block()` adapter)
- Spring Boot 3.4.4 documentation — WebFlux vs MVC
