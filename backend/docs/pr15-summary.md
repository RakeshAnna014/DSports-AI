# PR #15 – Shopping Cart Management

## Overview

Implements a complete Shopping Cart bounded context following Domain Driven Design (DDD) and Clean Architecture. The cart module integrates with the existing Product Catalog, Inventory, Pricing, and Authentication modules.

---

## Architecture

```
presentation (PublicCartController)
     ↓
application (use cases, commands, ports)
     ↓
domain      (Cart aggregate, value objects, domain events)
     ↓
infrastructure (R2DBC persistence, event publishing)
```

### Package Structure

```
cart/
  domain/
    model/        Cart, CartItem, CartId, CartItemId, UserId, Quantity, Money, CartStatus
    event/        CartCreatedEvent, ProductAddedToCartEvent, CartItemUpdatedEvent,
                  CartItemRemovedEvent, CartClearedEvent
    exception/    CartDomainException, CartErrorCode
  application/
    command/      CreateCartCommand, AddToCartCommand, UpdateCartItemCommand,
                  RemoveCartItemCommand, ClearCartCommand
    port/         CartRepository, EventPublisher, ProductCatalogPort, InventoryPort, PricingPort
    result/       CartResult, CartItemResult, CartSummaryResult
    usecase/      CreateCartUseCase, AddToCartUseCase, UpdateCartItemUseCase,
                  RemoveCartItemUseCase, ClearCartUseCase, GetCartUseCase
  infrastructure/
    config/       CartInfrastructureConfiguration
    event/        CartSpringEventPublisherAdapter
    persistence/
      entity/     CartEntity, CartItemEntity
      mapper/     CartEntityMapper
      repository/ SpringR2dbcCartRepository, CartR2dbcRepositoryAdapter
  interfaces/
    PublicCartController, AddItemRequest, UpdateQuantityRequest
```

### Cross-module integration

```
bootstrap/
  config/
    CartModuleConfig.java     Adapter beans that implement cart ports using catalog/inventory/pricing use cases
  exception/
    GlobalExceptionHandler.java  (updated) Handles CartDomainException
  config/
    OpenApiConfig.java            (updated) Adds "Shopping Cart" Swagger group
```

---

## Business Rules

| Rule | Implementation |
|------|----------------|
| Only one ACTIVE cart per user | Unique partial index `uq_carts_user_active` on `carts(user_id) WHERE status = 'ACTIVE'` |
| Cart belongs to authenticated user | UserId derived from JWT, never from client |
| Same product added again increases quantity | `Cart.addItem()` checks `findItemByProductId()` before adding |
| Maximum quantity per item = 99 | `Quantity` value object validates in compact constructor |
| Maximum different products = 50 | `Cart.addItem()` checks `items.size() >= 50` |
| Quantity must be > 0 | `Quantity` validates `value > 0` |
| Product must exist and be ACTIVE | `AddToCartUseCase.validateProduct()` via `ProductCatalogPort` |
| Inventory must be available | `AddToCartUseCase.validateInventory()` via `InventoryPort` |
| Use latest ACTIVE price | `AddToCartUseCase.getActivePrice()` via `PricingPort` (filters ACTIVE status) |
| Price snapshot stored in CartItem | `CartItem.unitPrice` stores price at time of add |
| Removing last product leaves empty ACTIVE cart | `Cart.removeItem()` calls `calculateTotals()` — cart stays ACTIVE |

---

## Cart Lifecycle

```
Created (ACTIVE) ──→ addItem / updateQuantity / removeItem / clear ──→ remains ACTIVE
     │
     ├──→ checkout() ──→ CHECKED_OUT (final state, no further modifications)
     │
     └──→ abandon()  ──→ ABANDONED  (final state)
```

---

## API Endpoints

| Method | Path | Description | Request Body |
|--------|------|-------------|--------------|
| `GET` | `/api/v1/cart` | Get current user's active cart | - |
| `POST` | `/api/v1/cart/items` | Add item to cart (creates cart if needed) | `{ productId, quantity }` |
| `PUT` | `/api/v1/cart/items/{itemId}` | Update item quantity | `{ quantity }` |
| `DELETE` | `/api/v1/cart/items/{itemId}` | Remove item from cart | - |
| `DELETE` | `/api/v1/cart` | Clear all items | - |

All endpoints require JWT authentication (Bearer token).

### Response Format

```json
{
  "id": "uuid",
  "userId": "uuid",
  "status": "ACTIVE",
  "totalItems": 3,
  "totalAmount": 149.97,
  "version": 1,
  "items": [
    {
      "id": "uuid",
      "productId": "uuid",
      "productName": "Cricket Bat",
      "unitPrice": 49.99,
      "quantity": 3,
      "lineTotal": 149.97
    }
  ]
}
```

---

## Inventory Validation Strategy

When adding an item or increasing quantity:

1. `AddToCartUseCase` calls `InventoryPort.checkAvailability(productId, requestedQuantity)`
2. Bootstrap adapter `InventoryPort` uses `GetInventoryByProductUseCase` to sum `availableQuantity` across all warehouses
3. If `availableQuantity < requestedQuantity`, the use case throws `CartDomainException(INSUFFICIENT_STOCK, ...)`
4. The exception is mapped to HTTP 409 Conflict by `GlobalExceptionHandler`

---

## Pricing Snapshot Strategy

When adding an item:

1. `AddToCartUseCase` calls `PricingPort.getActivePrice(productId)`
2. Bootstrap adapter filters `GetPricesUseCase` results to find the first price with `status == "ACTIVE"`
3. The `sellingPrice` is stored as `unitPrice` in the `CartItem`
4. This creates a price snapshot — future price changes don't affect existing cart items
5. `lineTotal` is computed as `unitPrice × quantity`

---

## Database Changes

### New Migration: `V18__create_cart_tables.sql`

**Table: `carts`**

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `UUID` | PK |
| `user_id` | `UUID` | NOT NULL |
| `status` | `VARCHAR(20)` | NOT NULL, DEFAULT 'ACTIVE', CHECK (IN 'ACTIVE','CHECKED_OUT','ABANDONED') |
| `total_items` | `INTEGER` | NOT NULL, DEFAULT 0, CHECK ≥ 0 |
| `total_amount` | `DECIMAL(10,2)` | NOT NULL, DEFAULT 0, CHECK ≥ 0 |
| `version` | `INTEGER` | NOT NULL, DEFAULT 0 (optimistic locking) |
| `created_at` / `updated_at` | `TIMESTAMP` | NOT NULL |

**Indexes:** `uq_carts_user_active UNIQUE (user_id) WHERE status = 'ACTIVE'`

**Table: `cart_items`**

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `UUID` | PK |
| `cart_id` | `UUID` | FK → carts(id) ON DELETE CASCADE |
| `product_id` | `UUID` | NOT NULL |
| `product_name` | `VARCHAR(200)` | NOT NULL (snapshot) |
| `unit_price` | `DECIMAL(10,2)` | NOT NULL, CHECK ≥ 0 (snapshot) |
| `quantity` | `INTEGER` | NOT NULL, CHECK > 0 |
| `line_total` | `DECIMAL(10,2)` | NOT NULL, CHECK ≥ 0 |

**Indexes:** `uq_cart_items_cart_product UNIQUE (cart_id, product_id)`

---

## Frontend Integration

### API Service (`src/api/cart.ts`)

New module providing:
- `cartApi.getCart()` — GET /api/v1/cart
- `cartApi.addItem(productId, quantity)` — POST /api/v1/cart/items
- `cartApi.updateQuantity(itemId, quantity)` — PUT /api/v1/cart/items/{itemId}
- `cartApi.removeItem(itemId)` — DELETE /api/v1/cart/items/{itemId}
- `cartApi.clearCart()` — DELETE /api/v1/cart

### Store (`src/store/cartStore.ts`)

Rewritten to:
- Fetch cart from backend on init (if authenticated)
- All mutations call backend APIs then refresh state
- Maintains localStorage fallback for anonymous users
- `totalItems` auto-updates from backend response

### Components

- **MainLayout** — cart badge reads `totalItems` from store (already wired)
- **ProductCard** — Add to Cart calls `cartApi.addItem()`
- **CartPage** — Uses backend responses for items, quantities, totals

---

## Security

- All cart endpoints require JWT authentication
- User ID extracted from JWT subject, never from request body
- Only active cart for the authenticated user is accessible
- Global exception handler maps `CartDomainException` to appropriate HTTP status codes

---

## Future Checkout Integration

When the Order module is implemented:

1. `Cart.checkout()` transitions status to `CHECKED_OUT`
2. Order module calls `GetCartUseCase` to read the checked-out cart
3. Cart items become order line items with the price snapshot
4. Inventory reservations are finalized
5. Cart becomes read-only (`addItem/remove/clear` throw for CHECKED_OUT carts)

---

## Testing

### Unit Tests (`CartTest.java`)

| Test Group | Tests |
|------------|-------|
| Cart Creation | Active status, zero totals, domain event emission |
| Adding Items | New item, duplicate product (quantity increase), max quantity, max items |
| Updating Items | Quantity change, non-existent item, event emission |
| Removing Items | Remove, non-existent item, event emission |
| Clearing Cart | All items removed, totals reset, event emission |
| Status Transitions | Checkout, abandon, reject modifications on non-active carts |
| Reconstitute | Restore from persistence |
