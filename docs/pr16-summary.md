# PR #16 — Checkout Management

## Architecture

The Checkout bounded context follows Clean Architecture with DDD inside the `order` module:

```
presentation (interfaces)
    ↓
application (usecase)
    ↓
domain (model)
    ↓
infrastructure (persistence)
```

### Module: `backend/order/`

```
order/
├── application/checkout/
│   ├── command/              # CQRS command records
│   ├── port/                 # Outbound ports (repository, cart, inventory, pricing, address)
│   ├── result/               # Response DTOs + mapper
│   └── usecase/              # 6 use cases
├── domain/checkout/
│   ├── event/                # 6 domain events
│   ├── exception/            # CheckoutDomainException + CheckoutErrorCode
│   └── model/                # Checkout aggregate, CheckoutItem, value objects, enums
├── infrastructure/checkout/
│   ├── config/               # Spring @Configuration (async event multicaster)
│   ├── event/                # Event publisher adapter
│   └── persistence/          # R2DBC entities, mapper, repository adapter
├── interfaces/checkout/
│   └── dto/                  # Request/Response DTOs
└── resources/db/migration/
    └── V19__create_checkout_tables.sql
```

### Cross-module wiring: `bootstrap/`

`CheckoutModuleConfig.java` wires the `order` module's ports to the actual implementations:

| Port | Source Module | Use Case |
|------|--------------|----------|
| `CartPort` | `cart` | `GetCartUseCase` |
| `InventoryPort` | `inventory` | `GetInventoryByProductUseCase` |
| `PricingPort` | `pricing` | `GetPricesUseCase` |
| `AddressPort` | `identity` | `GetAddressesUseCase` |

---

## Checkout Lifecycle

```
                    ┌─────────────┐
                    │   PENDING   │
                    └──────┬──────┘
                           │
                 ┌─────────┴─────────┐
                 │                   │
          selectAddress()     selectDeliveryMethod()
                 │                   │
                 └─────────┬─────────┘
                           │
                      validate()
                           │
                    ┌──────┴──────┐
                    │  VALIDATED  │
                    └──────┬──────┘
                           │
                  markReadyForPayment()
                           │
                ┌──────────┴──────────┐
                │  READY_FOR_PAYMENT   │  ← Payment initiation (PR #17)
                └─────────────────────┘

Terminal states: EXPIRED (30min TTL), CANCELLED
```

### State Machine

| From | To | Trigger |
|------|-----|---------|
| PENDING | VALIDATED | `validate()` with address + delivery method |
| PENDING | EXPIRED | Time-based (30 min default) |
| PENDING | CANCELLED | `cancel()` |
| VALIDATED | READY_FOR_PAYMENT | `markReadyForPayment()` |
| VALIDATED | EXPIRED | Time-based |
| VALIDATED | CANCELLED | `cancel()` |
| READY_FOR_PAYMENT | EXPIRED | Time-based |
| READY_FOR_PAYMENT | CANCELLED | `cancel()` |

---

## Inventory Validation

During checkout creation:

1. For each cart item, `InventoryPort.checkAvailability(productId, quantity)` is called
2. If `result.sufficient() == false`, a `CheckoutDomainException(ITEM_OUT_OF_STOCK)` is thrown
3. During `validate()`, the same check is repeated to ensure stock hasn't changed

## Price Validation

During checkout creation:

1. For each cart item, `PricingPort.getActivePrice(productId)` is called
2. If no active price exists, `PRICE_NOT_FOUND` error is thrown
3. The latest ACTIVE price's selling price is used for the checkout line items
4. If price changed after cart addition, the checkout uses the current price (user is informed through validation)

## Shipping

- Address is validated via `AddressPort.addressBelongsToCustomer(addressId, customerId)`
- Only addresses belonging to the authenticated user are accepted
- Three delivery methods: STANDARD (5 INR / 5 days), EXPRESS (15 INR / 2 days), NEXT_DAY (25 INR / 1 day)

## Tax Calculation

- Tax is calculated at checkout creation: `subtotal × 18%`
- Recalculated when delivery method changes (affects grand total)
- Formula: `grandTotal = subtotal + tax + shipping - discount`

## Checkout Aggregate Fields

| Field | Type | Description |
|-------|------|-------------|
| id | CheckoutId | UUID aggregate identifier |
| customerId | UUID | Owner of checkout |
| cartId | UUID | Source cart |
| status | CheckoutStatus | PENDING / VALIDATED / READY_FOR_PAYMENT / EXPIRED / CANCELLED |
| shippingAddressId | UUID | Selected shipping address |
| deliveryMethod | DeliveryMethod | Selected delivery method |
| items | List\<CheckoutItem\> | Line items from cart |
| subtotal | BigDecimal | Sum of line totals |
| taxAmount | BigDecimal | 18% of subtotal |
| deliveryCharge | BigDecimal | Based on selected method |
| discountAmount | BigDecimal | Any applicable discount (default 0) |
| totalAmount | BigDecimal | subtotal + tax + delivery - discount |
| currency | String | INR |
| expiresAt | Instant | 30 min from creation |
| version | int | Optimistic locking |

---

## Future Payment Integration (PR#17)

When `CreateOrderUseCase` is implemented in PR#17:

1. The `markReadyForPayment()` call on the checkout aggregate transitions to READY_FOR_PAYMENT
2. An order aggregate is created from the validated checkout
3. Payment gateway integration is initiated
4. On payment success: order is confirmed, checkout expires
5. On payment failure: checkout can be retried

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/checkout` | Create checkout from active cart |
| GET | `/api/v1/checkout/{checkoutId}` | Get checkout by ID |
| GET | `/api/v1/checkout/active` | Get current active checkout |
| POST | `/api/v1/checkout/{checkoutId}/address` | Select shipping address |
| POST | `/api/v1/checkout/{checkoutId}/delivery` | Select delivery method |
| POST | `/api/v1/checkout/{checkoutId}/validate` | Validate checkout |
| POST | `/api/v1/checkout/{checkoutId}/cancel` | Cancel checkout |

All endpoints require JWT authentication. Swagger documentation available under the "Checkout" API group.

---

## Database

### Migration: `V19__create_checkout_tables.sql`

**`checkouts`** table:
- `id UUID PK` — checkout identifier
- `customer_id UUID NOT NULL` — owner
- `cart_id UUID NOT NULL` — source cart
- `status VARCHAR(30) NOT NULL DEFAULT 'PENDING'` — state machine
- `shipping_address_id UUID` — selected address
- `delivery_method_code VARCHAR(30)` — STANDARD/EXPRESS/NEXT_DAY
- `delivery_method_name VARCHAR(100)` — display name
- `delivery_charge DECIMAL(10,2)` — shipping cost
- `discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00` — discount
- `subtotal DECIMAL(10,2)`, `tax_amount DECIMAL(10,2)`
- `total_amount DECIMAL(10,2)` — grand total
- `currency VARCHAR(3) DEFAULT 'INR'`
- `notes VARCHAR(500)`
- `expires_at TIMESTAMPTZ NOT NULL` — 30 min TTL
- `validated_at TIMESTAMPTZ`, `cancelled_at TIMESTAMPTZ`
- `version INT NOT NULL DEFAULT 0` — optimistic locking
- `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`

**`checkout_items`** table:
- `id UUID PK`, `checkout_id UUID FK → checkouts(id)`
- `product_id UUID`, `product_name VARCHAR(255)`
- `sku VARCHAR(50)`, `quantity INT CHECK (>0)`
- `unit_price DECIMAL(10,2)`, `line_total DECIMAL(10,2)`
- `image_url VARCHAR(500)`, `created_at TIMESTAMPTZ`

**Indexes:**
- `idx_checkouts_customer_id` on `checkouts(customer_id)`
- `idx_checkouts_status` on `checkouts(status)`
- `idx_checkouts_expires_at` on `checkouts(expires_at)`
- `idx_checkout_items_checkout_id` on `checkout_items(checkout_id)`

---

## Testing

| Test File | Type | Coverage |
|-----------|------|----------|
| `CheckoutTest.java` | Domain unit test | Aggregate creation, address selection, delivery selection, validation, expiry, cancellation, state transitions |
| `CreateCheckoutUseCaseTest.java` | Application use case | Happy path, active checkout exists, cart not found, empty cart, insufficient stock, price not found |
| `ValidateCheckoutUseCaseTest.java` | Application use case | Happy path validation, checkout not found, customer mismatch, insufficient stock during validation |
| `PublicCheckoutControllerTest.java` | Controller | Create, get by ID, get active, select address, select delivery, validate, cancel |
| `CheckoutMigrationTest.java` | Migration (Testcontainers) | Table existence, columns, FK constraint, indexes, valid insert, quantity check constraint |

---

## Optimistic Locking

The `checkouts` table has a `version` column annotated with `@Version` in `CheckoutEntity`. Spring Data R2DBC automatically manages optimistic locking:

- On save, Spring checks that the version in the DB matches the entity's version
- If another request modified the checkout concurrently, `OptimisticLockingFailureException` is thrown
- The repository adapter maps this to `PricingDomainException(OPTIMISTIC_LOCKING_CONFLICT)` which returns HTTP 409
- The frontend can retry the operation

---

## Frontend

### Checkout Page (`frontend/src/features/checkout/CheckoutPage.tsx`)

The checkout page provides a complete checkout flow:

1. **Initialization**: Fetches active checkout or creates one from the cart
2. **Address Selection**: Lists user's saved addresses from `/api/customers/me/addresses`
3. **Delivery Method**: Radio group with STANDARD, EXPRESS, NEXT_DAY options
4. **Items Summary**: Displays cart items with quantities and line totals
5. **Price Breakdown**: Subtotal, shipping, tax (18%), discount, grand total
6. **Validation**: "Validate Checkout" button → validates address, delivery, inventory
7. **Continue to Payment**: Enabled only after successful validation

### API Client (`frontend/src/api/checkout.ts`)
- `create()` — POST /api/v1/checkout
- `getById(id)` — GET /api/v1/checkout/{id}
- `getActive()` — GET /api/v1/checkout/active
- `selectAddress(checkoutId, payload)` — POST /api/v1/checkout/{id}/address
- `selectDeliveryMethod(checkoutId, payload)` — POST /api/v1/checkout/{id}/delivery
- `validate(checkoutId)` — POST /api/v1/checkout/{id}/validate
- `cancel(checkoutId)` — POST /api/v1/checkout/{id}/cancel

### Types (`frontend/src/types/checkout.ts`)
- `CheckoutResponse` — full checkout data with items
- `CreateCheckoutResponse` — subset returned on creation
- `CheckoutItemResponse` — line item data
- `SelectAddressPayload`, `SelectDeliveryMethodPayload`

---

## Files Created/Modified

### Backend (order module — all new)
- `domain/checkout/model/Checkout.java` — aggregate root
- `domain/checkout/model/CheckoutStatus.java` — state machine enum
- `domain/checkout/model/CheckoutItem.java` — line item entity
- `domain/checkout/model/CheckoutId.java` — typed UUID value object
- `domain/checkout/model/CheckoutItemId.java` — typed UUID value object
- `domain/checkout/model/DeliveryMethod.java` — delivery method value object
- `domain/checkout/event/` — 6 domain events
- `domain/checkout/exception/` — `CheckoutDomainException` + `CheckoutErrorCode`
- `application/checkout/command/` — 3 command records
- `application/checkout/port/` — 6 port interfaces
- `application/checkout/result/` — 2 result records + mapper
- `application/checkout/usecase/` — 6 use cases
- `infrastructure/checkout/persistence/entity/` — 2 R2DBC entities
- `infrastructure/checkout/persistence/mapper/` — entity mapper
- `infrastructure/checkout/persistence/repository/` — 3 repositories
- `infrastructure/checkout/config/` — infrastructure configuration
- `infrastructure/checkout/event/` — event publisher adapter
- `interfaces/checkout/PublicCheckoutController.java` — REST controller
- `interfaces/checkout/dto/` — 5 request/response DTOs
- `resources/db/migration/V19__create_checkout_tables.sql` — Flyway migration

### Bootstrap (modified)
- `bootstrap/src/main/java/com/dsports/config/CheckoutModuleConfig.java` — port wiring
- `bootstrap/src/main/java/com/dsports/config/OpenApiConfig.java` — Swagger checkout group (already present)
- `bootstrap/src/main/java/com/dsports/exception/GlobalExceptionHandler.java` — Checkout error handler (already present)

### Frontend (modified/created)
- `frontend/src/features/checkout/CheckoutPage.tsx` — complete checkout page
- `frontend/src/api/checkout.ts` — API client
- `frontend/src/types/checkout.ts` — TypeScript types
- `frontend/src/routes/index.tsx` — /checkout route

### Tests (new)
- `application/checkout/usecase/CreateCheckoutUseCaseTest.java`
- `application/checkout/usecase/ValidateCheckoutUseCaseTest.java`
- `interfaces/checkout/PublicCheckoutControllerTest.java`
- `infrastructure/checkout/persistence/CheckoutMigrationTest.java`

### Documentation (new)
- `docs/pr16-summary.md`

---

## Production Ready Checklist

- [x] All layers follow Clean Architecture with DDD (no layer violations)
- [x] Reactive stack throughout (WebFlux, R2DBC, Reactor)
- [x] Optimistic locking with `@Version` for concurrent modification handling
- [x] Proper error codes mapped to HTTP status codes (400, 401, 403, 404, 409, 410, 500)
- [x] Swagger/OpenAPI documentation with "Checkout" group
- [x] JWT authentication required for all endpoints; ownership validation
- [x] Flyway migration with indexes, FK constraints, CHECK constraints
- [x] Domain events published for all state transitions
- [x] Configurable checkout expiry (30 min default)
- [x] No blocking code, no field injection, no commented code
- [x] Comprehensive test coverage: domain, use cases, controller, migration
- [x] Frontend with address selection, delivery method, validation flow, grand total display
