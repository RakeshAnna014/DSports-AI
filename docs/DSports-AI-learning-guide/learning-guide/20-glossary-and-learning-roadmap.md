# 20. Glossary and learning roadmap

## Part A. Glossary (project-specific and AI terms)

Each entry: definition; why it matters here; where it appears; a project example; a common interview misconception.

| Term | Simple definition | Why it matters here | Where in code | Project example | Common misconception |
|---|---|---|---|---|---|
| **Source of truth** | the system whose data is authoritative | prevents the AI inventing prices, stock, statuses | Java modules; `Catalog.live` | price comes from `GET /api/prices`, never from the model | "the AI index is the product database" (it stores no price/stock) |
| **Tier 0 / Tier 1 / Tier 2** | rules (no model) / cheap classifier / stronger explainer | cost and reliability | `tier0.py`, `tier1.py`, `shopping._llm_text` | "Show my orders" is Tier 0 | "everything goes through the big model" |
| **Intent** | the kind of request the user made | selects the graph node | `router/intents.py` | `TRACK_ORDER` | confusing it with the tool call |
| **Slot** | a parameter extracted from text | filters/lookups | `router/extract.py` | `max_price=8000` | "the model's slots are trusted" (they are verified) |
| **LangGraph** | a library for state machines of nodes and edges | routing and runtime structure | `agent/graph.py` | `guard_in -> route -> shopping -> guard_out` | "it makes the system an autonomous agent" |
| **Subgraph** | a graph used as a node | domain boundaries | `shopping_graph`, `orders_graph`, `support_graph` | support subgraph | "each node calls the LLM" |
| **Typed tool** | a function with a validated schema and metadata | the only way to reach data/actions | `tools/registry.py` | `track_order` | "the model calls tools" (code does) |
| **READ / WRITE tool** | does not change data / changes data (needs confirmation) | safety | `Kind` enum | `create_support_case` is WRITE | "read tools are free of side effects" (`run_promotion_scenario` logs) |
| **MCP** | Model Context Protocol for exposing tools to AI clients | external access to the same tools | `mcp/server.py` | `propose_action` / `confirm_action` | "the agent uses MCP internally" (it calls the registry in-process) |
| **RAG** | retrieve documents, then generate an answer from them | cited knowledge answers | `rag/`, `shopping.knowledge` | return policy answer with `[1]` | "RAG is the source of truth for current facts" |
| **Embedding** | a vector representing text meaning | semantic search | `rag/embedder.py` | `text-embedding-005`, 768 dims | "embeddings can be compared across models" |
| **pgvector / HNSW** | Postgres vector type / approximate nearest-neighbour index | similarity search | `schema_rag.sql` | `embedding <=> $q` | "HNSW gives exact results" |
| **Chunk** | a slice of a document stored with metadata | retrieval unit | `rag/chunking.py` | ~450 tokens, 60 overlap | "bigger chunks are always better" |
| **RRF** | Reciprocal Rank Fusion of ranked lists | combines vector and keyword search | `retrieval.rrf_fuse` | `1/(60+rank)` | "scores can be added directly" |
| **Tika / OCR** | document text extraction / text from images | ingestion of PDFs and scans | `rag/tika.py` | scanned warranty PDF | "OCR runs on every PDF" (only pages without text) |
| **Grounding** | verifying claims against tool results | stops hallucinated facts | `guard/grounding.py`, `inventory/grounding.py` | invented price is replaced | "grounding proves the answer is correct" (it proves the facts are in the data) |
| **Fallback text** | the deterministic answer built from the same data | graceful degradation | `Turn.fallback_text` | shown when Gemini is down | "the fallback is a worse answer" (it is the verified one) |
| **Input guard** | pattern checks run before any model/tool | stops injection and staff-only asks | `guard/input.py` | refuses "list every open case" | "it is the main security control" (it is the weakest layer) |
| **Untrusted data fence** | markup that marks data the model must not obey | injection defence | `guard/untrusted.py` | `<untrusted_data>` | "fencing alone prevents injection" |
| **Confirmation token** | signed single-use proof the customer approved an exact action | safe WRITEs | `guard/confirmation.py` | `v1.<payload>.<hmac>` | "the token carries the arguments" (they are stored server-side) |
| **Idempotency key** | client-supplied key making a retry harmless | no duplicate returns/cases | `Idempotency-Key` header | `ai-<nonce>` | "idempotency prevents all duplicates" (categories/orders can differ) |
| **Principal** | the verified identity extracted from a token | identity never comes from the body | `auth/jwt.py` | roles + customer hash | "the request body can carry the user id" |
| **Customer hash** | HMAC of the user id used in AI storage and logs | privacy | `customer_hash()` | `conversations.customer_hash` | "it is reversible with the id" (needs the key) |
| **Case** | a persisted support complaint | Phase 3 record | `SupportCase` | `CASE-20261012-000001` | "the AI creates it alone" (customer confirms; Java creates) |
| **Escalation REQUESTED vs ACCEPTED** | someone was asked vs someone took it | honesty about human involvement | `EscalationState` | "nobody has taken it yet" | "escalation is a status" |
| **Backend-verified evidence** | facts the Java backend itself recorded | trust separation | `EvidenceItem.BACKEND_VERIFIED` | order/shipment/payment snapshot | "evidence submitted by the AI is verified" |
| **Investigation (facts / inferences / missing)** | the structured result of checking records | Phase 3 workflow | `support/model.py` | "Proof of delivery" is missing | mixing possibilities into facts |
| **Decision outcome** | ANSWER, ASK_INFO, SAFE_NEXT_STEP, CREATE_CASE, ESCALATE, UNVERIFIABLE | deterministic resolution | `Outcome` enum | `ESCALATE` for delivered-not-received | "the LLM chooses the outcome" |
| **Optimistic locking** | detect concurrent modification via a version | staff edits | `@Version`, `expectedVersion` | stale view rejected | "it blocks concurrent reads" |
| **Advisory lock** | Postgres application-level lock | race-free idempotent creation | `pg_advisory_xact_lock` | one case from 8 concurrent calls | "a unique index is enough for read-then-insert logic" |
| **Outbox** | table of events written in the same transaction for later delivery | reliable messaging | `outbox_events` | order/payment events | "it is used by support" (support uses `support_case_events`) |
| **Circuit breaker** | stop calling a failing dependency for a while | protects from LLM outages | `resilient.py` | opens after 3 failures for 30 s | "it retries forever" |
| **WAPE** | weighted absolute percentage error | forecast accuracy | `forecasting.metrics` | 38.6 % held-out | "lower WAPE on synthetic data means a good forecaster" |
| **Holdout / backtest** | data kept for unbiased evaluation / repeated forecasting from past origins | honest accuracy | `run_backtest` | 90-day holdout | "tuning on the holdout is fine" |
| **Days of cover** | how many days current stock lasts at forecast demand | stock-out risk | `planning.projected_cover` | HIGH if cover < lead time | "cover ignores incoming orders" (arrivals are included) |
| **Safety stock** | buffer for demand uncertainty | replenishment | `planning.assess` | `1.645 x sd` | "it is a fixed percentage" |
| **Elasticity** | demand response to price | promotion what-ifs | `scenarios.estimate_elasticity` | e = 1.5 (POC prior) | "it is a measured business fact" (here a flagged assumption or synthetic estimate) |
| **POC_SYNTHETIC / POC_REFERENCE** | labels for generated / assumed data | prevents presenting fake data as real | `inv_*.data_source` | every inventory response | "synthetic results are real forecasts" |
| **Held-out first run** | the first execution of an unseen evaluation set | the only unbiased measurement | `eval/results/*first-run*` | Phase 3 15/24 | "later 100 % proves it generalises" |
| **BLOCKED / SKIP / FAIL** | validation statuses: precondition missing / not selected / failed | honest reporting | `validate-all.sh` | `sup-eval-holdout` SKIP | "SKIP means pass" |
| **Quota (429)** | provider rate limit | environmental failures | `LLM_QUOTA` | `OUT_OF_SCOPE` replies during quota | "a quota failure is a model-quality failure" |

## Part B. Learning roadmap

Twelve sessions of roughly 60-90 minutes. For each: read, trace, reproduce a diagram, answer practice questions, and run a self-check. Use the interview bank (doc 19) as practice and the traces in docs 04-11.

| # | Session | Read | Trace in code | Diagram to reproduce | Practice | Self-check (you can explain...) |
|---|---|---|---|---|---|---|
| 1 | The big picture | docs 00, 01, 02 | `docker-compose.yml`, `vite.config.ts` | component and runtime topology | Q9, Q10, Q109 | what each service owns; why Java is the source of truth |
| 2 | Java security and ownership | doc 03 sections 3-5; doc 13 T1-T3 | `SecurityConfig`, `JwtWebFilter`, `PublicCatalogController` -> adapter | JWT sequence diagram | Q4, Q7, Q69, Q71 | how a request is authenticated and why cases 404 |
| 3 | Java domain and transactions | doc 03 sections 6-8 | `ReturnPolicy`, `CreateReturnUseCase`, `SupportCase` | support ER diagram | Q1-Q3, Q5, Q6, Q77 | idempotency, advisory lock, optimistic locking |
| 4 | Python service structure | doc 05 sections 1-3, 6-7 | `main.py`, `services.py`, `service.py` | startup sequence | Q15-Q20 | composition root, settings, one chat turn |
| 5 | Routing and the agent graph | doc 05 section 4, doc 09 section 2 | `graph.py`, `tier0.py`, `tier1.py` | the generated customer graph | Q32-Q37, Q38-Q41 | Tier 0/1/2, guard_in/out, workflow vs agent |
| 6 | LLM calls and prompts | doc 06 | `vertex.py`, `resilient.py`, `prompts.py`, `shopping._llm_text` | model call path | Q21-Q31, Q84, Q96 | tiering, cost, quota behaviour, why no function calling |
| 7 | Tools, MCP and confirmed writes | doc 08 | `registry.py`, `actions.py`, `confirmation.py`, `mcp/server.py` | WRITE sequence diagram | Q42-Q46, Q73, Q75, Q76, Q90 | READ/WRITE, token design, revalidation |
| 8 | RAG end to end | doc 07 | `ingest.py`, `chunking.py`, `tika.py`, `retrieval.py` | ingestion and retrieval flows | Q47-Q56, Q57-Q61 | chunking, hybrid search, internal isolation |
| 9 | Phase 1 journeys | doc 09, doc 04 section 5 | `shopping.search`, `shop/search.py`, `ranking.py`, `orders.py` | Journey A trace | Q89, Q100, Q107 | discovery, ranking weights, returns, reorder |
| 10 | Phase 2 inventory | doc 10 | `forecasting.py`, `planning.py`, `scenarios.py`, `copilot.py` | inventory graph | Q62-Q68 | WAPE, leakage guard, safety stock, why synthetic is not evidence |
| 11 | Phase 3 support | doc 11 | `flow.py`, `investigate.py`, `resolve.py`, `CreateSupportCaseUseCase` | complaint sequence diagram | Q69, Q97, Q99, Q102 | evidence kinds, outcomes, escalation REQUESTED vs ACCEPTED |
| 12 | Testing, evidence, limits and presenting | docs 13-16, 18 | `validate-all.sh`, `run_support_eval.py` | threat table (from memory) | Q79-Q83, Q92, Q103-Q110 | what is verified vs not, how to demo, how to answer honestly |

Suggested order for the first five topics to study with ChatGPT: (1) source-of-truth and service boundaries, (2) the Customer Agent turn from `guard_in` to `guard_out`, (3) confirmation tokens and WRITE safety, (4) the Phase 3 complaint-to-case flow, (5) evaluation integrity and how to talk about limits.
