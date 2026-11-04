# 17. Source code index and file-by-file map

Columns: **Path**, **Purpose**, **Important symbols**, **Called by -> calls**, **Tests**, **Explained in**. Files are listed in learning-priority order. Paths are relative to the repository root. Line counts at time of writing. Generated/vendor files, `node_modules`, `__pycache__` and build output are excluded.

## A. Python AI service: runtime spine (read these first)

| Path | Purpose | Important symbols | Called by -> calls | Tests | Doc |
|---|---|---|---|---|---|
| `ai-service/app/main.py` (81) | app factory, lifespan, MCP mount | `create_app`, `lifespan`, `mcp_mount` | uvicorn -> `build_services`, `ChatService`, `InventoryCopilot` | `tests/unit/test_app.py` | 05 |
| `ai-service/app/services.py` (97) | composition root | `Services`, `build_services` | `main`, CLIs -> every collaborator | `test_app.py` | 05 |
| `ai-service/app/api/routes.py` (90) | customer REST routes, health, metrics | `chat`, `confirm`, `recommendations`, `dependencies` | FastAPI -> `ChatService` | `test_api_chat.py` (14) | 05 |
| `ai-service/app/config/settings.py` (151) | environment configuration | `Settings`, `get_settings`, `public_view` | everything -> env | `test_config.py` (6) | 05 |
| `ai-service/app/auth/jwt.py` (78) | JWT verification, principal, customer hash | `verify_token`, `current_principal`, `optional_principal`, `customer_hash`, `Principal` | routes, MCP -> PyJWT | `test_auth.py` (13) | 05, 13 |
| `ai-service/app/errors.py` (51) | error contract | `ApiException`, `error_body`, `install_error_handlers` | all routes | `test_app.py` | 02 |
| `ai-service/app/agent/graph.py` (263) | the Customer Agent LangGraph | `build_customer_agent`, `guard_in`, `route_node`, `dispatch`, `guard_out`, `shopping_graph`, `orders_graph`, `support_graph` | `ChatService` -> `tier0`, `tier1`, nodes, guards | `test_agent.py` (63) | 05 |
| `ai-service/app/agent/runtime.py` (98) | per-request runtime, `Turn` accumulator, LLM/tool gateway | `Runtime.ask`, `Runtime.call`, `Turn`, `BudgetExceeded` | nodes -> `ResilientLLM`, `ToolRegistry` | `test_agent.py` | 05 |
| `ai-service/app/agent/service.py` (205) | one turn: rate limit, conversation, graph, response | `ChatService.chat/confirm/recommend/_finish`, `RateLimiter`, `make_summary` | routes -> graph, state store, executor | `test_agent.py`, `test_api_chat.py` | 05 |
| `ai-service/app/agent/schemas.py` (94) | structured API models | `ChatRequest`, `ChatResponse`, `Meta`, `ActionPrompt` | routes, service | `test_structured_responses.py` (22) | 05 |
| `ai-service/app/agent/shopping.py` (259) | search/compare/knowledge nodes | `search`, `explain_search`, `_llm_text`, `compare`, `knowledge`, `merge_search_slots` | graph -> tools, `Runtime.ask`, prompts | `test_agent.py` | 05, 07, 09 |
| `ai-service/app/agent/orders.py` (271) | orders/returns/reorder + confirmation by chat | `orders_list`, `track`, `return_check`, `_propose`, `return_create`, `reorder`, `confirm_yes`, `apply_outcome` | graph -> tools, `ConfirmationService` | `test_agent.py`, `test_tools.py` | 08, 09 |
| `ai-service/app/agent/prompts.py` (61) | system prompt, data blocks, task prompts | `SYSTEM`, `RULES_ID`, `data_block`, `explain_search`, `advise`, `answer_from_sources` | shopping -> `fence` | `test_agent.py` | 06 |
| `ai-service/app/agent/templates.py` (154) | deterministic customer texts | `products_text`, `tracking_text`, `eligibility_text`, ... | nodes | `test_structured_responses.py` | 05 |
| `ai-service/app/agent/recommend.py` (226), `product_context.py` (105), `offers.py` (28), `suggestions.py` (41), `response_kind.py` (42), `support.py` (36) | recommendations/kits, product context, server-owned choices, chips, response type, policy/refund-why nodes (`support.support_case` is stale dead code) | `recommend`, `select_product`, `add_offer`/`resolve`, `build_suggestions`, `response_type`, `support_policy`, `refund_why` | graph | `test_product_context.py` (9), `test_structured_responses.py` | 05, 09 |

## B. Python: routing, LLM, tools, guards

| Path | Purpose | Important symbols | Tests | Doc |
|---|---|---|---|---|
| `ai-service/app/router/tier0.py` (307) | deterministic routing rules | `classify`, `_support_route`, many regexes | `test_router.py` (23) | 06, 09, 11 |
| `ai-service/app/router/tier1.py` (141) | cheap-model classifier + verification | `Tier1.classify`, `verify`, `SYSTEM`, `Tier1Output` | `test_agent.py` (tier 1 cases) | 06 |
| `ai-service/app/router/intents.py` (56), `extract.py` (124), `periods.py` (51) | intent enum/sets, slot extraction, date periods | `Intent`, `Route`, `extract_shopping_slots`, `parse_period` | `test_router.py` | 09 |
| `ai-service/app/llm/base.py` (31), `vertex.py` (104), `resilient.py` (79), `smoke.py` (37) | provider protocol, Vertex client, retry/breaker, live check | `LLMClient`, `VertexGemini.generate/embed`, `ResilientLLM`, `CircuitBreaker`, `_classify` | `test_llm_layer.py` (3), `tests/live/test_vertex_live.py` (3 test functions, 4 cases) | 06 |
| `ai-service/app/tools/registry.py` (161) | the typed tool registry | `Tool`, `StrictArgs`, `ToolRegistry.call/prepare/_gate`, `Proposal`, `ToolContext` | `test_tools.py` (40) | 08 |
| `ai-service/app/tools/shop_tools.py` (105), `account_tools.py` (177), `write_tools.py` (99), `support_tools.py` (232) | tools (Phase 1/3) | `search_products`, `get_product_knowledge`, `get_orders`, `track_order`, `create_return`, `reorder_product`, `create_support_case` ... | `test_tools.py`, `tests/support/*` | 08 |
| `ai-service/app/tools/actions.py` (56), `app/guard/confirmation.py` (88) | confirmed-action executor, signed tokens | `ActionExecutor.confirm`, `ConfirmationService.propose/redeem` | `test_tools.py` (token tests) | 08 |
| `ai-service/app/tools/dsports_client.py` (69), `dsports_http.py` (21), `build.py` (10) | REST client to Java, registry assembly | `DSportsClient.request`, `ToolError`, `build_registry` | `test_tools.py` | 08 |
| `ai-service/app/mcp/server.py` (101) | MCP facade | `build_mcp_server`, `propose_action`, `confirm_action` | `test_mcp.py` (6), `tests/live/test_stack_mcp*.py` | 08 |
| `ai-service/app/guard/input.py` (92), `grounding.py` (160), `untrusted.py` (66) | input guard, output grounding, untrusted-text handling | `check_input`, `check_grounding`, `collect_facts`, `sanitize_untrusted`, `fence` | `test_guards.py` (29), `tests/support/test_security_support.py` | 13 |
| `ai-service/app/shop/catalog.py` (146), `search.py` (136), `ranking.py` (107), `cards.py` (30) | live product facts, hybrid search pipeline, deterministic ranking | `Catalog.live`, `ProductSearch.search`, `rank`, `WEIGHTS` | `test_ranking.py` (17), `test_tools.py` | 09 |

## C. Python: RAG, storage, observability

| Path | Purpose | Important symbols | Tests | Doc |
|---|---|---|---|---|
| `ai-service/app/rag/ingest.py` (136) | ingestion pipeline | `Ingestor.ingest`, `clean_text`, `DocSpec` | `tests/integration/test_rag_db.py` (16), `tests/live/test_stack_rag.py` (5) | 07 |
| `ai-service/app/rag/chunking.py` (125) | heading-aware chunker | `chunk_text`, `count_tokens` | `test_chunking.py` (18) | 07 |
| `ai-service/app/rag/tika.py` (104) | Tika client, OCR decision | `TikaClient.extract`, `html_to_pages` | `test_tika.py` (8) | 07 |
| `ai-service/app/rag/embedder.py` (45), `retrieval.py` (153), `products.py` (77), `reindex.py` (50) | embeddings, hybrid retrieval, product indexer, re-index | `Embedder`, `Retriever.search_chunks/search_products`, `rrf_fuse`, `ProductIndexer` | `test_rag_db.py` | 07 |
| `ai-service/app/store/db.py` (61), `state.py` (178), `schema.sql`, `schema_rag.sql` | pool + schema, conversations/pending/audit, SQL | `Store`, `PgStateStore`, `ResilientStateStore`, `ConversationState` | `tests/integration/test_store.py` (8) | 12 |
| `ai-service/app/obs/{logging,middleware,context,metrics,usage}.py` | logs, correlation, metrics, usage/cost | `JsonFormatter`, `install_request_context`, `UsageRecorder`, `REGISTRY` | `test_observability.py` (15) | 15 |
| `ai-service/app/cache.py` (32), `cli.py` (68) | TTL cache, CLI (`ingest-corpus`, index, reindex) | `TTLCache` | | 07 |

## D. Python: Phase 2 inventory

| Path | Purpose | Important symbols | Tests | Doc |
|---|---|---|---|---|
| `ai-service/app/inventory/forecasting.py` (250) | cleaning, 4 models, backtests, metrics, intervals | `clean_series`, `predict`, `run_backtest`, `metrics`, `interval_for_sum`, `coverage` | `tests/inventory/test_forecasting.py` (9), `test_numeric_boundaries.py` (18) | 10 |
| `ai-service/app/inventory/planning.py` (196) | cover, risk, safety stock, replenishment, health | `assess`, `projected_cover`, `round_order`, `health_class` | `test_planning.py` (8) | 10 |
| `ai-service/app/inventory/scenarios.py` (215) | elasticity, what-if, recommended discount | `estimate_elasticity`, `run_item`, `evaluate` | `test_scenarios.py` (9) | 10 |
| `ai-service/app/inventory/events.py` (96) | event impact | `measure_event`, `estimate_upcoming`, `apply_to_forecast` | `test_numeric_boundaries.py` | 10 |
| `ai-service/app/inventory/generator.py` (321), `quality.py` (67), `pipeline.py` (307) | synthetic data, data-quality report, seed/train | `generate`, `report`, `seed`, `train` | `test_generator.py` (7), `tests/integration/test_inventory_db.py` (11) | 10 |
| `ai-service/app/inventory/repository.py` (532), `tools.py` (182), `api.py` (140) | queries, 17 ADMIN tools, REST | `InventoryRepository`, `tools()`, `admin`, `copilot` | `test_inventory_authz_matrix.py` (9), `tests/integration/test_phase2_scenarios.py` (34) | 08, 10 |
| `ai-service/app/inventory/understand.py` (186), `copilot.py` (694), `grounding.py` (83) | rule-based understanding, supervisor graph + explanation, numeric grounding | `understand`, `build_graph`, `InventoryCopilot.ask/_compose`, `is_grounded` | `test_understand.py` (7), `test_inventory_copilot.py` (13), `test_grounding.py` (6), `test_claims.py` (3) | 10 |

## E. Python: Phase 3 support

| Path | Purpose | Important symbols | Tests | Doc |
|---|---|---|---|---|
| `ai-service/app/support/model.py` (118) | vocabulary | `Complaint`, `CASE_CATEGORY`, `Fact`, `Inference`, `Missing`, `Investigation`, `Outcome`, `Decision` | `tests/support/*` | 11 |
| `ai-service/app/support/classify.py` (136) | deterministic complaint classification, safety flag | `classify`, `safety_flag`, `wants_human` | `test_classify_and_regressions.py` (12) | 11 |
| `ai-service/app/support/investigate.py` (410) | evidence gathering and assessment | `investigate`, `Gatherer`, `_delivery`, `_payment`, `_refund`, `best_passage` | `test_flow_delivery.py` (5), `test_flow_money.py` (10) | 11 |
| `ai-service/app/support/resolve.py` (156) | deterministic decision engine | `decide`, `_decide`, `CRITICAL_LOOKUPS` | `test_flow_*` | 11 |
| `ai-service/app/support/respond.py` (133) | customer text and UI payload | `findings_text`, `case_arguments`, `created_text`, `case_status_text` | `test_cases_conversation.py` (14) | 11 |
| `ai-service/app/support/flow.py` (340) | the workflow | `complaint`, `respond_to`, `case_create/status/message/escalate/reopen`, `apply_support_outcome` | `test_flow_products_returns.py` (16), `test_security_support.py` (19) | 11 |
| `ai-service/app/support/cli.py` (37) | ingest `corpus_support` | `python -m app.support.cli ingest-corpus` | `tests/integration/test_support_documents_db.py` (5) | 07 |
| `ai-service/tests/fakes.py`, `tests/support/conftest_support.py` | fake DSports with Java-equivalent rules for unit tests | `FakeDSports` | | 14 |

## F. Python: corpora, evaluation, scripts

| Path | Purpose | Doc |
|---|---|---|
| `ai-service/corpus/`, `corpus_support/`, `corpus_inventory/` (+ `manifest.yaml`) | sample documents (all labelled samples) | 07 |
| `ai-service/eval/run_eval.py`, `cases.yaml`, `holdout.yaml`, `poison.md` | Phase 1 harness and sets | 14 |
| `ai-service/eval/run_inventory_eval.py`, `eval/inventory/*` | Phase 2 harness: dev, holdout, holdout2, numeric cases, claims, side effects | 14 |
| `ai-service/eval/run_support_eval.py`, `eval/support/dev.yaml`, `holdout.yaml` | Phase 3 harness | 14 |
| `ai-service/eval/results/*` | historical results (preserved), `SUPPORT-RESULTS-NOTE.md` | 14 |
| `e2e/validate-all.sh` | consolidated validation (PASS/FAIL/BLOCKED/SKIP) | 14 |
| `e2e/ai/*.py|sh` | stack helpers, data builders, probes, failure instances, port-safe `free-port.sh` | 14 |
| `e2e/*.mjs` | Playwright journeys | 04, 14 |
| `e2e/api-e2e.sh` | Java API E2E | 14 |
| `e2e/README.md`, `docs/ai/12-local-development.md`, `docs/ai/phase2/13-runbook.md`, `docs/ai/phase3/11-testing-and-runbook.md` | operations | 14 |

## G. Java backend (`backend/`)

| Path | Purpose | Doc |
|---|---|---|
| `bootstrap/src/main/java/com/dsports/DSportsApplication.java` | entry point | 03 |
| `bootstrap/.../security/SecurityConfig.java` (77), `JwtWebFilter.java` (62) | route rules and JWT filter | 03, 13 |
| `bootstrap/.../exception/GlobalExceptionHandler.java`, `ApiErrors.java` | error mapping (support codes at lines 400-408) | 03 |
| `bootstrap/.../config/*ModuleConfig.java`, `ReactiveTransactionRunner.java`, `CorrelationIdFilter.java` | wiring, transactions, correlation | 03 |
| `bootstrap/.../jobs/ExpireUnpaidOrdersJob.java`, `outbox/*` | scheduled expiry, outbox | 03 |
| `identity/.../JwtTokenProvider.java`, `LoginUseCase.java`, `AuthController.java` | tokens, login | 03 |
| `catalog/.../PublicCatalogController.java`, `GetProductsUseCase.java`, `ProductR2dbcRepositoryAdapter.java` | catalog read path (traced) | 03 |
| `returns/.../ReturnPolicy.java`, `CreateReturnUseCase.java`, `ReturnController.java` | return rules and idempotent creation | 03, 09 |
| `order/...PublicOrderController.java`, `payment/...PublicPaymentController.java`, `shipment/...ShipmentController.java` | order, payment, shipment APIs | 03 |
| `support/.../SupportCase.java`, `CaseStatus.java`, `PriorityPolicy.java` | domain | 03, 11 |
| `support/.../CreateSupportCaseUseCase.java`, `CustomerCaseUseCase.java`, `StaffCaseUseCase.java` | use cases | 11 |
| `support/.../SupportCaseController.java`, `StaffSupportController.java`, `SupportRequests.java`, `SupportResponses.java` | REST | 11 |
| `support/.../SupportCaseR2dbcRepositoryAdapter.java` (284) | persistence | 11, 12 |
| `support/src/main/resources/db/migration/V44__create_support_cases.sql` | schema | 12 |
| `*/src/main/resources/db/migration/V*.sql` | Flyway migrations V1-V47 | 12 |
| Tests: `support/.../SupportCaseTest`, `PriorityPolicyTest`, `SupportCaseIntegrationTest`; `returns/.../ReturnPolicyTest`, `CreateReturnUseCaseTest`, `ReturnsIntegrationTest`; `bootstrap/.../FlywayMigrationVersionTest`, `GlobalExceptionHandlerTest` | | 03, 14 |

## H. Frontend (`frontend/src/`)

| Path | Purpose | Doc |
|---|---|---|
| `main.tsx`, `App.tsx`, `routes/index.tsx` | bootstrap, providers, routes | 04 |
| `api/client.ts` (54), `ai.ts` (55), `support.ts` (40), `inventoryAi.ts` (47), `errors.ts` (32) | HTTP clients and error helpers | 04 |
| `store/aiStore.ts` (149), `authStore.ts` (65), `cartStore.ts` | state | 04 |
| `features/ai/MessageList.tsx` (141), `AssistantPanel.tsx` (83), `AiCommerceSearch.tsx` (150), `AiShopPage.tsx` (66), `ConfirmActionDialog.tsx` (36), `ResponseExtras.tsx` (59), cards (`ProductResultCards`, `ComparisonTable`, `OrderStatusCard`, `OrderTimeline`, `ReturnOptions`, `ProductSelection`, `SourcesList`) | assistant UI | 04 |
| `features/support/SupportApp.tsx` (293), `MyCasesPage.tsx` (107), `SupportChatBlock.tsx` (27), `InvestigationEvidence.tsx`, `ResolutionSummary.tsx`, `parts.tsx` | support UI | 04, 11 |
| `features/inventory/InventoryApp.tsx` (99), `Copilot.tsx` (130), `tabs.tsx` (318), `blocks.tsx` (260), `charts.tsx`, `ui.tsx`, `ErrorBoundary.tsx` | inventory UI | 04, 10 |
| `types/ai.ts`, `types/support.ts`, `types/inventoryAi.ts` | response types | 04 |
| `vite.config.ts` | dev proxy `/api` and `/ai-api` | 02, 04 |

## I. Configuration, infrastructure, docs
`docker-compose.yml`, `ai-service/Dockerfile`, `ai-service/db/init-ai-db.sql`, `ai-service/.env.example`, `backend/bootstrap/src/main/resources/application*.yml`, `AGENTS.md`, `docs/adr/ADR-008..011`, `docs/ai/` (Phase 1), `docs/ai/phase2/`, `docs/ai/phase3/`, `docs/baseline/`, `docs/architecture.md`, `ROADMAP.md`.
