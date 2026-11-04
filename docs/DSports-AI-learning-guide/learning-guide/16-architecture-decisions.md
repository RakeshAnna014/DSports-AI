# 16. Architecture decisions and trade-offs

Each entry gives: the problem, the chosen approach, why it fits this POC, alternatives, trade-offs, repository evidence, and what would change for production. "Alternatives considered" are marked **(documented)** when an ADR or doc lists them, otherwise **(relevant, not formally evaluated)**: the repository contains no benchmark comparing them.

## D1. Java business backend versus a Python AI service
* **Problem:** add AI without risking checkout/payments and while using the best AI tooling.
* **Chosen:** a separate Python/FastAPI service that calls Java only through public REST with the customer's own JWT; Java stays the source of truth (`docs/adr/ADR-009-python-ai-service.md`).
* **Why it fits:** LangGraph, the MCP SDK, Vertex SDKs and evaluation tooling are more mature in Python; AI traffic is latency-heavy and failure-prone; AI outage or cost cannot take down commerce (verified: `shop-with-ai-down`).
* **Alternatives (documented):** Spring AI inside the monolith; Python with direct database access (rejected: bypasses ownership checks); Python with its own service credential (rejected for Phase 1).
* **Trade-offs:** a second runtime to build/test/secure; extra hop and a second JWT validation path with a shared HS256 secret; deviates from the Java-only stack in `AGENTS.md` (updated).
* **Evidence:** ADR-009; `docker-compose.yml`; `ai-service/app/tools/dsports_client.py`.
* **Production:** asymmetric JWT (JWKS) or token exchange, mTLS between services, separate deployment units/autoscaling, CI for both runtimes.

## D2. Deterministic logic versus LLM reasoning
* **Problem:** LLMs are fluent but not reliable for money, stock, eligibility or decisions.
* **Chosen:** code decides, tools fetch, the LLM only (a) classifies free text when rules fail and (b) phrases explanations of already-computed facts; outputs are verified (`guard_out`, inventory numeric grounding); Support text is not model-written.
* **Why it fits:** testable, reproducible, cheap, explainable; AI is optional.
* **Alternatives (relevant, not formally evaluated):** an LLM agent that chooses tools and writes everything; hybrid with LLM-as-judge.
* **Trade-offs:** rule maintenance and coverage gaps (held-out runs repeatedly found missed phrasings); deterministic text is less fluent; every new capability needs code.
* **Evidence:** `ai-service/app/agent/shopping.py:1-4` docstring; `app/support/resolve.py:1-10`; `app/inventory/forecasting.py:1`; `docs/adr/ADR-010` decision 1.
* **Production:** a larger labelled intent dataset, a learned classifier with fallbacks, continuous evaluation, content review of the deterministic templates.

## D3. LangGraph orchestration
* **Problem:** structured multi-step flows with a uniform runtime and bounded loops.
* **Chosen:** one Customer Agent graph with a guard, a router, three subgraphs and an output guard; one Inventory graph. Detail flows (the support workflow) run as Python functions inside nodes.
* **Why it fits:** a visible, testable routing structure with `recursion_limit` and a single place for guards; "one agent, three subgraphs where there is a real domain boundary; simple capabilities are typed tools, not agents" (`graph.py:11-12`).
* **Alternatives (relevant):** plain async functions; a different agent framework; one agent per tool/API (explicitly avoided: the brief forbade artificial agents per API).
* **Trade-offs:** LangGraph is used mostly for **routing** here; there are no LLM-driven loops, no checkpointing or human-in-the-loop interrupts (confirmation is implemented by the application, not by graph interrupts); the graph adds a dependency and a mental model for what is largely a dispatcher.
* **Evidence:** `ai-service/app/agent/graph.py`, `app/inventory/copilot.py:583-592`.
* **Production:** persist graph state/checkpoints if long-running flows appear; decide whether the dependency still earns its place.

## D4. MCP and typed tools
* **Problem:** one safe way to expose capabilities to the in-process agent and to external MCP clients.
* **Chosen:** a single typed `ToolRegistry` (Pydantic `StrictArgs`, READ/WRITE, role and auth gates, budgets); the agent calls it in-process; an MCP facade exposes the same objects, READ tools directly and WRITE only via `propose_action`/`confirm_action`.
* **Why it fits:** safety is enforced in one place (not prompts), identical behaviour for both channels, no network hop on the hot path.
* **Alternatives (relevant):** agent consumes MCP over the network always; model-driven function calling (not used).
* **Trade-offs:** the MCP layer is a facade used by tests/external clients, not by the product's own agent; the model cannot choose tools, so flexibility is limited.
* **Evidence:** `ai-service/app/tools/registry.py`, `app/mcp/server.py`, `tests/live/test_stack_mcp*.py`.
* **Production:** authenticated MCP gateway, per-client rate limits, tool-level audit of MCP callers, versioned tool schemas.

## D5. RAG versus fine-tuning
* **Problem:** answer product/policy questions with citations from a changing corpus.
* **Chosen:** retrieval-augmented generation with cited passages; no fine-tuning.
* **Why it fits:** documents change, citations are required, the corpus is tiny, and no training data exists. (Fine-tuning would not give citations or easy updates.)
* **Alternatives:** fine-tuning, long-context stuffing (**relevant, not formally evaluated**: no experiment exists in the repository).
* **Trade-offs:** retrieval quality depends on chunking/embeddings and a small sample corpus; the measured hit@5 is on a few dozen chunks.
* **Evidence:** doc 07; `docs/ai/06-rag.md`; Phase 1 evaluation retrieval metrics.
* **Production:** a real corpus, retrieval evaluation set, reranking if measured to help, ingestion pipeline with versioning and approval.

## D6. PostgreSQL + pgvector (one engine)
* **Problem:** store embeddings, support metadata filtering and keyword search.
* **Chosen:** `pgvector/pgvector:pg16-trixie` with HNSW cosine indexes and a generated `tsvector`; a **separate database** `dsports_ai` (`docs/ai/03-infrastructure.md`: the image keeps the same major version and glibc as the previous `postgres:16` so the existing data volume stays valid).
* **Why it fits:** one engine, SQL metadata filters in the same query, fused with full-text via RRF, no new system to operate.
* **Alternatives (relevant, not formally evaluated):** a dedicated vector database (for example Qdrant or Chroma). No benchmark exists; the project simply chose not to add another engine.
* **Trade-offs:** HNSW in Postgres has scaling limits; the vector dimension is fixed in the schema (re-index on model change, detected by `check_embedding_config`).
* **Evidence:** `schema_rag.sql`, `retrieval.py`, `db.py:49-62`.
* **Production:** managed Postgres with pgvector, index tuning, capacity tests, backup and retention.

## D7. Apache Tika and OCR
* **Problem:** ingest md/pdf/docx/html and scanned documents.
* **Chosen:** the Tika server (`apache/tika:latest-full`, includes Tesseract), called over HTTP; OCR decided by Tika's own layer-detection and recorded (`tika.py:1-14`).
* **Why it fits:** one extractor for many formats; no OCR engine in the Python image; honest handling of Tika 4's disabled per-request config (documented, measured).
* **Alternatives (relevant):** per-format Python libraries, cloud document AI.
* **Trade-offs:** an extra container; a `latest-full` tag is not pinned; a single scanned sample document.
* **Production:** pin versions, scan for vulnerabilities, size/time limits for uploads, quarantine for untrusted documents.

## D8. Model tiering and cost control
* **Chosen:** Tier 0 rules, Tier 1 `gemini-2.5-flash-lite` JSON classifier, Tier 2 `gemini-2.5-flash` explainer; caches, budgets, breaker, thinking off.
* **Why it fits:** measured: about half of Phase 1 turns need no model; Phase 3 evaluations used 5 model calls in 51 turns (doc 15).
* **Alternatives (relevant, not formally evaluated):** a single strong model for everything; locally hosted models. No comparison exists in the repository.
* **Trade-offs:** rule drift, two models to monitor, quota sensitivity (Vertex 429 appeared during validation).
* **Production:** quota management, budgets/alerts, a price table maintained by finance, per-feature cost reporting.

## D9. Synthetic inventory data for the POC
* **Problem:** forecasting needs about a year of daily history; the real database has ~5 weeks of mostly test orders and no cost/supplier/region data.
* **Chosen:** a deterministic, labelled generator (`POC_SYNTHETIC`/`POC_REFERENCE`) feeding a real pipeline; accuracy never presented as business accuracy (`docs/adr/ADR-010`).
* **Alternatives (documented):** let Gemini forecast (rejected), Prophet/XGBoost/ARIMA (not adopted), a separate microservice/database (rejected), writing forecasts into the Java database (rejected).
* **Trade-offs:** results demonstrate the pipeline and UX, not demand; scripted situations make the demo findable by construction.
* **Production:** replace the generator with real order/inventory movement history; the mart schema, models, planning rules, tools, API and UI stay.

## D10. Persistent support cases in Java
* **Problem:** a complaint capability needs a real, auditable, owner-scoped record.
* **Chosen:** a new `support` module in the Java backend (system of record), the AI service creates/reads cases through REST; escalation is a separate state from status; the backend snapshots its own evidence (`docs/adr/ADR-011`).
* **Alternatives (documented):** case storage in the AI database, a third-party helpdesk, letting the model decide refunds/escalation, escalation as a status, auto-resolving cases (all rejected).
* **Trade-offs:** several complaint types end in "a person must review"; no live agent/SLA/notifications exist.
* **Production:** a staffed queue, SLAs (as business policy), notification channels, case retention and privacy review, reporting.

## D11. Authorization and audit design
* **Chosen:** Java enforces ownership and roles; the AI service forwards the customer token, adds guards/registry gates/SQL filters as additional layers, signs and audits WRITE confirmations; support events are append-only.
* **Trade-offs:** reliance on a forwarded token means the AI cannot act as a system (no batch/background actions); in-process rate limiting; audit retention undefined.
* **Production:** central policy engine, tamper-evident audit storage, retention and access reviews, token exchange.

## D12. Not creating a separate agent per operation
* **Chosen:** one Customer Agent plus one Inventory supervisor graph; capabilities are tools/nodes (`graph.py:11-12`; ADR-010 decision 6).
* **Why:** an agent per API multiplies prompts, cost and failure modes without a domain boundary; the brief forbade artificial agents.
* **Trade-off:** large dispatch tables (`_ORDER_NODES`, Tier-0 regexes) to maintain.

## D13. Other decisions worth knowing
* **Forward-only Flyway migrations on the real database; Testcontainers/throwaway databases for destructive tests** (`docs/baseline`, `e2e/README.md`).
* **Confirmation by signed server-side token rather than client-supplied arguments** (doc 08).
* **Single-source error contract across Java and Python** (`ApiError` shape).
* **Customer identifiers as HMAC hashes in AI storage** (`jwt.py:40-42`).

## How to defend these in an interview (pattern)
State the constraint (safety, cost, evidence), the decision, the measurable evidence from this repository, then the honest trade-off and what you would change at production scale. Do not claim alternatives were benchmarked unless the repository shows it.
