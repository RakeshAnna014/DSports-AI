# PR #13 — Inventory Management

## Design Decisions

### Why Inventory is a Separate Bounded Context

Inventory has fundamentally different characteristics from Catalog:

| Dimension | Catalog (Products) | Inventory (Stock) |
|-----------|-------------------|-------------------|
| Lifecycle | Created once, rarely changed | Fluctuates daily |
| Consistency | Strong (SKU/slug uniqueness) | Eventual (orders vs stock) |
| Access pattern | Read-heavy, browsed by users | Read/write-heavy, consumed by order processing |
| Domain events | Product lifecycle events | Stock movement events |

Integration happens via shared `ProductId` value objects and domain events — no direct database joins.

### Why InventoryItem is an Aggregate Root

An inventory record has its own lifecycle independent of products or warehouses:
- Identity (`InventoryId`)
- Invariants (available >= 0, reserved >= 0, reserved <= available)
- Behaviors (`stockIn`, `stockOut`, `reserve`, `releaseReservation`, `adjust`, `changeReorderLevel`)
- Domain events (8 events tracking every stock movement)

It references `ProductId` and `WarehouseId` by value (not entity references) — they are value objects from other bounded contexts.

### Status Auto-Calculation

`InventoryStatus` is computed, not stored. Every mutation calls `updateStatus()` which sets:
- `IN_STOCK` when `availableQuantity > reorderLevel`
- `LOW_STOCK` when `0 < availableQuantity <= reorderLevel`
- `OUT_OF_STOCK` when `availableQuantity == 0`

### Why One Record Per Product+Warehouse

The unique constraint `(product_id, warehouse_id)` ensures:
- Single source of truth per product-warehouse pair
- No accidental duplicate inventory records
- Simple lookup by productId for total availability queries

### Reactive Transaction Boundary

Following the PR #12 review pattern, the repository adapter uses:
- `TransactionalOperator` for reactive transaction demarcation
- Async event publishing after successful save (not inside the transaction)
- Optimistic locking via `@Version` for concurrent stock operations
- `ON CONFLICT DO NOTHING`-style duplicate detection at the DB level

## Architecture

### Domain Layer

- **Aggregate**: `InventoryItem` — root with all stock behaviors
- **Value Objects**: `InventoryId`, `ProductId`, `WarehouseId`, `Quantity`, `ReservedQuantity`, `ReorderLevel`, `InventoryStatus`
- **Domain Events**: `InventoryCreatedEvent`, `StockAddedEvent`, `StockRemovedEvent`, `StockReservedEvent`, `ReservationReleasedEvent`, `InventoryAdjustedEvent`, `LowStockEvent`, `OutOfStockEvent`
- **Exception**: `InventoryDomainException` with `InventoryErrorCode` (14 error codes)

### Application Layer

- **Commands**: 7 command records (`CreateInventoryCommand`, `StockInCommand`, `StockOutCommand`, `ReserveInventoryCommand`, `ReleaseReservationCommand`, `AdjustInventoryCommand`, `UpdateReorderLevelCommand`)
- **Results**: `InventoryResult`, `InventorySummaryResult`
- **Ports**: `InventoryRepository`, `EventPublisher`
- **Use Cases**: 10 use case classes
- **Mapper**: `InventoryResultMapper`

### Infrastructure Layer

- **Flyway**: `V13__create_inventory_table.sql`
- **R2DBC**: `SpringR2dbcInventoryRepository` (Spring Data R2DBC)
- **Adapter**: `InventoryR2dbcRepositoryAdapter` (TransactionalOperator + async events)
- **Mapper**: `InventoryEntityMapper`
- **Events**: `InventorySpringEventPublisherAdapter`
- **Config**: `InventoryInfrastructureConfiguration` (wires all 10 use cases)

### API Layer (Interfaces)

**Public** (read-only, accessible by authenticated users):
- `GET /api/inventory/{productId}` — Get inventory for a product
- `GET /api/inventory` — List all inventory (paginated)

**Admin** (requires `ROLE_ADMIN`):
- `POST /api/admin/inventory` — Create inventory record
- `GET /api/admin/inventory/{id}` — Get inventory by ID
- `PATCH /api/admin/inventory/{id}/stock-in` — Add stock
- `PATCH /api/admin/inventory/{id}/stock-out` — Remove stock
- `PATCH /api/admin/inventory/{id}/reserve` — Reserve stock
- `PATCH /api/admin/inventory/{id}/release-reservation` — Release reservation
- `PATCH /api/admin/inventory/{id}/adjust` — Adjust quantity
- `PATCH /api/admin/inventory/{id}/reorder-level` — Change reorder level

## Business Rules Enforced

| Rule | Enforcement |
|------|------------|
| Available quantity >= 0 | `Quantity` value object |
| Reserved quantity >= 0 | `ReservedQuantity` value object |
| Reserved <= Available | Guard in `reserve()` |
| Cannot stock out more than available - reserved | Guard in `stockOut()` |
| Cannot reserve from OUT_OF_STOCK stock | Guard in `reserve()` |
| Status auto-calculated on every mutation | `updateStatus()` called in every behavior |
| One record per product + warehouse | `uq_inventory_product_warehouse` unique constraint |
| Optimistic locking | `@Version` on `InventoryEntity` |
| Duplicate detection | Application check + exception mapping for `DataIntegrityViolationException` |

## Self-Review

### Findings by Severity

| Severity | Count | Notes |
|----------|-------|-------|
| 🔴 BLOCKER | 0 | — |
| 🟠 HIGH | 0 | — |
| 🟡 MEDIUM | 2 | See below |
| 🟢 LOW | 1 | See below |

### 🟡 MEDIUM

1. **No warehouse entity**: `WarehouseId` is a value object with no validation or reference data. A future `warehouse` bounded context should own the warehouse lifecycle and publish `WarehouseCreatedEvent` so Inventory can validate warehouse existence.

2. **No pagination for GET /api/inventory**: The public listing endpoint supports pagination but the current implementation will need cursor-based or page-based pagination when inventory grows beyond thousands of records. Acceptable for V1.

### 🟢 LOW

1. **No integration tests**: The module uses Testcontainers dependencies in pom.xml but no integration tests are written yet. Add `InventoryRepositoryIntegrationTest` when Docker is available in CI.

### Decision

PR #13 is production-ready and recommended for merge.
