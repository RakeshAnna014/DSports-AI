# Customer AI: assistant, recommendations, RAG and MCP

Code: `ai-service/app/{agent,router,shop,reco,rag,tools,guard,mcp}`. Depth: [../ai/](../ai/01-architecture.md) (files 01-17), [ADR-009](../adr/ADR-009-python-ai-service.md), [ADR-012](../adr/ADR-012-profiles-and-budget-aware-recommendations.md). Platform foundation: [ai-platform.md](ai-platform.md).

## 1. Business purpose
Help a shopper find, compare and understand products, check orders, payments, refunds, shipments and returns, get checkout offers and recommendations, and take a few confirmed actions (return, reorder, support case). Guests can ask product questions; account questions need sign-in.

## 2. Entry points
`POST /v1/chat` (guest or signed-in), `POST /v1/confirm` (signed-in), `GET /v1/recommendations`, `GET /v1/offers/recommend`, MCP at `/mcp/`. UI: `frontend/src/features/ai/` (`AssistantPanel`, `AiShopPage`, cards, `ConfirmActionDialog`).

## 3. Request lifecycle (one chat turn)
`routes.chat` → `agent/service.ChatService` (loads `ConversationState`, enforces limits: `AI_MAX_ITERATIONS`, `AI_MAX_TOOL_CALLS`, `AI_TIMEOUT_S`, token budget) → LangGraph (`agent/graph.py`): `guard_in` (input guard, injection scan) → `route_node` (**Tier 0** rules in `router/tier0.py`, then **Tier 1** cheap-model classifier `router/tier1.py` only when the rules are unsure, cached) → one of three sub-graphs: *shopping* (search → rank → explain; compare; knowledge; recommend; kit; detail; reviews), *orders/returns* (list, detail, track, payment, refund, return check, reorder), *support* (see [support.md](support.md)) → `guard_out` (every product, price, status and order number in the text must exist in this turn's tool results, else a templated safe answer) → structured `ChatResponse` (message plus verified `products`, `comparison`, `orders`, `tracking`, `cart`, `payments`, `refunds`, `eligibility`, `actions`, `sources`, `suggestions`, `meta`).

## 4. Business rules
* Search: live catalogue through the Java API, a derived semantic index (`product_index`, never holds price or stock), live price and stock fetched per candidate, **deterministic ranking** (`shop/ranking.py`); the model only explains the top results.
* Recommendations (`reco/engine.py`): hard constraints (in stock, within the stated budget which is never widened, not owned, sport/role match, child age rule), then the budget band (explicit budget over a 20 % complementary ceiling), no model call. Rules and thresholds are `AI_RECO_*` settings.
* Knowledge answers use hybrid retrieval (pgvector cosine + full-text, fused) over the **sample corpus** (synthetic demo documents) and cite `sources`; with nothing found the assistant says so.
* Writes (`create_return`, `reorder_product`, `create_support_case`, `add_case_message`, `request_case_escalation`, `reopen_support_case`) are proposals first; `POST /v1/confirm` redeems a single-use token and calls Java with an idempotency key. The assistant never places an order or payment.

## 5. Data ownership
Reads (through Java): catalogue, price, stock, reviews, cart, orders, payments, refunds, shipments, returns, cases, shopper profile. Writes in `dsports_ai`: `conversations` (TTL `AI_CONVERSATION_TTL_H`), `pending_actions`, `audit`, `usage`, `documents`, `chunks`, `product_index`. The assistant records unmet product searches as redacted, hashed demand signals for [sourcing.md](sourcing.md).

## 6. External dependencies
Java REST API, Gemini (intent and reasoning tiers, embeddings), Tika (corpus ingestion only), PostgreSQL + pgvector.

## 7. Security and authorization
Identity is the verified token, never a tool argument; tools are `extra="forbid"`; account tools need authentication and Java enforces ownership (a 403/404 from Java is the answer); retrieved and catalogue text is fenced as untrusted data; only a first name goes to the model (no address, phone, e-mail, payment data); per-customer chat limiter (`AI_RATE_LIMIT_PER_MIN`); the same gates apply over MCP. Tests: `tests/unit/test_tools.py`, `tests/security/test_tools_and_prompt_injection.py`, the evaluation security set.

## 8. Failure handling and observability
Model down: Tier-0 flows and deterministic recommendations still work, other intents return a structured fallback. Java down: typed tool errors. Usage per call (tokens, latency, estimated cost) in `usage`; metrics at `/metrics`. Trace a request by its `X-Correlation-Id` in both service logs.

## 9. Run and test
Start the stack ([../SETUP-AND-DEMO.md](../SETUP-AND-DEMO.md)); seed with `uv run python -m app.cli index-products` and `ingest-corpus`. Tests: `uv run pytest -q` (unit), `eval/run_eval.py` (live model; development and held-out sets, see [../ai/09-evaluation.md](../ai/09-evaluation.md)), browser: `e2e/ai-commerce.mjs`, `ai-ui.mjs`, `ai-multisport.mjs`, scripted scenarios `e2e/ai/ai_scenarios.py`.

## 10. Implemented / partial / missing
Implemented: all of the above. Partial: the knowledge base is sample content, not real manufacturer documents; no streaming. Missing by design: payment actions, human hand-off. Live-model answer quality on new phrasings is model-dependent and is only claimed where a recorded run exists in [../STATUS.md](../STATUS.md).

## 11. First files to read
`agent/service.py`, `agent/graph.py`, `router/tier0.py`, `tools/registry.py`, `tools/write_tools.py`, `guard/confirmation.py`, `shop/ranking.py`, `reco/engine.py`.
