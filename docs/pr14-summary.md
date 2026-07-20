# PR #14 — Pricing Management

## Design Decisions

### Why Pricing is a Separate Bounded Context

Pricing has fundamentally different concerns from Catalog and Inventory:

| Dimension | Catalog (Products) | Inventory (Stock) | Pricing (Prices) |
|-----------|-------------------|-------------------|------------------|
| Lifecycle | Created once | Fluctuates daily | Changes by promotions, seasons |
| Consistency | Strong | Eventual | Must enforce one active price |
| Domain logic | Product attributes | Stock movements | MRP vs selling price, currency, effective dates |
| Change pattern | Rare edits | Frequent mutations | Scheduled activations, price history |

Product stores product information only. Pricing owns all price-related logic, including MRP validation, currency support, effective date ranges, and price history.

### Why Product Should Not Contain Price

1. **Different change frequency**: Product details (name, description, images) change rarely. Prices change frequently due to promotions, seasonal discounts, and currency fluctuations.
2. **Different consistency requirements**: Product updates don't need to trigger price recalculations and vice versa.
3. **Price history**: A product's current price is a snapshot — past prices must be preserved for order history, invoicing, and analytics.
4. **Scheduled pricing**: Prices can be scheduled for future activation (e.g., a sale starting next week). This is a pricing concern, not a product concern.
5. **Multi-currency**: A product can have different prices in different currencies. Pricing manages this; Product shouldn't.

### Why Price History is Required

1. **Order reconciliation**: Orders reference the price at the time of purchase. If a price changes later, historical orders must retain their original price.
2. **Audit trail**: Pricing changes must be traceable for compliance and dispute resolution.
3. **Analytics**: Historical price data enables demand forecasting, price elasticity analysis, and promotion effectiveness measurement.
4. **No physical delete**: Archived prices remain in the database for historical reference but cannot be modified.

### Status Machine

```
DRAFT ──schedule()──▶ SCHEDULED ──activate()──▶ ACTIVE ──archive()──▶ ARCHIVED
  │                      │                                               ▲
  └──────activate()──────┘                                               │
  └─────────────────────────archive()────────────────────────────────────┘
```

- **DRAFT**: Initial state for price setup. Can be updated, scheduled, activated, or archived.
- **SCHEDULED**: Set to activate on a future date. Can be activated early or archived.
- **ACTIVE**: The current effective price. Only one ACTIVE price per product+currency. Can be archived.
- **ARCHIVED**: Terminal state. Cannot be modified. Preserved for history.

### Unique Constraint Strategy

The `uq_price_product_currency_active` partial unique index (`WHERE status = 'ACTIVE'`) enforces at the database level that only one price can be ACTIVE per product+currency. This is a PostgreSQL-specific feature that provides an additional safety net beyond application-level validation.

### Future Integration

#### Promotion Integration
- A promotion engine will apply discount rules on top of the selling price
- Promotions may create SCHEDULED prices that auto-activate on a campaign start date
- `PriceActivatedEvent` can trigger promotion-aware recomputation

#### Coupon Integration
- Coupon codes will calculate discounts relative to the ACTIVE selling price at order time
- The Price aggregate provides the authoritative selling price; coupon logic is separate

#### Tax Integration
- Tax calculation depends on the selling price + product category + customer location
- A separate Tax context will read the ACTIVE price via PriceId reference or domain events

## Architecture

### Domain Layer

- **Aggregate**: `Price` — root with status machine and price behaviors
- **Value Objects**: `PriceId`, `Money`, `Currency`, `EffectiveDate`, `ProductId`
- **Domain Events**: `PriceCreatedEvent`, `PriceUpdatedEvent`, `PriceActivatedEvent`, `PriceScheduledEvent`, `PriceArchivedEvent`
- **Exception**: `PricingDomainException` with `PricingErrorCode` (13 error codes)

### Application Layer

- **Commands**: `CreatePriceCommand`, `UpdatePriceCommand`, `ActivatePriceCommand`, `SchedulePriceCommand`, `ArchivePriceCommand`
- **Results**: `PriceResult`, `PriceSummaryResult`
- **Ports**: `PriceRepository`, `EventPublisher`
- **Use Cases**: `CreatePrice`, `UpdatePrice`, `GetPrice`, `GetPrices`, `ActivatePrice`, `SchedulePrice`, `ArchivePrice`
- **Mapper**: `PriceResultMapper`

### Infrastructure Layer

- **Flyway**: `V14__create_prices_table.sql`
- **R2DBC**: `SpringR2dbcPriceRepository` (Spring Data R2DBC with custom deactivate query)
- **Adapter**: `PriceR2dbcRepositoryAdapter` (TransactionalOperator + async events + optimistic locking)
- **Mapper**: `PriceEntityMapper`
- **Events**: `PricingSpringEventPublisherAdapter`
- **Config**: `PricingInfrastructureConfiguration` (wires all 7 use cases)

### Interfaces Layer

**Public** (read-only):
- `GET /api/prices/{productId}` — All prices for a product
- `GET /api/prices` — All prices (paginated)

**Admin** (requires `ROLE_ADMIN`):
- `POST /api/admin/prices` — Create price
- `PUT /api/admin/prices/{id}` — Update price
- `GET /api/admin/prices/{id}` — Get price by ID
- `PATCH /api/admin/prices/{id}/activate` — Activate price
- `PATCH /api/admin/prices/{id}/schedule` — Schedule price
- `PATCH /api/admin/prices/{id}/archive` — Archive price

## Business Rules Enforced

| Rule | Enforcement |
|------|-------------|
| MRP >= 0 | `Money` value object validation |
| Selling Price >= 0 | `Money` value object validation |
| Selling Price <= MRP | Guard in `Price.create()` and `Price.updatePrice()` |
| Currency is 3-letter ISO | `Currency` value object with supported set |
| Currency is mandatory | Constructor null check |
| Only one ACTIVE per product+currency | Partial unique index `uq_price_product_currency_active` |
| Cannot modify archived prices | Guard in `updatePrice()`, `schedule()`, `activate()` |
| Only DRAFT can be scheduled | Guard in `schedule()` |
| Cannot activate archived | Guard in `activate()` |
| effectiveTo > effectiveFrom | `EffectiveDate` value object validation |
| No physical delete | No DELETE endpoint; archive only |
| Optimistic locking | `@Version` on `PriceEntity` |

## Testing

- **PriceTest**: 17 aggregate tests covering all status transitions, validation, and edge cases
- **CreatePriceUseCaseTest**: 4 use case tests covering happy path, duplicate rejection, and validation
- **Total**: 21 tests, all passing

## Self-Review

### Findings by Severity

| Severity | Count | Notes |
|----------|-------|-------|
| 🔴 BLOCKER | 0 | — |
| 🟠 HIGH | 0 | — |
| 🟡 MEDIUM | 2 | See below |
| 🟢 LOW | 1 | See below |

### 🟡 MEDIUM

1. **No scheduled price auto-activation**: The module creates SCHEDULED prices but has no background job (e.g., Spring `@Scheduled` or a separate scheduler) to activate them when `effective_from` is reached. A future enhancement should add a scheduled task that queries `SELECT * FROM prices WHERE status = 'SCHEDULED' AND effective_from <= NOW()` and activates them.

2. **Price history querying**: The repository only returns prices ordered by `created_at DESC`. For a complete price history feature, add support for filtering by currency, status, effective date range, and pagination.

### 🟢 LOW

1. **Supported currencies hardcoded**: The list of supported currencies is hardcoded in `Currency.java`. Consider externalizing this to configuration or a database table when multi-currency support expands beyond 8 currencies.

### Decision

PR #14 is production-ready and recommended for merge.
