# 03 — Business Rule Validation

Evidence tags: **[RUN]** · **[CODE]** · **[DB]** · **[INF]**. See [README](README.md).

## As-built order-to-cash flow

```
Cart(ACTIVE) ──POST /checkout──► Checkout(PENDING) ──address+delivery──► ──validate──► Checkout(VALIDATED)
      │                                                                                    │
      │  (expected: Cart→CHECKED_OUT)  ✗ never happens (wrong ID passed)                    │
      ▼                                                                                    ▼
POST /orders ──► existsByCheckoutId? ─► stock CHECK (no reservation) ─► save Order(PENDING_PAYMENT)  ✗ 500 today
                                                                                           │
POST /payments ──► Payment CREATED→PENDING→PROCESSING ──mock gateway──► AUTHORIZED          │
                                                                                           │
POST /payments/{id}/capture ✗ (never called by UI) ──► SUCCESS ──PaymentSucceededEvent──►   │
   PaymentSucceededEventHandler (fire-and-forget .subscribe):                              │
     order.confirm()  → CONFIRMED                                                          │
     inventory: fulfillReservation() if reserved ≥ qty, else stockOut()  (errors swallowed)│
     cart.complete()  ✗ cart is ACTIVE, not CHECKED_OUT → throws (swallowed);               │
                      even if it ran, DB CHECK forbids COMPLETED                            │
     publish OrderConfirmedEvent ──► Shipment listener (.subscribe) creates shipment with  │
                                     address "N/A", may read the order before commit        │
```

## Cart

| Rule | Status | Notes |
|---|---|---|
| Product exists | ✅ | `ProductCatalogPort` → `PRODUCT_NOT_FOUND` |
| Product is active | ✅ | ACTIVE status checked |
| Variant exists | ⬜ | No variants in the model |
| Quantity > 0 and ≤ max | ✅ | `Quantity` VO and DB CHECK `quantity > 0` |
| Requested qty ≤ stock | 🟡 **Bug** | Adding to an existing line checks only the *increment* against stock, not existing + increment [CODE `AddToCartUseCase`] |
| Stock accounts for reservations | 🔴 | Availability sums `available_quantity` and ignores `reserved_quantity` [CODE `CartModuleConfig.inventoryPort`] |
| Price consistency | 🟡 | Price is captured at add time and never refreshed. Adding more of an existing item keeps the old unit price. The pricing port ignores `effective_from/to` and currency [CODE] |
| Duplicate cart items | ✅ | Merged by productId, plus DB unique `(cart_id, product_id)` |
| One active cart per user | ✅ | Partial unique index `uq_carts_user_active`, mapped to `DUPLICATE_ACTIVE_CART` |
| Concurrent updates | ✅ | `version` optimistic lock → `OPTIMISTIC_LOCKING_CONFLICT` |
| Cart ownership | ✅ | Always resolved from the JWT subject |
| Guest → authenticated merge | ⬜ | No guest cart |
| Max distinct products (50) | ✅ | Domain rule |
| Cart lifecycle after purchase | 🔴 | See the flow above. The cart stays ACTIVE with its items after an order. The DB CHECK lacks `COMPLETED` [DB] |
| Domain events | 🟡 | Published after the transaction from the adapter. `AddToCartUseCase` itself has no publisher (fine). Events are lost if the process dies between commit and publish |

## Checkout

| Rule | Status | Notes |
|---|---|---|
| Cart exists and is not empty | ✅ | |
| Only one active checkout | 🟡 | Enforced by `existsActiveCheckout`, but the error code is `CHECKOUT_ALREADY_TERMINAL` (semantically wrong). Check-then-act, with no DB constraint behind it |
| Stock revalidated at validate | 🟡 | Yes, but ignoring reservations (same port issue) |
| Price revalidated | 🔴 | Only checks that *an* active price exists. The cart's frozen unit price is used for totals |
| Tax | 🟡 | Hard-coded `TAX_RATE = 0.18` on the subtotal (excludes delivery), set in the domain. No HSN/category GST rules, and nothing configurable |
| Delivery charge | 🟡 | Hard-coded constants (₹5 / ₹15 / ₹25) in `DeliveryMethod` |
| Expiry (30 min) | 🟡 | Enforced on mutations. **Order placement does not check `isExpired()`**, so an expired VALIDATED checkout can still become an order [CODE `CheckoutDataPort`] |
| Status after order | 🔴 | Checkout stays VALIDATED. It never becomes COMPLETED or READY_FOR_PAYMENT |
| Ownership | ✅ | `CHECKOUT_NOT_OWNED_BY_CUSTOMER` → 403 (body) |

## Order

| Rule | Status | Notes |
|---|---|---|
| Create from a validated checkout | 🔴 | Always fails: `order_items` save becomes an UPDATE because the entity is not `Persistable` [RUN] |
| Duplicate order prevention | 🟡 | `existsByCheckoutId` check-then-act. **No unique constraint on `orders.checkout_id`**, so concurrent submits create duplicates [DB] |
| Idempotency key | ⬜ | Documented in the architecture doc, not implemented |
| Atomicity | 🔴 | No transaction around check → (reserve) → cart update → order save. A partial failure leaves inconsistent state [CODE] |
| Inventory reservation | 🔴 | `InventoryReservationPort.reserveInventory` only **checks** availability [CODE `OrderModuleConfig`] |
| Order number | 🔴 | `count(*) + 1`. Concurrent orders get the same number and the unique index throws a 500. The count also drifts if rows are ever deleted [CODE] |
| Address snapshot | 🟡 | `fullName` and `phone` are always empty strings. Billing address is always null, so it falls back to shipping [CODE] |
| State machine | 🟡 | CREATED → PENDING_PAYMENT → CONFIRMED → PROCESSING → SHIPPED → DELIVERED / CANCELLED / REFUNDED in the domain. The admin can set status through `PUT /admin/orders/{id}/status` |
| Customer cancel | 🟡 | No inventory release, payment void, or refund. Only a notification [CODE] |
| Admin list/search | ⬜ | Only get-by-id and update-status |

## Payment

| Rule | Status | Notes |
|---|---|---|
| Order exists and is owned | ✅ | |
| Amount and currency match the order | ✅ | `AMOUNT_MISMATCH` |
| Prevent paying an already-paid order | 🟡 | `alreadyPaid` covers CONFIRMED/PROCESSING/SHIPPED/DELIVERED only. **CANCELLED and REFUNDED orders can be paid** [CODE `PaymentModuleConfig`] |
| One payment per order | 🔴 | No uniqueness and no idempotency key. Double-clicking can create two AUTHORIZED payments, and capturing both charges twice [CODE] [DB] |
| Capture checks order state | 🔴 | It doesn't. Capturing after the order is cancelled takes the money, and then `order.confirm()` throws (swallowed) [CODE] |
| Gateway failure | 🟡 | Marked FAILED. **No timeout** on the gateway call |
| UI flow | 🔴 | The UI calls create only, so status stays AUTHORIZED and the order never confirms [CODE `PaymentPage.tsx`] |
| Refund | 🔴 | See security issue S-01 |
| Audit trail | 🟡 | `payment_audit` table exists [DB]. Adapter writes were not fully verified [INF] |

## Post-payment processing (`PaymentSucceededEventHandler`)

| Concern | Status | Notes |
|---|---|---|
| Delivery guarantee | 🔴 | Synchronous `@EventListener` that calls `.subscribe()`. Fire-and-forget: if the process dies, the payment is SUCCESS and the order stays PENDING_PAYMENT forever. No outbox, no retry |
| Partial failure | 🔴 | Inventory and cart errors are `onErrorResume → empty`, so the order is confirmed even when stock deduction failed |
| Postgres transaction poisoning | 🔴 [INF] | A failed statement (for example the cart CHECK violation) aborts the PG transaction, so the subsequent `orderRepository.save` fails and the whole confirmation rolls back |
| Multi-warehouse | 🟡 | `findByProductId(..).next()` picks an arbitrary warehouse row |
| Events published before commit | 🟡 | Published inside the transactional chain. Listeners (shipment) may read uncommitted state |

## Shipment / delivery

| Rule | Status | Notes |
|---|---|---|
| Created only for confirmed orders owned by the user | ✅ | |
| One shipment per order | 🟡 | Check-then-act. **No unique `shipments.order_id`** [DB] |
| Address | 🔴 | The auto-create listener uses `"N/A"` for every field. A manual create takes a free-text address from the client instead of the order snapshot |
| Courier | 🧪 | `MockCourierGateway` only. The CHECK allows `'MOCK'` only |
| Status transitions | ✅ | Domain state machine with a tracking history table |

## Returns & refunds

| Rule | Status |
|---|---|
| Return eligibility, window, product eligibility, quantity, duplicates, partial returns | ⬜ The Returns module is empty |
| Refund validation | 🔴 Payment-level refund only, unrestricted (S-01) |

## Reviews

| Rule | Status | Notes |
|---|---|---|
| Must have purchased and received | ✅ | `userPurchasedProduct` + `isProductDeliveredInOrder` |
| One review per product per user | 🟡 | Check-then-act. **No unique `(product_id, user_id)`** [DB] |
| Moderation before visibility | ✅ | PENDING → APPROVED/REJECTED |
| Helpful vote once per user | ✅ | DB unique `(review_id, user_id)` |
| Public read | 🔴 | Requires authentication. Anonymous product pages cannot show reviews |

## Inventory (admin)

| Rule | Status | Notes |
|---|---|---|
| Non-negative stock | 🟡 | Domain-enforced only. **No DB CHECK** (`available ≥ 0`, `reserved ≥ 0`, `reserved ≤ available`) |
| Reserve / release / stock-out | ✅ domain | The admin API works. Nothing in the purchase flow calls reserve |
| Low and out-of-stock events | ✅ | Emitted. No consumer acts on them other than possibly notifications |
| Events per operation | ⚠ | `stockOut` now emits an extra `InventoryUpdatedEvent`. 3 tests fail on this mismatch |
