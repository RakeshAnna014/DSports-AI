# PR #11 – Catalog Foundation

## Overview

This PR introduces the master data foundation for the Commerce bounded context.

Three aggregate roots are introduced:

- **Sport**
- **Category**
- **Brand**

No Products, Inventory, Pricing, Cart, or Orders are included.

---

## Why These Are Aggregate Roots

### Sport

A Sport is the top-level classification in the catalog. It has its own lifecycle (create, update, archive) and its own identity (`SportId`). Products will later reference a Sport, making Sport the root of its own consistency boundary.

**Why an aggregate root (not an enum or value object):**

- Sports have mutable state (name, slug, description, status).
- Sports have lifecycle events (`SportCreatedEvent`, `SportUpdatedEvent`, `SportArchivedEvent`).
- Sports have unique business rules (name and slug uniqueness).
- Future versions will attach sport-specific attributes, rules, and configurations.
- External systems (inventory, pricing) will reference sports by ID.

An enum would be too rigid — sports are master data managed by administrators, not compile-time constants.

### Category

A Category classifies products (e.g., Bat, Ball, Shoes). It has independent lifecycle and identity (`CategoryId`).

**Why an aggregate root:**

- Categories are independently managed by administrators.
- Categories have lifecycle events.
- Categories have unique name and slug constraints.
- Future versions may introduce category hierarchies (parent-child), attributes, and SEO metadata.
- Products will reference categories, but categories do not depend on products.

### Brand

A Brand represents the manufacturer or label of a product. It has its own identity (`BrandId`).

**Why an aggregate root:**

- Brands exist independently of products (a brand can exist in the system without any products).
- Brands are managed by administrators with full CRUD lifecycle.
- Brands have unique name and slug constraints.
- Future versions may add brand-specific content (logos, descriptions, websites).

---

## Why Products Are Intentionally Excluded

Products were deliberately excluded from this PR for several reasons:

1. **Separation of concerns**: Master data (sports, categories, brands) is foundational and should be stable before introducing products, which have significantly more complexity.

2. **Complexity management**: Products will require:
   - Variants (size, color, material)
   - Pricing (base price, discounts, tax rules)
   - Inventory tracking (stock levels, warehouse locations)
   - Media (images, videos, 3D models)
   - Specifications (attributes, dimensions, weight)
   - SEO (meta titles, descriptions, canonical URLs)

3. **Incremental delivery**: The catalog bounded context is being built in layers. This PR establishes the foundation. Products will be introduced in a subsequent PR with its own migration, domain model, tests, and APIs.

4. **Database stability**: The sports, categories, and brands tables are reference data for products. Seeding this data first ensures products can reference valid entities from day one.

---

## Future Product Integration

When products are introduced, they will:

- Reference `sports.id` via foreign key (`product.sport_id`)
- Reference `categories.id` via join table (`product_categories`)
- Reference `brands.id` via foreign key (`product.brand_id`)
- Use the same `Slug` value object for URL-friendly identifiers
- Use the same `Status` enum for soft-delete lifecycle
- Follow the same DDD patterns (aggregate root, value objects, domain events)
- Use the same Flyway migration strategy (V11+)
- Use the same repository adapter pattern

Expected product aggregate structure:

```
Product (Aggregate Root)
├── ProductId (Value Object)
├── ProductName (Value Object)
├── Slug (Value Object)
├── Description (Value Object)
├── Status (Enum)
├── SportId (reference to Sport aggregate)
├── BrandId (reference to Brand aggregate)
├── Set<CategoryId> (references to Category aggregates)
├── List<ProductVariant> (child entities)
├── List<ProductMedia> (child entities)
├── ProductAttributes (Value Object)
└── Domain Events (ProductCreated, ProductUpdated, ProductArchived)
```

---

## Future Inventory Integration

Inventory will be a separate bounded context, not part of catalog:

- Catalog owns product definitions, descriptions, and categorization
- Inventory owns stock levels, warehouse locations, and availability
- Inventory references products by `ProductId` (not by sport/category/brand)

This separation follows DDD principles — inventory has different business rules, different transaction boundaries, and different consistency requirements than catalog.

---

## Architecture Decisions

### Bounded Context: Catalog

The catalog bounded context owns master data for the Commerce domain. It is fully reactive (Spring WebFlux + R2DBC) with no blocking calls.

### Database

- PostgreSQL
- Flyway migrations (V7–V10)
- Soft delete via `Status.ARCHIVED` (no `deleted_at` column — status-based)
- Optimistic locking via `version` column
- Unique constraints on `name` and `slug` per table

### Domain Model

- Rich domain model with behavioral methods
- Factory methods: `create()` for new aggregates, `reconstitute()` for persistence rebuild
- Domain events recorded on the aggregate, published by the repository adapter
- Value objects are immutable Java records implementing `ValueObject` marker

### Security

- Public GET `/api/catalog/*` — no authentication required
- Admin POST/PUT/DELETE `/api/admin/catalog/*` — requires `ROLE_ADMIN`

### Seed Data

Provided via Flyway migrations:

| Entity | Count | Examples |
|--------|-------|---------|
| Sports | 6 | Cricket, Football, Badminton, Basketball, Tennis, Volleyball |
| Categories | 8 | Bat, Ball, Shoes, Gloves, Helmet, Jersey, Accessories, Kit Bag |
| Brands | 10 | MRF, SS, SG, DSC, Gray Nicolls, Kookaburra, Adidas, Nike, Puma, Spartan |

---

## Trade-offs

1. **No category hierarchy**: Categories are flat. Future PRs can add parent-child via self-referential foreign key or a closure table.

2. **Single Status enum**: ACTIVE and ARCHIVED only. No DRAFT or PENDING states. These can be added later without breaking changes.

3. **No sport-category-brand relationships**: These three are independent. Product integration will define the relationships.

4. **Lombok not used**: The codebase uses explicit getters/setters. Any use of Lombok is optional and not enforced.

5. **Repetitive code**: Sport, Category, and Brand follow the same patterns. This is intentional — each aggregate is independent and will diverge as features are added.

---

## Architecture Review

### Blocker

None.

### High

None.

### Medium

1. Seed data uses `gen_random_uuid()` which requires the `pgcrypto` extension. Ensure the PostgreSQL instance has this extension enabled.
2. Admin controllers do not have `@PreAuthorize` annotations — security is handled at the filter chain level in `SecurityConfig`.

### Low

1. Description column allows arbitrary length. Future PRs may add max-length validation.
2. No pagination for GET endpoints. Acceptable for current seed data volume.

---

## Testing

- **Domain tests**: Sport, Category, Brand aggregate behavior and validation
- **Value object tests**: Slug validation
- **Use case tests**: Create, Update, Archive, Query with mocked repositories
- **Controller tests**: WebFlux slice tests for public and admin endpoints
- **Migration tests**: Testcontainers-based Flyway migration validation including schema, seed data, and constraint enforcement

---

## Verification

```bash
# Compile
mvn compile -pl catalog -am

# Run tests
mvn test -pl catalog -am
```

PR #11 is production-ready and recommended for merge.
