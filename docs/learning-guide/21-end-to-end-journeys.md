# 21. End-to-end execution traces (Journeys A-E)

Each journey is traced from the actual code. "Present?" marks steps that exist; where a step does **not** exist it says so. Source references use `symbol @ path:start-end`.

## Journey A: Customer AI product discovery

Full UI-level trace with code references: doc 04 section 5. Condensed:

| # | Step | Present? | Where |
|---|---|---|---|
| 1 | Customer types in the search box, presses Enter | yes | `AiSearchBox.submit @ frontend/src/features/ai/AiCommerceSearch.tsx:67-71` |
| 2 | Store action | yes | `useAiStore.send @ frontend/src/store/aiStore.ts:88-111` |
| 3 | API request `POST /ai-api/v1/chat` | yes | `aiApi.chat @ frontend/src/api/ai.ts:16-29`; Vite rewrites to `:8090/v1/chat` (`vite.config.ts:21-26`) |
| 4 | Python endpoint, identity (guest or JWT) | yes | `chat @ ai-service/app/api/routes.py:19-23`, `optional_principal @ app/auth/jwt.py:87-97` |
| 5 | Graph state initialisation | yes | `ChatService.chat @ app/agent/service.py:143-158` (`{"message": ...}`, runtime in config) |
| 6 | Intent / query understanding | yes | `guard_in`, `route_node @ graph.py:54-94`; Tier 0 `extract_shopping_slots` else Tier 1 `Tier1.classify @ router/tier1.py:140-161` |
| 7 | Model routing | yes | Tier 0 (0 tokens) -> Tier 1 `gemini-2.5-flash-lite` JSON -> later Tier 2 `gemini-2.5-flash` explanation |
| 8 | Typed tool invocation | yes | `shopping.search @ app/agent/shopping.py:61-84` -> `rt.call("search_products")` -> `ToolRegistry.call @ tools/registry.py:122-149` |
| 9 | Java calls | yes | `Catalog.prices/stock/details/ratings @ app/shop/catalog.py:111-174` -> `GET /api/prices`, `/api/inventory`, `/api/catalog/products/{id}` (+`/attributes`), `/api/v1/reviews/product/{id}/summary` (public endpoints, `SecurityConfig.java:47-50`) |
| 10 | Optional RAG | yes (retrieval of products) | `Retriever.search_products @ app/rag/retrieval.py:79-110` over `product_index` (pgvector + full text); no document RAG in a plain search |
| 11 | Ranking | yes | `rank @ app/shop/ranking.py:86-130` |
| 12 | Explanation | yes unless plain keyword search | `sh_explain @ graph.py:150-156` -> `_llm_text @ shopping.py:107-123` |
| 13 | Response validation | yes | `guard_out @ graph.py:121-139` (`check_grounding`) |
| 14 | API response | yes | `ChatResponse @ app/agent/schemas.py:89-118` |
| 15 | Render | yes | `AssistantMessage @ frontend/src/features/ai/MessageList.tsx:54-114`: `ProductResultCards` inside "VERIFIED FROM DSPORTS", text under "AI EXPLANATION" |

Calls both Python and Java: yes (Python understands/retrieves/ranks/explains; Java supplies live price, stock, product facts, ratings).

## Journey B: Inventory Intelligence question

Example: "Which products are likely to stock out next month?"

| # | Step | Present? | Where |
|---|---|---|---|
| 1 | Admin opens `/inventory-ai`, Copilot visible | yes (ADMIN only in UI) | `InventoryApp @ frontend/src/features/inventory/InventoryApp.tsx:29-99` (role gate lines 57-67) |
| 2 | Click starter chip or type | yes | `Copilot.ask @ frontend/src/features/inventory/Copilot.tsx:90-105`; `STARTERS` lines 13-20 |
| 3 | Authenticated endpoint | yes | `inventoryAiApi.copilot @ api/inventoryAi.ts:34-35` -> `POST /ai-api/v1/inventory/copilot` -> `copilot @ ai-service/app/inventory/api.py:186-193`; `admin` dependency (30-41) returns 403 + `DENIED` audit row for non-ADMIN |
| 4 | Guard before any model/tool | yes | `InventoryCopilot.ask @ app/inventory/copilot.py:612-625` (`check_input`, customer-data and privilege regexes) |
| 5 | Conversation memory | yes | `state_store.load` (627-628) |
| 6 | Understanding (rules first) | yes | `understand.classify @ app/inventory/understand.py:141-177` -> `STOCK_RISK` (regex `stock[- ]?out|run out|at risk`); `within_days = 30` for "next month" (`extract_entities`, 127-128). Model only if `UNKNOWN` (`_maybe_model`, 705-726) |
| 7 | Graph | yes | `graph.ainvoke` (668) -> node `n_stock_risk @ copilot.py:244-273` |
| 8 | Tool and database read | yes | `rt.call("get_stock_risk", {limit, within_days})` -> registry gate (ADMIN) -> `get_stock_risk @ app/inventory/tools.py:148-153` -> `InventoryRepository.stock_risks @ app/inventory/repository.py:177-192` -> SQL over `inv_assessment` joined to `inv_sku` for the **current model run** and scenario `BASELINE` |
| 9 | Deterministic numbers | yes | days of cover, risk level, probability of stock-out were computed at training time by `planning.assess @ app/inventory/planning.py:120-211` over the persisted forecast and **stored**; this request only reads them (training is a separate manual operation) |
| 10 | Facts, blocks, headline | yes | `n_stock_risk` builds `facts` (counts such as `high_risk_count` computed in code), a `risk_table` block and a deterministic headline |
| 11 | Optional LLM explanation | yes | `_compose @ copilot.py:728-757`: facts JSON + system rules ("never calculate a new number"); then `is_grounded` verifies every number; failure -> deterministic headline (`grounding_fallback`) |
| 12 | Response | yes | `InventoryResponse @ copilot.py:92-108` incl. `provenance` (run id, model, as-of, data classes) and `evidence` |
| 13 | Display | yes | `Assistant @ Copilot.tsx:54-79`: badges ("AI Recommendation", data source), text, `Block` components (`RiskTable`), warnings, and a trace line "Numbers from: get_stock_risk · model ridge_regression ..., POC synthetic history · explained by gemini... (n in / m out tokens)" |

Why the LLM is not the numeric source of truth: the numbers are produced by `forecasting.py` and `planning.py` (numpy, no model), persisted, read by tools, shown in tables from tool data, and the model's wording is rejected if it contains any number not in the facts. All data are `SYNTHETIC / POC DATA`.

## Journey C: Customer complaint and support case

Full sequence diagram and branches: doc 11 sections 3 and 5. Condensed mapping of the requested chain:

| Requested step | Implemented? | Where |
|---|---|---|
| Customer complaint | yes | chat message |
| Identity and request validation | yes | JWT (`optional_principal`), `guard_in`, rate limiter, tool args validation |
| Support intent classification | yes | Tier 0 `_support_route @ app/router/tier0.py:208-` and `classify @ app/support/classify.py:103-161`; Tier 1 may add `complaint_type` validated against `Complaint` |
| Context gathering | yes | `flow.complaint @ app/support/flow.py:76-97` -> `_identify_order` (156-197), `_identify_product` (229-252); asks if ambiguous |
| Order/payment/refund/return evidence | yes | `investigate @ app/support/investigate.py:90-156` (parallel READ tools) |
| Policy retrieval | yes | `search_support_policy` -> `get_product_knowledge` (customer document types incl. `procedure`) |
| Evidence-based decision | yes | `decide @ app/support/resolve.py:53-177` |
| Request-more-information / answer | yes | `ASK_INFO`, `ANSWER`, `SAFE_NEXT_STEP`, `UNVERIFIABLE` |
| Create case / escalate | yes (after confirmation) | `_propose("create_support_case")` -> `ActionExecutor.confirm @ app/tools/actions.py:32-66` -> `POST /api/v1/support/cases` |
| Persistent Java support case | yes | `CreateSupportCaseUseCase.execute @ backend/support/.../CreateSupportCaseUseCase.java:65-133`, tables `support_cases`, `support_case_messages`, `support_case_events` |
| Audit | yes | AI `audit` rows (`PROPOSED`, `CONFIRMED`, `EXECUTED`); Java `support_case_events` (`CREATED`, `ESCALATION_REQUESTED`) |
| UI response | yes | `SupportChatBlock`/`SupportCaseCard` (`CASE_CREATED`) |
| Later case-status lookup | yes | `case_status @ flow.py:302-324` -> `GET /api/v1/support/cases[/{ref}]`; text from `respond.case_status_text` |
| **Not present** | - | resolving, refunding, assigning by the assistant; photo assessment; carrier contact; live agent; notifications |

## Journey D: RAG question (policy)

Full trace: doc 07 section 8. Mapping: **extraction** `TikaClient.extract @ app/rag/tika.py:90-123`; **chunking** `chunk_text @ app/rag/chunking.py:113-149`; **embedding** `Embedder.embed_documents @ app/rag/embedder.py:38-44` (ingest) and `embed_query` (46-57) (query); **vector retrieval** `Retriever.search_chunks @ app/rag/retrieval.py:113-161`; **context construction** `get_product_knowledge @ app/tools/shop_tools.py:92-116` + `answer_from_sources @ app/agent/prompts.py:66-76`; **citation generation** `shopping.knowledge @ app/agent/shopping.py:259-289` (`_source`, 295-297). Ingestion command: `uv run python -m app.cli ingest-corpus`.

## Journey E: Failure handling (three real paths)

### E1. Gemini quota or timeout
1. **Detect (Python):** `VertexGemini.generate` catches the SDK exception and `_classify` maps HTTP 429 to `LLM_QUOTA` (and 504/timeouts to `LLM_TIMEOUT`), recording a usage row with the outcome (`vertex.py:61-71, 112-119`).
2. **Retry/fallback:** `ResilientLLM._guarded` retries once only for `LLM_TIMEOUT`/`LLM_ERROR` (not quota); quota counts toward the breaker, which opens after 3 consecutive failures for 30 s and then fails fast with `AI_UNAVAILABLE` (`resilient.py:24-89`).
3. **Caller behaviour:** during routing, `route_node` catches `LLMError`: if rules can extract search slots it answers with a plain filtered search plus the disclaimer "The AI assistant is limited right now, so I used a simple search"; otherwise `status="ai_unavailable"`, `error_code="AI_UNAVAILABLE"` and a message listing what still works (`graph.py:73-87`). In explanation nodes `_llm_text` keeps the deterministic text and adds "The AI explanation is unavailable right now; you are seeing the verified data only." (`shopping.py:111-116`).
4. **Error contract:** a normal `ChatResponse` (HTTP 200) with `status` and `errorCode`; REST-level errors use the common `ApiError` body.
5. **UI outcome:** `responseKind` = `ERROR` -> `ResponseError` "DSports AI is taking a break ... orders, cart and checkout are not affected" with a retry button (`ResponseExtras.tsx:46-55`); Tier-0 flows keep working.
6. **Evidence:** `test_circuit_breaker_opens_after_repeated_model_failures_and_tier0_keeps_working`; during Phase 3 validation real 429s produced `OUT_OF_SCOPE` replies and invalid evaluation runs (doc 14).

### E2. Downstream business API (Java) failure
1. **Detect (Python):** `DSportsClient.request` converts a timeout to `ToolError("UPSTREAM_TIMEOUT")` and a connection failure to `UPSTREAM_UNAVAILABLE`; 5xx becomes `UPSTREAM_ERROR` (`dsports_client.py:41-47, 67-71`). The registry returns `ToolResult(ok=False, error_code, error_message)` and never raises (`registry.py:139-148`).
2. **Retry:** none for tool calls (reads are cheap to re-ask; writes must not repeat).
3. **Node behaviour:** order nodes: "I couldn't get your orders: The shop service is not reachable right now" with `status="error"` (`orders._fail @ orders.py:37-41`); support investigation: a failed critical lookup makes `decide` return `UNVERIFIABLE` ("I could not check ... right now, so I won't guess. Nothing was created. Please try again shortly.") and `error_code="UPSTREAM_UNAVAILABLE"`; **no case number is invented** (`resolve.py:65-68`, `flow.respond_to` 117-120; `test_when_support_is_down_no_case_number_is_ever_invented`).
4. **Error contract:** `ChatResponse.status`/`errorCode`; Java down means the AI service's own HTTP answer is still 200.
5. **UI outcome:** `ERROR` type shows the friendly error with retry (order nodes) or the support text with the investigation card (UNVERIFIABLE keeps status ok).
6. **Where Java itself fails:** its `GlobalExceptionHandler` returns `ApiError` JSON (e.g. 409/404/400) which the AI service maps by status and `code`.

### E3. Missing or unauthorized customer/case data
1. **Request:** "What happened to case CASE-20261012-000099?" (someone else's or nonexistent).
2. **Detect (Java):** `CustomerCaseUseCase.owned @ CustomerCaseUseCase.java:100-102` returns `CASE_NOT_FOUND` (404) for both cases; `GlobalExceptionHandler` maps it to the `ApiError` body (lines 400-401).
3. **Python mapping:** `DSportsClient._interpret` takes the body `code`; `get_support_case` catches `NOT_FOUND`/`CASE_NOT_FOUND`/`FORBIDDEN` and raises one customer-safe `ToolError("NOT_FOUND", "I could not find that case on your account")` (`support_tools.py:168-181`); `flow._fail` writes "I couldn't get that case: I could not find that case on your account" with `status="ok"` (NOT_FOUND/FORBIDDEN are not treated as errors, `flow.py:53-56`).
4. **No retry/fallback** (a correct negative answer). Nothing about the other customer is revealed (`test_another_customers_case_number_answers_exactly_like_a_missing_one`).
5. **Unauthorized *staff* access:** a customer token on `/api/v1/support/staff/**` -> Spring Security `accessDeniedHandler` writes `403 ACCESS_DENIED` in the common error shape (`SecurityConfig.java:58-59`); the UI maps 403 to "This area is restricted to DSports support staff." (`supportError @ frontend/src/api/support.ts:31-40`) and the console itself is hidden for non-staff roles.
6. **UI outcome:** a plain assistant message (no card), or the restricted-area screen for the staff console.
