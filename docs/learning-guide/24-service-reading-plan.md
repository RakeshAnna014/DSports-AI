# 24. Service-by-service reading plan

Companion to [23-stabilization-walkthroughs.md](23-stabilization-walkthroughs.md) (which traces four workflows step by step). This plan says, for each service, **what to read first, one request to trace, the tables involved and the tests that prove the behaviour**. All paths are real as of 2026-10-16. It teaches the system as built; it does not introduce new concepts or features. Study one service at a time, in this order, because each depends on the ones before it.

How to study each service (about half a day each): read the guide in [../services/](../services/README.md) → read the "first files" in the order listed → run the proving tests → trace the request once against the running stack by its `X-Correlation-Id` → write down, from memory, what the service owns and must not own.

## 1. Java backend, security and identity (start here)
* **Why first:** every other service trusts its tokens, ownership checks and data.
* **First files:** `backend/bootstrap/.../security/SecurityConfig.java` → `JwtWebFilter.java` → `backend/identity/.../infrastructure/security/JwtTokenProvider.java` → `bootstrap/.../exception/GlobalExceptionHandler.java` → `AuthController` → `LoginUseCase`, `RefreshTokenUseCase`.
* **Trace:** `POST /api/auth/login` then `GET /api/v1/orders` with and without the token (401 envelope in [../api/README.md](../api/README.md)).
* **Tables:** `customers`, `customer_roles`, `refresh_tokens`, `customer_addresses`.
* **Proving tests:** `SecurityPolicyTest` (33 path and token cases), `JwtTokenProviderTest`, `LoginUseCaseTest`, `RefreshTokenUseCaseTest`, `RateLimitWebFilterTest`, `GlobalExceptionHandlerTest`; real stack: `e2e/api-e2e.sh`, `e2e/ai/security_e2e.py`.

## 2. Java commerce: cart, checkout, order, payment (the transaction core)
* **First files:** `order/.../PlaceOrderUseCase.java` → `OrderStatus.java` → `payment/.../CreatePaymentUseCase.java` → `CapturePaymentUseCase.java` → `MockPaymentGateway.java` → `bootstrap/.../outbox/OutboxDispatcher.java`, `OutboxHandlers.java` → `ExpireUnpaidOrdersJob.java`.
* **Trace:** cart item → checkout → order → payment → capture ([walkthrough A](23-stabilization-walkthroughs.md)); watch `outbox_events` fill and drain.
* **Tables:** `carts`, `cart_items`, `checkouts`, `checkout_items`, `orders`, `order_items`, `inventory`, `inventory_reservations`, `payments`, `payment_audit`, `refunds`, `outbox_events`.
* **Proving tests:** `PlaceOrderUseCaseTest`, `OrderStateMachineTest`, `OrderOptimisticLockingIntegrationTest`, `CapturePaymentUseCaseTest`, `CreatePaymentUseCaseTest`, `RefundUseCasesTest`, `MockPaymentGatewayTest`, `StockReservationConcurrencyIntegrationTest`; real stack: `api-e2e.sh` (order, payment, refund, idempotency, cross-customer access), `e2e/ui-flow.mjs`.

## 3. Java fulfilment and after-sales: shipment, returns, notifications, support cases
* **First files:** `shipment/.../UpdateShipmentStatusUseCase.java`, `ShipmentStatus.java` → `returns/.../ReturnPolicy` and `CreateReturnUseCase` → `notification/.../CreateNotificationUseCase` → `support/.../CaseStatus.java`, `SupportCase`, `PriorityPolicy`.
* **Trace:** delivered order → return eligibility → return request → admin approval; and a support case from creation to resolution.
* **Tables:** `shipments`, `shipment_tracking`, `return_requests`, `notifications`, `support_cases`, `support_case_messages`, `support_case_events`.
* **Proving tests:** `ShipmentTest`, `UpdateShipmentStatusUseCaseTest`, `ReturnPolicyTest`, `ReturnsIntegrationTest`, `ApprovedReturnsReaderIntegrationTest`, `NotificationTest`, `SupportCaseTest`, `PriorityPolicyTest`, `SupportCaseIntegrationTest`; real stack: `api-e2e.sh`, `e2e/ai/support_staff_e2e.py`, `e2e/support-ui.mjs`.

## 4. Catalogue, price and stock (the data the AI reads)
* **First files:** `catalog/.../PublicCatalogController.java`, `Product` domain model, `pricing/.../PublicPriceController.java` and `Price` lifecycle use cases, `inventory/.../PublicInventoryController.java`.
* **Trace:** `GET /api/catalog/products` → price → stock for one product.
* **Tables:** `sports`, `categories`, `brands`, `products`, `product_images`, `product_attributes`, `prices`, `inventory`.
* **Proving tests:** `PublicCatalogControllerTest`, `ProductTest`, `PriceTest`, `ActivatePriceUseCaseTest`, `PriceMigrationTest`, `InventoryItemTest`; `frontend` script `pnpm validate:catalog:live`.

## 5. Frontend
* **First files:** `frontend/src/routes/index.tsx` → `src/api/client.ts` → `src/store/authStore.ts` → one feature folder (`features/orders`, then `features/ai`).
* **Trace:** log in in the browser and watch the Network tab: Authorization header, the 401 → refresh → replay path.
* **Tables:** none (browser `localStorage` holds tokens).
* **Proving tests:** `safeUrl.test.ts`, `features/*/*.test.tsx` (198 tests in 17 files), browser journeys `e2e/ui-flow.mjs`, `ai-commerce.mjs`.

## 6. AI platform
* **First files:** `ai-service/app/main.py` → `app/auth/jwt.py` → `app/tools/registry.py` → `app/guard/untrusted.py` → `app/guard/confirmation.py` → `app/errors.py` → `app/llm/resilient.py` → `app/config/settings.py`.
* **Trace:** `GET /v1/me` (valid, forged, expired token), then `GET /health/dependencies` as anonymous and as admin ([walkthrough B](23-stabilization-walkthroughs.md)).
* **Tables:** `ai_meta`, `usage`, `audit`, `conversations`, `pending_actions`.
* **Proving tests:** `tests/unit/test_auth.py`, `test_tools.py`, `test_guards.py`, `test_llm_layer.py`, `test_app.py`, `tests/security/test_route_sweep.py`, `test_tools_and_prompt_injection.py`, `test_ops_and_config.py`; generated matrices: [../api/ai-tools.md](../api/ai-tools.md), [../security/AUTHORIZATION-MATRIX.md](../security/AUTHORIZATION-MATRIX.md).

## 7. Customer AI (assistant, RAG, recommendations, MCP)
* **First files:** `app/agent/service.py` → `agent/graph.py` → `router/tier0.py` → `agent/orders.py` → `tools/account_tools.py` → `shop/ranking.py` → `reco/engine.py` → `rag/retrieval.py` → `mcp/server.py`.
* **Trace:** guest "show my orders" (no model call), then signed-in "show my orders", then "lightweight cricket bats under 8000".
* **Tables:** `product_index`, `documents`, `chunks`, `conversations`, `pending_actions`.
* **Proving tests:** `test_router.py`, `test_agent.py`, `test_api_chat.py`, `test_ranking.py`, `test_reco_engine.py`, `test_mcp.py`, `tests/integration/test_rag_db.py`; live: `py-live` (63), `e2e/ai/ai_scenarios.py` (64 checks), `eval/run_eval.py`.

## 8. Support assistance
* **First files:** `app/support/classify.py` → `investigate.py` → `resolve.py` → `flow.py` → `tools/support_tools.py` → `support/staff_assist.py`.
* **Trace:** "my order has not arrived" → investigation → proposal → confirm → case created in Java.
* **Tables:** Java `support_cases` family; AI `support_ai_actions`.
* **Proving tests:** `tests/support/*` (194), `tests/integration/test_staff_assist_db.py`, `test_support_documents_db.py`; live: `tests/live/test_stack_support.py`, `e2e/ai/case_chat_and_incidents_e2e.py`, `e2e/support-ui.mjs`.

## 9. Inventory Intelligence
* **First files:** `app/inventory/pipeline.py` → `forecasting.py` → `planning.py` (stock risk, replenishment) → `understand.py` → `tools.py` → `grounding.py`.
* **Trace:** "which products will stock out in 14 days?" and open the evidence.
* **Tables:** `inv_sku`, `inv_demand_daily`, `inv_forecast`, `inv_model_run`, `inv_assessment`, `inv_position`, `inv_audit`.
* **Proving tests:** `tests/inventory/*` (491 with related unit tests), `tests/unit/test_inventory_authz_matrix.py`, `tests/integration/test_inventory_db.py`; `eval/run_inventory_eval.py`; `e2e/inventory-ui.mjs`.

## 10. Engineering AI
* **First files:** `app/engineering/ingest.py`, `parsers.py` → `redact.py` → `signature.py` → `understand.py` → `investigate.py` → `safe.py`.
* **Trace:** `cli synthetic`, then ask "Why did checkout fail?".
* **Tables:** `eng_logs`, `eng_deployments`, `eng_incident_status`, `eng_audit`.
* **Proving tests:** `tests/engineering/*` (66), `tests/integration/test_engineering_db.py`, `eval/engineering/run_eval.py`, `e2e/ai/engineering_e2e.py` (24).

## 11. Product & Supplier Intelligence (sourcing)
* **First files:** `app/sourcing/fetch.py` → `normalize.py` → `calc.py` → `scoring.py` → `workflow.py` → `repository.py`.
* **Trace:** enter a quote → calculate landed cost → compare → try to approve with and without the approver role.
* **Tables:** `src_opportunities`, `src_suppliers`, `src_quotes`, `src_evidence`, `src_scores`, `src_calcs`, `src_decisions`, `src_proposals`, `src_runs`.
* **Proving tests:** `tests/sourcing/*` (136), `tests/security/test_ssrf_and_hostile_sources.py`, `tests/integration/test_sourcing_db.py`, `e2e/ai/sourcing_e2e.py`, `sourcing_live_e2e.py`, `e2e/sourcing-ui.mjs`.

## 12. Sales & Inventory Planning (last: it reuses the others)
* **First files:** `app/planning/conventions.py` → `ingest.py` → `forecast.py` → `forecast_run.py` → `workflow.py` → `repo_plans.py` → `insights.py` → `service_insights.py`. Java side: `order/.../GetSalesFeedUseCase`, `AdminSalesFeedController`.
* **Trace:** ingest → forecast run → plan → edit a line (stale-version conflict) → submit → approve ([walkthrough C](23-stabilization-walkthroughs.md)).
* **Tables:** `pln_sales_line`, `pln_sales_week`, `pln_forecast_run`, `pln_forecast`, `pln_plan`, `pln_plan_line`, `pln_actual_adjustment`, `pln_audit`, `pln_scope`.
* **Proving tests:** `tests/planning/*` (103), `tests/integration/test_planning_db.py` (57), `e2e/ai/planning_e2e.py`, `planning_audit.py`, `e2e/planning-ui.mjs`. Note the fixture interaction found on 2026-10-16: `planning_e2e.py` leaves an approved correction if it stops early, which changes the actual the browser journey expects ([VALIDATION-MATRIX.md](../VALIDATION-MATRIX.md)).

## 13. Finish with security
Read [../SECURITY.md](../SECURITY.md), [../security/THREAT-MODEL.md](../security/THREAT-MODEL.md) and [../security/RISK-REGISTER.md](../security/RISK-REGISTER.md), then re-read `SecurityConfig` and `registry.py` with the threats in mind.

## The first service to study
**Service 1: the Java backend's security and identity layer, followed immediately by the order and payment transaction core.** They define who a caller is, what the databases hold and how money-bearing state changes safely; everything in the AI service is a reader or a careful requester of those facts.
