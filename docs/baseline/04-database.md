# 04 — Database Validation

Evidence tags: **[RUN]** · **[CODE]** · **[DB]** · **[INF]**. See [README](README.md).

Database: PostgreSQL 16 (docker `dsports-postgres`). Access is R2DBC at runtime and JDBC for Flyway. Each module ships its own migrations under `src/main/resources/db/migration`, and all of them are merged on the classpath into a single version sequence.

> **Changes since the audit (2026-10-06):**
> - Notification and shipment migrations renumbered to **V26/V27**.
> - New migrations:
>   - **V28:** carts CHECK allows `COMPLETED` (D-01 resolved).
>   - **V29–V31:** seeded rows marked `version = 1`, and the column default changed to 1. Spring Data treated version-0 rows as new, so seed data could never be updated.
> - The sequence is now V1–V31 with no gaps or duplicates, guarded by `FlywayMigrationVersionTest`.
> - Verified on a clean DB and on a copy of the dev DB (V22 → V31). See [09](09-remediation-log.md).

## 1. Migration inventory and drift

| Version | Script | Module | Applied in dev DB? |
|---|---|---|---|
| V1–V6 | identity tables, refresh tokens, session metadata, profile, addresses | identity | ✅ |
| V7–V12, V15 | catalog tables + seeds, products, images, product seed | catalog | ✅ |
| V13, V16 | inventory + seed | inventory | ✅ |
| V14, V17 | prices + seed | pricing | ✅ |
| V18 | carts | cart | ✅ |
| **V19** | `create_checkout_tables` | order | ✅ |
| **V19** | `create_notification_tables` | notification | ❌ **duplicate version** |
| **V20** | `add_discount_to_checkouts` | order | ✅ |
| **V20** | `create_shipment_tables` | shipment | ❌ **duplicate version** |
| V21 | orders | order | ✅ |
| V22 | payments, payment_audit | payment | ✅ |
| V23 | add `PROCESSING` payment status | payment | ❌ not applied |
| V24 | reviews | review | ❌ not applied |
| V25 | wishlists | wishlist | ❌ not applied |

- **[RUN]** Flyway against an empty DB fails with: `Found more than one migration with version 19`, so the current working tree cannot migrate.
- **[DB]** The dev DB is at V22. Because nothing past V22 has been applied, the duplicates can be fixed by **renumbering the notification and shipment scripts to V26 and V27**. No checksum repair is needed.
- **Risk:** the per-module numbering scheme has no ownership guard. Recommended fix: either reserve version ranges per module (`V1xx` identity, `V2xx` catalog, …) or add a CI check that fails on duplicate versions.
- `application-local.yml` sets `validate-on-migrate: false`, which hides checksum drift locally. Recommendation: remove it.
- Stray file `backend/bootstrap/src/main/java/com/dsports/config/Untitled-1.pgsql` (`SELECT * FROM flyway_schema_history`) sits in the Java source tree. Delete it.
- The `database/migrations` and `database/seeds` folders at the repo root are empty, and the docs still reference them.

## 2. Entity → table → relationships

| Aggregate (module) | Tables | FKs [DB] | Notable unique / partial indexes |
|---|---|---|---|
| User (identity) | customers, customer_roles, customer_auth_providers, refresh_tokens, customer_addresses | all → customers | `uq_customers_email`, `uq_refresh_tokens_token` |
| Sport / Category / Brand (catalog) | sports, categories, brands | — | name + slug unique on each |
| Product (catalog) | products, product_images | product_images → products | `uq_products_sku`, `uq_products_slug` |
| InventoryItem (inventory) | inventory | — | `uq_inventory_product_warehouse` |
| Price (pricing) | prices | — | `uq_price_product_currency_active` (partial, ACTIVE) |
| Cart (cart) | carts, cart_items | cart_items → carts (cascade) | `uq_carts_user_active` (partial), `uq_cart_items_cart_product` |
| Checkout (order) | checkouts, checkout_items | checkout_items → checkouts | **none on `customer_id`+active** |
| Order (order) | orders, order_items | order_items → orders | `uq_orders_order_number` only |
| Payment (payment) | payments, payment_audit | payment_audit → payments | `idx_payments_payment_reference` (unique) |
| Review (review) | reviews, review_helpful_votes | votes → reviews | votes `(review_id, user_id)` |
| Shipment (shipment) | shipments, shipment_tracking | tracking → shipments | tracking_number (partial unique) |
| Notification (notification) | notifications, notification_templates | — | template `(name, channel)` |
| Wishlist (wishlist) | wishlists, wishlist_items | items → wishlists (cascade) | `user_id` unique, `(wishlist_id, product_id)` |

Cross-module references (for example `order_items.product_id`, `cart_items.product_id`, `payments.order_id`) intentionally have **no FKs**. This is consistent with the modular-monolith rule that one module does not reach into another's tables, and it is acceptable. **Same-module references should have FKs, and some don't:** `products.brand_id/category_id/sport_id` have no FK to the catalog tables in the same module.

## 3. Constraint gaps

| ID | Table | Missing constraint | Why it matters | Evidence |
|---|---|---|---|---|
| D-01 | carts | CHECK lacks `'COMPLETED'` (domain enum has it) | Post-payment cart completion fails | [DB] |
| D-02 | orders | `UNIQUE (checkout_id)` | Duplicate orders under concurrency | [DB] |
| D-03 | payments | Partial unique: one non-terminal payment per order (for example `UNIQUE(order_id) WHERE status IN ('CREATED','PENDING','PROCESSING','AUTHORIZED','SUCCESS')`) | Double charge | [DB] |
| D-04 | checkouts | Partial unique on `customer_id` WHERE status is active | Concurrent checkout creation | [DB] |
| D-05 | shipments | `UNIQUE (order_id)`, and `shipment_number` is indexed but not unique | Duplicate shipments | [CODE] V20 |
| D-06 | reviews | `UNIQUE (product_id, user_id)` | Duplicate reviews | [CODE] V24 |
| D-07 | inventory | `CHECK (available_quantity >= 0)`, `CHECK (reserved_quantity >= 0)`, `CHECK (reserved_quantity <= available_quantity)`, status CHECK | Negative stock | [DB] |
| D-08 | products | FKs to brands, categories, and sports. Status CHECK | Orphans and invalid states | [DB] |
| D-09 | order_items / checkout_items | `CHECK (unit_price >= 0)`, `line_total >= 0` | Integrity | [DB] |
| D-10 | orders, payments | `currency` format CHECK (prices has one) | Consistency | [DB] |

## 4. Type consistency [DB]

| Concern | Finding | Recommendation |
|---|---|---|
| Timestamps | identity, catalog, inventory, pricing, and cart use `TIMESTAMP` (no time zone). checkout, order, and payment use `TIMESTAMPTZ`. The app forces JVM UTC, and Flyway's JDBC URL sets `TimeZone=UTC` | Standardise on `TIMESTAMPTZ`. Running locally on an IST machine already broke a raw JDBC connection during this audit ("invalid TimeZone Asia/Calcutta") |
| Money | cart, checkout, and prices use `NUMERIC(10,2)` (max about ₹9.99 crore). orders and payments use `NUMERIC(12,2)` | Standardise on `NUMERIC(12,2)` (or `(19,4)` if multi-currency or tax precision is planned) |
| Status columns | `VARCHAR(20)` + CHECK (good) on most tables. Inventory and products have no CHECK | Add CHECKs |

## 5. Query and index review

| ID | Finding | Evidence |
|---|---|---|
| Q-01 | Product list loads images in one `IN (:productIds)` query, so there is **no N+1**. Good | [CODE] |
| Q-02 | Product list uses OFFSET pagination, which is fine at the current scale. Keyset or a search index will be needed for AI search later | [CODE] |
| Q-03 | `carts` save deletes and re-inserts every `cart_items` row on each mutation. Acceptable at ≤50 items, but it creates write churn and prevents per-row auditing | [CODE] |
| Q-04 | `ix_cart_items_cart_id` is redundant (covered by the leading column of `uq_cart_items_cart_product`). `idx_orders_checkout_id` becomes redundant once D-02's unique index exists | [DB] |
| Q-05 | `GET /api/inventory`, `GET /api/prices`, admin list endpoints, order history, and payment history are unbounded scans with no LIMIT | [CODE] |
| Q-06 | Order number from `SELECT count(*) FROM orders` is a full scan on every order and a race. Use a DB sequence | [CODE] |
| Q-07 | Cross-module "ports" are wired by calling another module's *use cases* in bootstrap configs (for example cart's `inventoryPort` calls `GetInventoryByProductUseCase`). This is consistent with the architecture, but each cart add triggers 3 separate round trips (product, inventory, price). Acceptable at the current scale | [CODE] |
