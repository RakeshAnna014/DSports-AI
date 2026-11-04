# Databases, migrations and data provenance

One PostgreSQL server (`pgvector/pgvector:pg16-trixie`, container `dsports-postgres`, port 5432) holds separate databases. **No service reads another service's database**: the AI service reaches commerce data only through Java's REST API.

| Database | Owner | Created by | Contents |
|---|---|---|---|
| `dsports` | Java backend | container init, then Flyway at backend start | commerce tables below |
| `dsports_ai` | AI service | `ai-service/db/init-ai-db.sql` (mounted into the container; runs only on a fresh volume, for an existing volume run it by hand as noted in `docker-compose.yml`), then the service's idempotent schema files at start | vectors, conversations, usage, `inv_*`, `eng_*`, `src_*`, `pln_*` |
| `dsports_ai_test`, `dsports_ai_src_test`, `dsports_ai_pln_test` | Python integration tests | created by hand or by `validate-all.sh` | **throwaway**: tests drop and recreate their tables. Never point a `*_TEST_DATABASE_URL` at `dsports_ai` |
| `dsports_e2e_val` | consolidated validation | `validate-all.sh` | disposable copy used for destructive checks |

## 1. Java database `dsports` (Flyway, forward-only, V1 to V47)

Migrations live in each module's `src/main/resources/db/migration` (one shared version sequence; `bootstrap` holds V33 and V43). Flyway runs when the backend starts; there is no down-migration. Never drop `dsports`; use a throwaway database for destructive experiments.

| Module | Tables (migration) | Notes |
|---|---|---|
| identity | `customers`, `customer_roles`, `customer_auth_providers` (V1); `refresh_tokens` (V2); `customer_addresses` (V6); `shopper_profiles`, `shopper_active_profile` (V46) | roles are rows in `customer_roles`; no API assigns them |
| catalog | `sports`, `categories`, `brands` (V7); `products` (V11); `product_images` (V12); `product_attributes` (V39) | seeded V8–V10, V15, V41, V43 (multi-sport demo catalogue) |
| pricing | `prices` (V14) | seeded V17, V42 (INR prices) |
| inventory | `inventory` (V13); `inventory_reservations` (V32) | seeded V16; a reservation per order line |
| cart | `carts`, `cart_items` (V18) | status includes `COMPLETED` (V28) |
| order | `checkouts`, `checkout_items` (V19); `orders`, `order_items` (V21); `promotions`, `promotion_scopes`, `promotion_redemptions` (V47) | integrity constraints V34; order number from a sequence |
| payment | `payments`, `payment_audit` (V22); `refunds` (V35) | `PROCESSING` status V23 |
| shipment | `shipments`, `shipment_tracking` (V27) | constraints V36; one shipment per order |
| returns | `return_requests` (V40) | |
| review | `reviews`, `review_helpful_votes` (V24) | one review per customer and product (V37) |
| wishlist | `wishlists`, `wishlist_items` (V25) | |
| notification | `notifications`, `notification_templates` (V26) | unique per user, type and reference (V38) |
| support | `support_cases`, `support_case_messages`, `support_case_events` (V44, V45) | |
| bootstrap | `outbox_events` (V33) | transactional outbox: `status` PENDING / PROCESSING / DONE / FAILED, `attempts`, `last_error`, unique `(event_type, aggregate_id)`, partial index on due rows |

Relationships in one line: customer → cart → checkout → order (+items) → payment (→ refunds) and → shipment (→ tracking) and → return_requests; order items hold product snapshots (name, sku, price) so history survives catalogue changes; `inventory_reservations` link an order to stock until fulfilment or release.

## 2. AI database `dsports_ai`

Schemas are `CREATE TABLE IF NOT EXISTS` files executed at service start (`app/store/schema.sql`, `schema_rag.sql`, `app/inventory/schema_inventory.sql`, `app/engineering/schema_engineering.sql`, `app/sourcing/schema_sourcing.sql`, `app/planning/schema_planning.sql`, `app/support/schema_support_staff.sql`). Table lists per area are in [services/ai-platform.md](services/ai-platform.md); the `vector` extension is created by `schema_rag.sql`; vectors use `chunks` and `product_index` with HNSW cosine indexes at the configured dimension (`AI_EMBEDDING_DIM`, 768 for `text-embedding-005`). `ai_meta` records the embedding model and dimension; a change forces `reindex_required`.

## 3. Data provenance: what is live, imported, derived or synthetic

| Data | Where | Status |
|---|---|---|
| Catalogue, prices, stock levels | `dsports` | **Demonstration seed data** created by migrations (cricket first; V43 adds other sports). Real in the sense that the application serves it, but it is not a real business's catalogue or stock |
| Customers and orders in the development database | `dsports` | **Test-generated**: they come from the E2E and validation scripts (for example about 1.9 thousand orders over about ten distinct days at the 2026-10-14 audit). They are live records of this system, not real customers' purchases |
| Payments | `dsports` | Created through `MockPaymentGateway`; no real money movement |
| Product index, document chunks | `dsports_ai` | **Derived** (rebuildable) from the catalogue and the sample corpora (`corpus/`, `corpus_inventory/`, `corpus_support/`, all synthetic demonstration documents) |
| Inventory mart: demand history, stock positions, purchase orders, suppliers, events, regions | `dsports_ai.inv_*` | **Synthetic** (`POC_SYNTHETIC` / `POC_REFERENCE`, on every row) |
| Planning: online sales | `pln_sales_*` | **Imported** from Java's sales feed (`DSPORTS_LIVE`), short history |
| Planning: store network, store history, store stock | `pln_*` | **Synthetic** demonstration (`POC_SYNTHETIC`), labelled in the UI |
| Planning: forecasts, variances, recommendations | `pln_forecast*`, `pln_alloc_rec`, `pln_transfer_rec` | **Derived**; accuracy figures measured on synthetic history are not business accuracy |
| Sourcing records | `src_*` | each row has `provenance` (live external source, buyer entered, customer demand signal, demo, test, unknown); only live and buyer-entered rows count by default |
| Engineering logs | `eng_logs` | **Imported** real logs from this project's own services, plus one labelled **synthetic** incident |
| Conversations, pending actions, usage, audit | `dsports_ai` | operational records of the assistant |

Online orders and physical-store sales are different things: only online orders exist as real records; "store" sales in planning are synthetic. A real supplier quotation exists only where a buyer entered or verified it (`quote` provenance); a public listing is not a quotation; an AI estimate is labelled as one.

## 4. Backup, retention and cleanup
No backup, retention or deletion policy is defined (risk R8 in [security/THREAT-MODEL.md](security/THREAT-MODEL.md)). Conversations expire by TTL; audit and usage rows are kept indefinitely. Test and demonstration rows created by scripts are removed with `e2e/ai/planning_data.py --cleanup` and the data scripts' cleanup options; never run those against a database that holds data you want to keep.
