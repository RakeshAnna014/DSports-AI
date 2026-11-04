# 02 — Phase 1 Backend AI Readiness

What the Java backend gained in Phase A so a customer assistant can answer from real data. Everything here is
ordinary DSports API surface: it is useful without any AI, documented in OpenAPI (`/api-docs`), and covered by tests.

| # | Capability | Endpoint | Access |
|---|---|---|---|
| 1 | Catalog keyword search | `GET /api/catalog/products?q=` | public |
| 2 | Product attributes | `GET /api/catalog/products/{id}/attributes`, `PUT /api/admin/catalog/products/{id}/attributes` | public read, admin write |
| 3 | Batch price | `GET /api/prices?productIds=a,b,c` | public |
| 4 | Batch inventory | `GET /api/inventory?productIds=a,b,c` | public |
| 5 | Shipment by order | `GET /api/v1/shipments/by-order/{orderId}` | owner or admin |
| 6 | Returns | see below | owner / admin |
| 7 | Review DTO fix | `GET /api/v1/reviews/product/{id}` | public |

## 1. Catalog `q` search
`q` is trimmed, capped at 100 characters and split on whitespace into at most 6 terms. Every term must match
(`AND`) at least one of name, description or SKU (`ILIKE`, with `\`, `%` and `_` escaped so user input cannot act as a
pattern). It combines with the existing brand/category/sport filters and paging. `V39` adds `pg_trgm` GIN indexes on
name and description. Only ACTIVE products are returned, as before.

## 2. Product attributes
Table `product_attributes(product_id → products ON DELETE CASCADE, attr_key, attr_value)`. Keys match
`[a-zA-Z][a-zA-Z0-9_]{0,49}`, values are 1–200 characters, at most 30 per product. `PUT` replaces the full set (admin
only). These are structured facts (skill level, weight class, material …) the assistant can compare without
inferring them from prose.

## 3–4. Batch price and inventory
`productIds` accepts up to 50 IDs (duplicates removed); more is a `400 VALIDATION_ERROR`. Price keeps the
published/ACTIVE filter. Inventory returns `{productId, availableQuantity, status}` where `availableQuantity` is the
**sellable** quantity (on hand minus reserved) — internal fields (warehouse, reserved, reorder level) are not exposed.
The single-product forms are unchanged. These exist so a result list needs 2 calls, not 2×N.

## 5. Shipment by order
`GET /api/v1/shipments/by-order/{orderId}` returns the shipment and its tracking events. Only the order's owner or an
admin may read it; another customer gets `403`, an order without a shipment yet gets `404`.

## 6. Returns module (`backend/returns`)
New bounded context following the same layout as `payment`. **It does not move money**: refund payout remains the
existing admin refund flow.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/orders/{orderId}/returns/eligibility?productId=` | authoritative policy answer |
| `POST /api/v1/returns` (header `Idempotency-Key`, required) | create a return request |
| `GET /api/v1/returns`, `GET /api/v1/returns/{id}` | my returns (owner only) |
| `GET /api/v1/admin/returns`, `POST /api/v1/admin/returns/{id}/approve`, `…/reject` | admin decision |

**Policy** (`ReturnPolicy`): the order must be `DELIVERED`, the product must be in the order, `now` must be within
`app.returns.window` (ISO-8601 duration, **default `P7D`**), and the quantity must not exceed ordered minus units
already held by `REQUESTED`/`APPROVED` returns (a `REJECTED` return frees its quantity). Partial quantities are
allowed. The eligibility response carries `eligible`, `reason` (`ELIGIBLE`, `ORDER_NOT_FOUND`, `ORDER_NOT_DELIVERED`,
`PRODUCT_NOT_IN_ORDER`, `RETURN_WINDOW_EXPIRED`, `ALREADY_RETURNED`), the remaining `maxReturnableQuantity` and the
`returnDeadline`. The creation endpoint re-runs the policy inside the transaction; the client's view is never trusted.

**Lifecycle:** `REQUESTED → APPROVED | REJECTED` (terminal). Approving or rejecting twice, or rejecting an approved
return, is `409`.

**Security:** an order that is not yours is indistinguishable from one that does not exist (`ORDER_NOT_FOUND` /
`NOT_ELIGIBLE`), so order IDs cannot be probed; reading another customer's return is `403`; approve/reject/list-all
need `ROLE_ADMIN`.

**Idempotency and concurrency:** `(user_id, idempotency_key)` is unique in the database. A retry returns the original
request; the same key with a different order/product/quantity is `409 IDEMPOTENCY_KEY_REUSED`. Creation takes a
transaction-scoped Postgres advisory lock per `(order, product)` line, so concurrent requests cannot exceed the
returnable quantity. `version` provides optimistic locking for decisions.

> **Delivery timestamp — current behaviour.** The `orders` table has no `delivered_at` column. The return window
> therefore starts from the **last update time (`updated_at`) of the order once it is `DELIVERED`**. A `DELIVERED`
> order can only move on to `REFUNDED`, so this is the delivery time in practice, but it is **not a dedicated
> delivery timestamp**: any later update of the order row would move the window. Adding `orders.delivered_at`
> (set in `Order.deliver()`) is the proper fix and is deferred as out of Phase A scope.

## 7. Review DTO security fix
The public review DTO exposed the reviewer's `userId`. `ReviewResult` (and the frontend type) no longer carries it.

## Migrations added

| Version | Content |
|---|---|
| V39 | `product_attributes`, `pg_trgm`, trigram indexes |
| V40 | `return_requests` (unique `(user_id, idempotency_key)`, CHECK on quantity and status) |
| V41 | **SAMPLE DATA** — 180 attributes and richer descriptions for the 50 seed products |
| V42 | **SAMPLE DATA** — INR MRP/selling prices for the seed products (they were stored as USD amounts) |

## Verification evidence (Phase A)
See [03-infrastructure.md](03-infrastructure.md#verification-record) for the migration, test and E2E results.
