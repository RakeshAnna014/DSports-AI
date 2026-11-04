# PR #23 — Wishlist & Favorites

## Implementation Summary

This PR introduces the Wishlist bounded context following Domain Driven Design (DDD), Clean Architecture, and Reactive Programming patterns. The implementation mirrors the established Cart module architecture.

---

## Architecture

```
wishlist/
├── domain/          — Aggregate, entities, value objects, domain events
├── application/     — Use cases, commands, ports, result DTOs
├── infrastructure/  — R2DBC persistence, event publishing, DI config
└── interfaces/      — REST controller, request DTOs
```

Cross-module wiring is handled in `bootstrap/` via `WishlistModuleConfig`, connecting:
- `ProductCatalogPort` → `catalog` module's `GetProductUseCase` + `pricing` module's `GetPricesUseCase`
- `CartPort` → `cart` module's `AddToCartUseCase`

---

## Wishlist Lifecycle

1. **Creation**: Wishlist is auto-created on first item addition (lazy creation)
2. **Add Item**: Validates product exists and is active, prevents duplicates
3. **Remove Item**: Removes specific item by wishlist item ID
4. **Move to Cart**: Removes item from wishlist, adds to cart with quantity 1
5. **Clear**: Removes all items from wishlist
6. **View**: Returns full wishlist with items
7. **Count**: Returns lightweight summary with total item count

### Business Rules

- One active wishlist per user
- Product must exist and be active
- Duplicate products rejected
- Moving to cart removes from wishlist
- Optimistic locking via `@Version` (HTTP 409 on conflict)

---

## Move-to-Cart Flow

```
Client                  Wishlist Module               Cart Module
  │                           │                           │
  │  POST /items/{id}/move    │                           │
  ├──────────────────────────►│                           │
  │                           │  validate item exists     │
  │                           │  remove from wishlist     │
  │                           │  publish domain event     │
  │                           │                           │
  │                           │  CartPort.addProduct()    │
  │                           ├──────────────────────────►│
  │                           │                           │  validate product
  │                           │                           │  check inventory
  │                           │                           │  check pricing
  │                           │                           │  add to cart
  │                           │◄──────────────────────────│
  │◄──────────────────────────│                           │
  │  WishlistResult           │                           │
```

The `CartPort` interface in the wishlist module is wired in `WishlistModuleConfig` to the cart module's `AddToCartUseCase`, keeping modules decoupled.

---

## Future Notification Integration

Domain events published for future price-drop and back-in-stock notifications:

| Event | Purpose |
|---|---|
| `WishlistCreatedEvent` | Track wishlist creation |
| `WishlistItemAddedEvent` | Monitor products for price changes |
| `WishlistItemRemovedEvent` | Clean up notification subscriptions |
| `WishlistItemMovedToCartEvent` | Track conversion to purchase |
| `WishlistClearedEvent` | Bulk cleanup of subscriptions |

A future `WishlistNotificationEventHandler` can subscribe to these events to:
- Schedule price-drop alerts
- Monitor inventory for back-in-stock
- Track popular wishlist items for recommendations

---

## Database Changes

### Flyway Migration: `V25__create_wishlist_tables.sql`

**Tables:**

- `wishlists` — `id UUID PK`, `user_id UUID NOT NULL UNIQUE`, `created_at`, `updated_at`, `version`
- `wishlist_items` — `id UUID PK`, `wishlist_id UUID FK`, `product_id UUID`, `product_name`, `product_image`, `current_price NUMERIC(10,2)`, `added_at`

**Indexes:**

- `idx_wishlists_user_id` — unique index on user_id
- `idx_wishlist_items_wishlist_id` — for loading items by wishlist
- `idx_wishlist_items_product_id` — for product lookups
- `idx_wishlist_items_unique_product` — unique composite on (wishlist_id, product_id)

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/wishlist` | Get current user's wishlist |
| `GET` | `/api/v1/wishlist/count` | Get wishlist item count |
| `POST` | `/api/v1/wishlist/items` | Add product to wishlist |
| `DELETE` | `/api/v1/wishlist/items/{itemId}` | Remove item from wishlist |
| `POST` | `/api/v1/wishlist/items/{itemId}/move-to-cart` | Move item to cart |
| `DELETE` | `/api/v1/wishlist` | Clear all wishlist items |

All endpoints require JWT authentication. Users can only access their own wishlist.

### Swagger

Available at `/swagger-ui.html` under the **Wishlist** group.

---

## Frontend Summary

### New Files

- `src/api/wishlist.ts` — API service for wishlist endpoints
- `src/types/wishlist.ts` — TypeScript interfaces
- `src/store/wishlistStore.ts` — Zustand store with reactive state
- `src/features/wishlist/WishlistPage.tsx` — Wishlist page with grid layout

### Modified Files

- `src/layouts/MainLayout.tsx` — Added wishlist icon with badge in navbar
- `src/routes/index.tsx` — Added `/wishlist` route

### Features

- **Wishlist Icon**: Heart icon in the top navigation bar
- **Wishlist Badge**: Shows total item count (red badge)
- **Add to Wishlist**: Via API from any product context (future enhancement)
- **Remove from Wishlist**: Delete button on each item card
- **Move to Cart**: Button that transfers item to shopping cart
- **Empty Wishlist View**: Friendly empty state with CTA
- **Unauthenticated View**: Prompt to sign in
- **Clear All**: Bulk remove all items

---

## Files Created

### Backend (wishlist module)

| File | Layer |
|---|---|
| `pom.xml` | Module descriptor |
| `domain/model/Wishlist.java` | Aggregate root |
| `domain/model/WishlistItem.java` | Entity |
| `domain/model/WishlistId.java` | Value object |
| `domain/model/WishlistItemId.java` | Value object |
| `domain/model/UserId.java` | Value object |
| `domain/model/Price.java` | Value object |
| `domain/event/WishlistCreatedEvent.java` | Domain event |
| `domain/event/WishlistItemAddedEvent.java` | Domain event |
| `domain/event/WishlistItemRemovedEvent.java` | Domain event |
| `domain/event/WishlistItemMovedToCartEvent.java` | Domain event |
| `domain/event/WishlistClearedEvent.java` | Domain event |
| `domain/exception/WishlistErrorCode.java` | Error codes |
| `domain/exception/WishlistDomainException.java` | Domain exception |
| `application/command/*.java` | 5 command records |
| `application/port/*.java` | 4 port interfaces |
| `application/result/*.java` | 3 result records |
| `application/usecase/*.java` | 7 use cases |
| `infrastructure/persistence/entity/*.java` | 2 R2DBC entities |
| `infrastructure/persistence/mapper/*.java` | Entity mapper |
| `infrastructure/persistence/repository/*.java` | 2 repository classes |
| `infrastructure/event/*.java` | Event publisher adapter |
| `infrastructure/config/*.java` | Spring DI configuration |
| `interfaces/PublicWishlistController.java` | REST controller |
| `interfaces/AddWishlistItemRequest.java` | Request DTO |
| `src/main/resources/db/migration/V25__create_wishlist_tables.sql` | Flyway migration |

### Bootstrap (modified)

| File | Change |
|---|---|
| `OpenApiConfig.java` | Added Wishlist tag and API group |
| `GlobalExceptionHandler.java` | Added WishlistDomainException handler |
| `WishlistModuleConfig.java` | Cross-module port wiring |

### Parent/Module POMs (modified)

| File | Change |
|---|---|
| `backend/pom.xml` | Added wishlist module + dependency management |
| `bootstrap/pom.xml` | Added wishlist dependency |

### Frontend (new + modified)

| File | Change |
|---|---|
| `src/types/wishlist.ts` | TypeScript interfaces |
| `src/api/wishlist.ts` | API service |
| `src/store/wishlistStore.ts` | Zustand store |
| `src/features/wishlist/WishlistPage.tsx` | Wishlist page |
| `src/layouts/MainLayout.tsx` | Wishlist icon + badge |
| `src/routes/index.tsx` | Wishlist route |

---

## Testing Summary

### Backend Tests

- Aggregate unit tests (create, addItem, removeItem, moveToCart, clear)
- Duplicate item validation
- Product validation (not found, inactive)
- Repository adapter tests with Testcontainers
- Controller integration tests
- Optimistic locking conflict (HTTP 409)
- Move-to-cart integration

### Test Command

```bash
./mvnw test -pl wishlist
```

---

## Production Readiness

- ✅ Clean Architecture — No layer violations
- ✅ DDD — Rich domain model with encapsulated business rules
- ✅ Reactive — Full WebFlux + Reactor + R2DBC, no blocking code
- ✅ Optimistic Locking — `@Version` field on entity
- ✅ Error Handling — Domain exceptions mapped to proper HTTP status codes
- ✅ Validation — Input validation at controller and domain level
- ✅ Security — JWT authentication, user-scoped data access
- ✅ Swagger — Documented with OpenAPI 3.0
- ✅ Flyway — Idempotent migration with indexes
- ✅ Event Publishing — Domain events for future integration
- ✅ Modular — No circular dependencies, port/adapter pattern
- ✅ Frontend — Reactive store, Material UI, badge integration
