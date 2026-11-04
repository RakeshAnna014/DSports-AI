# ADR-008: Reviews & Ratings Module

## Status

Accepted

## Context

The DSports-AI platform requires a product reviews and ratings system to allow customers to share feedback on purchased products. Key requirements:

- Customers can review products they have purchased and received
- Ratings on a 1-5 scale with optional comments
- Reviews require moderation before public visibility
- Aggregate rating summaries per product
- Helpfulness voting on reviews
- One review per product per customer

## Decision

Implement a dedicated Reviews module within the modular monolith following Clean Architecture and DDD.

### Domain Model

- **Review** aggregate root — encapsulates rating, title, comment, status lifecycle, and helpfulness count
- **HelpfulVote** entity — one vote per user per review
- **Rating** value object — enforces 1-5 range at construction
- **ReviewComment** value object — enforces max length and non-blank
- **ReviewStatus** enum — PENDING → APPROVED/REJECTED, APPROVED → HIDDEN, REJECTED → PENDING, HIDDEN → APPROVED

### Moderation Flow

1. Customer submits review → status = `PENDING`
2. Admin moderates → status = `APPROVED` or `REJECTED`
3. Only `APPROVED` reviews appear in public product review queries

### Verified Purchase Check

- Before creating a review, the use case calls `OrderQueryService.userPurchasedProduct()` and `OrderQueryService.isProductDeliveredInOrder()` to verify eligibility
- This is done via a port interface to keep the review module decoupled from the order module

### Events

Domain events are published after state changes:
- `ReviewCreatedEvent`
- `ReviewUpdatedEvent`
- `ReviewApprovedEvent`

These are published via the `EventPublisher` port interface.

### API Design

RESTful endpoints under `/api/v1/reviews/`:
- Public endpoints for reading reviews and summaries
- Authenticated endpoints for creating/updating/voting
- Admin-only endpoint for moderation

## Alternatives Considered

### Embedded reviews in product module
- Pros: Simpler data access
- Cons: Violates single responsibility; product module already large; moderation has different lifecycle

### Third-party reviews service
- Pros: Faster initial implementation
- Cons: Data sovereignty; integration complexity; cost; less control over moderation flow

## Trade-offs

- **Verified purchase check via port**: Adds indirection but keeps modules decoupled
- **Separate helpful_votes table**: Avoids JSONB complexity in the reviews table and enables unique constraints per user
- **Optimistic locking**: Using `@Version` on the Review aggregate to handle concurrent moderation/voting
- **TransactionalOperator**: Ensures consistency when saving reviews with optimistic locking retry

## Consequences

Positive:
- Clean separation of concerns for the reviews domain
- Event-driven integration enables future features (e.g., notification on review approval)
- Verified purchase flow ensures authenticity

Negative:
- Additional module to maintain
- Verified purchase check requires the order module to implement `OrderQueryService`
- Migration required for existing product catalog data

## Compliance

- Clean Architecture ✓
- DDD tactical patterns (aggregates, value objects, domain events) ✓
- Modular monolith ✓
- Reactive (Spring WebFlux / R2DBC) ✓
