# 07 — Tools and MCP

One typed registry (`ai-service/app/tools/`) is the single source of truth for what the assistant can do. The LangGraph agent calls it
**in-process** (no network hop); the same objects are exposed to external clients through an **MCP server** (official `mcp` SDK,
streamable HTTP) mounted at `/mcp/`.

## Tools

| Tool | Kind | DSports APIs used | Notes |
|---|---|---|---|
| `search_products` | READ (guests ok) | catalog index (AI DB) + `GET /api/prices?productIds`, `/api/inventory?productIds`, product detail/attributes, review summary | live price/stock, deterministic ranking |
| `get_product` | READ (guests ok) | product detail + attributes + price + stock + review summary | |
| `get_product_reviews` | READ (guests ok) | `/api/v1/reviews/product/{id}[/summary]` | approved only; reviewer identity never copied; text sanitised |
| `get_product_knowledge` | READ (guests ok) | AI DB hybrid retrieval | cited, sanitised passages |
| `get_customer_profile` | READ | `/api/customers/me` | **first name only** |
| `get_cart` | READ | `/api/v1/cart` | |
| `get_orders` | READ | `/api/v1/orders` (+ detail for item names) | period/status filter client-side; whitelisted fields |
| `get_order` | READ | `/api/v1/orders/{id}` | by id, number, last digits, or `latest`; no address |
| `track_order` | READ | `/api/v1/shipments/by-order/{id}` | status, tracking number, courier, last events |
| `get_payment_status` | READ | `/api/v1/payments/history` | |
| `get_refund_status` | READ | `/api/v1/payments/{id}`, `/refunds` | free text sanitised |
| `check_return_eligibility` | READ | `/api/v1/orders/{id}/returns/eligibility` | the verdict is DSports' |
| `create_return` | **WRITE** | `POST /api/v1/returns` + `Idempotency-Key` | confirmation required |
| `reorder_product` | **WRITE** | live product/price/stock, `POST /api/v1/cart/items` | confirmation required; never places an order or payment |

`create_support_case` is not built: DSports has no support-case API and none was faked.

## Safety enforced in the registry (not in prompts)

* Arguments are strict pydantic models with `extra = forbid`: a `customerId`/`userId` argument is rejected (tested for every tool).
  Identity is the **bearer token** forwarded to DSports, so the Java backend's ownership checks are the authority.
* Account tools need a verified customer; public tools work for guests.
* A per-request tool-call budget (`AI_MAX_TOOL_CALLS`) stops loops; each call has a timeout.
* A WRITE tool cannot run through `registry.call` unless the confirmation service authorised that exact call
  (`CONFIRMATION_REQUIRED` otherwise). `prepare()` computes the proposal from live data and is re-run at confirmation time.
* Every result is a whitelist built field by field (no addresses, phone numbers, e-mail, user ids, payment instrument details).
* Every call is timed (`ai_tool_latency_seconds{tool,status}`) and traced in the response `meta.tools`.

## MCP server (`app/mcp/server.py`)

* All READ tools are exposed with the registry's real JSON Schemas (generated from the pydantic models: types, bounds, descriptions).
* WRITE tools are **not** directly callable. Clients use `propose_action(tool, arguments)` → summary + single-use token, then
  `confirm_action(confirmation_token)`: the same confirmation service, revalidation, idempotency key and audit as the web UI.
* Authentication: the customer's bearer token on each HTTP request, verified like the REST API's and forwarded to DSports.
  Without a token only public tools work. A stray `customerId` argument is ignored by the MCP layer (identity never comes from it).
* Transport security: Host header allow-list `AI_MCP_ALLOWED_HOSTS`; disable with `AI_MCP_ENABLED=false`.

### Tested

* `tests/unit/test_mcp.py` — in-process MCP client: tool list and schemas, no direct WRITE tools, guests refused on account tools,
  schema-level argument validation.
* `tests/live/test_stack_mcp.py` — **real MCP client over HTTP → MCP server → registry → real DSports REST API**: authenticated
  calls return that customer's data, another customer's token gets nothing (`403`/not found from DSports), `customerId` arguments
  change nothing, anonymous callers get public tools only, and `propose_action` → (another customer's `confirm_action` refused) →
  `confirm_action` → real return created → replay refused.
