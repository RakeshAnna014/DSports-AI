# 23. Verified walkthroughs and a reading sequence (stabilization edition)

Every step below names the file that does the work. "Verified by" names the executable test or script that exercised the path; a walkthrough is labelled **not run in this pass** if that script was not executed during the stabilization run (see [../STATUS.md](../STATUS.md) for the dated results). Read [../ARCHITECTURE-OVERVIEW.md](../ARCHITECTURE-OVERVIEW.md) first for the diagrams.

## A. A customer places an order (Java only; no AI involved)

1. **Browse**: the browser calls `GET /api/catalog/products` (public, `PublicCatalogController`); price from `GET /api/prices/{productId}`, stock from `GET /api/inventory/{productId}`.
2. **Sign in**: `POST /api/auth/login` (`AuthController` → `LoginUseCase`) returns an access token (15 min) and a refresh token. `RateLimitWebFilter` limits attempts per address. The browser keeps both in `localStorage`; `src/api/client.ts` attaches the access token to every call.
3. **Cart**: `POST /api/v1/cart/items` → `AddToCartUseCase` (checks the product is active and priced). `JwtWebFilter` has already put the user id from the token into the `Authentication`; the controller never accepts a user id from the body.
4. **Checkout**: `POST /api/v1/checkout` (`CreateCheckoutUseCase`), then address, delivery method, optional promotion (`RedeemPromotionUseCase`), then `POST /api/v1/checkout/{id}/validate` (`ValidateCheckoutUseCase`).
5. **Place the order**: `POST /api/v1/orders` → `PlaceOrderUseCase`. In **one transaction**: order and items saved as `PENDING_PAYMENT`, stock reserved in `inventory_reservations`, cart marked checked-out, checkout completed, and an `outbox_events` row for "order placed". A second attempt for the same checkout fails with `DUPLICATE_ORDER`.
6. **Pay**: `POST /api/v1/payments` (`CreatePaymentUseCase`) authorises through `MockPaymentGateway`; the payment must match the order's amount. `POST /api/v1/payments/{id}/capture` (`CapturePaymentUseCase`) claims the payment (`PROCESSING`), calls the gateway outside any transaction with a 10 s timeout, then in a second transaction marks payment `SUCCESS`, order `CONFIRMED`, fulfils the stock, completes the cart and records the notification in the outbox.
7. **After commit**: `OutboxDispatcher` polls every second, runs `OutboxHandlers` (create the shipment, notifications). The customer sees the shipment under `/api/v1/shipments/by-order/{orderId}`; staff advance it with `PUT /api/v1/shipments/admin/{id}/status`.
8. **If the customer never pays**: `ExpireUnpaidOrdersJob` cancels the order (`CancelOrderUseCase.executeAsSystem`), releases the stock and voids the authorisation.
9. **Failure points**: gateway timeout → `GATEWAY_TIMEOUT`, retry the capture; outbox handler failure → row retried up to 8 times, then `FAILED` and visible to an ADMIN.

Verified by: `e2e/api-e2e.sh` (real-stack lifecycle and security checks), `e2e/ui-flow.mjs` (browser purchase), Java integration tests in `order` and `payment`. Trace it in the log with the response's `X-Correlation-Id`.

## B. An assistant tool call is authorised (customer asks "show my orders")

1. Browser → `/ai-api/v1/chat` with the same bearer token (`frontend/src/api/ai.ts`).
2. `app/api/routes.py chat` → `optional_principal` (`app/auth/jwt.py verify_token`: signature, `exp`, `sub`, `iss`, `aud`) → `ChatService` (`app/agent/service.py`).
3. `guard_in` scans the text; `route_node` → `router/tier0.py classify`: the rules recognise `ORDERS_LIST` **without a model call** (`tier 0`, `routeSource: rules`). For a guest the answer is `needs_login`, with no tool call at all (recorded example in [../api/README.md](../api/README.md)).
4. Signed-in: `agent/orders.py orders_list` → `rt.call("get_orders", ...)` → `ToolRegistry.call` (`tools/registry.py`): `_gate` checks the tool exists, authentication, role and the per-request call budget; the arguments are validated by a strict model (an injected `customerId` would be an error); then the handler `tools/account_tools.py get_orders` runs.
5. The handler calls `ctx.dsports.get("/api/v1/orders", token=ctx.token)`, i.e. Java with the **customer's own token**. Java returns only that customer's orders; another customer's order is simply not in the list, and a direct id is rejected by Java's ownership check.
6. The result is trimmed (`ToolResult`), shown as a verified `orders` card with a deterministic text (`templates.orders_text`); `guard_out` checks that every order number in the final text exists in the tool result.
7. An internal tool such as `get_inventory_overview` fails at `_gate` with `FORBIDDEN` for any caller without `ADMIN`, in chat, over REST and over MCP, because all three use this registry.

Verified by: `tests/unit/test_tools.py`, `tests/security/test_tools_and_prompt_injection.py` (every tool against every role), `tests/security/test_route_sweep.py`, `e2e/ai/security_e2e.py` (real tokens, forged tokens, prompt-injection attempts against the live model; counts unchanged), `e2e/ai/ai_scenarios.py` (shopping scenarios; needs the model).

## C. A forecast is generated and a plan is approved (planning)

1. An administrator calls `POST /v1/planning/ingest`: `planning/ingest.py` pages through Java `GET /api/v1/admin/sales/lines` with the admin's token and stores lines with the convention applied once (`conventions.py`: net = gross − cancelled − returned).
2. `POST /v1/planning/demo/seed` (optional) adds the labelled **synthetic** store network and history. Live and synthetic rows carry different `data_source` values and are never summed together unless the scope says so.
3. `POST /v1/planning/forecast/runs` → `forecast_run.py` → `forecast.py`: candidate methods, choice on selection origins by WAPE plus bias, accuracy on a held-out origin, intervals; results stored in `pln_forecast_*`. A failed run leaves the previous run current.
4. A planner creates a plan (`POST /v1/planning/plans`) and edits lines with `PATCH /plans/{id}/lines`; each edit carries the `line_version` it saw, a stale version is refused, a change is written to `pln_audit` in the same transaction. The model forecast is never changed; the planner's number is the **manager forecast**.
5. Submit: `POST /plans/{id}/transition` with target `SUBMITTED`. `workflow.check_transition` enforces role and sequence.
6. Approve: only `ADMIN`, `CATEGORY_MANAGER` or `REGIONAL_MANAGER` may move to `UNDER_REVIEW` / `APPROVED` / `RETURNED` / `REJECTED` / `PUBLISHED`; the submitter cannot approve their own plan unless `PLANNING_ALLOW_SELF_APPROVAL` is set. A regional or store manager can only act on locations in `pln_scope`. Approval snapshots the approved forecast; it **creates no purchase order, moves no stock, publishes nothing and changes no price**.
7. Optional: `POST /plans/{id}/analysis` builds deterministic findings; a model may word a summary, kept only if every number is in the findings. A finding's proposal is applied only when a person adopts it through the normal audited edit.

Verified by: `tests/planning`, `tests/integration/test_planning_db.py` (concurrent approvers, replay, self-approval, scope, frozen plan), `e2e/ai/planning_e2e.py`, `e2e/ai/planning_audit.py`, `e2e/planning-ui.mjs`. Limits: store-level data is demonstration data and live history is short; accuracy shown is not business accuracy.

## D. A supplier offer is evaluated (sourcing)

1. A product enters as a discovered record (a research run through `SafeFetcher`, or a buyer entering it) with its **provenance**; a customer search that found nothing stays a demand gap and cannot go further.
2. A buyer adds a supplier (`POST /v1/sourcing/suppliers`) and records a quotation (`POST /opportunities/{id}/quotes`) with price breaks, MOQ, lead time, currency and source kind. A public listing is not a quotation; verification is separate (`POST /quotes/{id}/verify`, approver role).
3. `POST /opportunities/{id}/calculate` → `calc.py`: purchase cost at the quantity's price tier × the operator-supplied dated exchange rate, freight, duty, non-recoverable tax, handling, defects → landed cost per unit, margin, break-even. Missing input → `INCOMPLETE` naming it; quantity below MOQ → `INVALID`.
4. `POST /opportunities/{id}/compare` ranks comparable offers; `scoring.py` produces the opportunity score, evidence confidence and supplier risk; a high score never hides low confidence or high risk.
5. `POST /opportunities/{id}/status` moves the workflow; approving needs an approver role, an active entered or verified quote, a saved margin calculation and the version the user saw. `POST /opportunities/{id}/proposal` prepares a proposal for the catalogue team: **no listing is published, nothing is ordered, no price is set**.

Verified by: `tests/sourcing`, `tests/security/test_ssrf_and_hostile_sources.py`, `tests/integration/test_sourcing_db.py`, `e2e/ai/sourcing_e2e.py`, `e2e/sourcing-ui.mjs`. Limits: one real research source (no prices); margins exist only after a buyer-entered quotation.

## E. Suggested reading sequence (by what the code actually depends on)

1. [01-business-context](01-business-context.md), then [../ARCHITECTURE-OVERVIEW.md](../ARCHITECTURE-OVERVIEW.md) and [../services/README.md](../services/README.md).
2. **Java foundation**: [../services/java-backend.md](../services/java-backend.md) → read `SecurityConfig`, `JwtWebFilter`, `GlobalExceptionHandler`, then `PlaceOrderUseCase` and `CapturePaymentUseCase`, `OutboxDispatcher`. Run `SecurityPolicyTest` and `api-e2e.sh`.
3. **Data**: [../DATABASE.md](../DATABASE.md) and a skim of the migrations V18-V35.
4. **Frontend**: [../services/frontend.md](../services/frontend.md) → `routes/index.tsx`, `api/client.ts`, one feature folder.
5. **AI platform**: [../services/ai-platform.md](../services/ai-platform.md) → `auth/jwt.py`, `tools/registry.py`, `guard/untrusted.py`, `guard/confirmation.py`, `errors.py`. Read the tool matrix [../api/ai-tools.md](../api/ai-tools.md).
6. **Customer AI** ([customer-ai](../services/customer-ai.md)) → **Support** ([support](../services/support.md)) → **Inventory** ([inventory-ai](../services/inventory-ai.md)) → **Engineering** ([engineering-ai](../services/engineering-ai.md)) → **Sourcing** ([sourcing](../services/sourcing.md)) → **Planning** ([planning](../services/planning.md)). Each depends on the platform and the next builds on earlier rules (planning reuses inventory rules; sourcing reads customer demand).
7. **Security last, with the code in mind**: [../SECURITY.md](../SECURITY.md), [../security/THREAT-MODEL.md](../security/THREAT-MODEL.md), then the `tests/security/` folder and `security_e2e.py`.
8. **Learn the concepts when you meet them**: chapters 05-08 (Python service, prompting, RAG, tools/MCP) of this guide, in that order.
