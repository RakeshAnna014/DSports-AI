# Documentation coverage report

Created 2026-10-13 for the learning guide in `docs/learning-guide/`. This is a documentation-only task: no application code, tests, expectations, databases or artifacts were changed (the only non-documentation additions are two explanatory notes and a few documentation corrections listed in section 5). Nothing was committed.

## 1. How the repository was inspected

Method: repository inventory (directory structure, module/package list with line counts), then reading the real source of every AI execution path end to end, running two read-only programs against the code (`build_registry()` to list the 39 registered tools, and `get_graph().draw_mermaid()` on the compiled LangGraph graphs), reading evaluation result JSON files, validation logs and existing documentation, then writing the guide, then an **automated audit** (`verify_docs.py`, run from the scratchpad, not added to the repository) that checks every internal link, every `file:line` reference (file exists, line range within the file, cited symbol present in the range) and scans the guide for secrets, tokens, e-mail addresses and project identifiers.

Audit result (final run, see section 8): all internal links resolve, all source references resolve to existing files with valid ranges, all symbol-bearing references contain the cited symbol in the cited range, and no secret-like strings, tokens, e-mail addresses or cloud project identifiers appear in the guide.

## 2. What was inspected, by area

| Area | Fully read | Partly read / by structure only | Not inspected |
|---|---|---|---|
| Python `ai-service/app` | `main`, `services`, `errors`, `config/settings`, `auth/jwt`, `api/routes`, `llm/*`, `agent/{graph,runtime,service,schemas,prompts,shopping,support,response_kind}`, `router/{intents,tier1}`, `tools/*` (all 9 files), `mcp/server`, `guard/*`, `rag/{ingest,chunking,tika,retrieval,embedder}`, `store/*` + SQL, `obs/{logging,context,usage,metrics,middleware}`, `shop/{catalog,search,ranking}`, `inventory/{forecasting,planning,scenarios,events,pipeline,grounding,understand,tools,api,quality,schema_inventory.sql}`, `support/{model,classify,resolve,respond}` | `agent/orders` (lines 1-150, 150-320 read), `agent/recommend` (130 of 226 lines), `router/tier0` (head and support routes), `support/flow`, `support/investigate` (most), `inventory/copilot` (head, nodes `overview`/`stock_risk`, graph, service), `inventory/generator` (head 90 of 321), `inventory/repository` (about 80 of 532 lines) | `agent/{templates,product_context,offers,suggestions}`, `router/{extract,periods}`, `shop/cards`, `rag/{products,reindex}`, `inventory/{repository (rest), cli}`, `app/cli`, `cache` |
| Java backend | `SecurityConfig`, `JwtWebFilter`, `JwtTokenProvider`, `LoginUseCase`, `PublicCatalogController` + `GetProductsUseCase` + adapter `findAll`, `ReturnController` (part), `ReturnPolicy`, `CreateReturnUseCase`, `ReactiveTransactionRunner`, `application.yml`, `SupportModuleConfig`, all `support` domain/use-case/controller files except the R2DBC adapter body and request/response records, V44, V35 and V40 fragments, `StaffSupportController` analytics | `GlobalExceptionHandler` (head and support map), `PublicOrderController` (part), controller route table for all modules (by script) | `order`, `payment`, `shipment`, `review`, `wishlist`, `notification`, `inventory`, `pricing` internals; `outbox/*`, `BusinessMetricsListener`; migrations other than V35/V40/V44 (table names listed by script only) |
| Frontend | `routes`, `App`, `api/{client,ai,support,errors,inventoryAi}`, `store/{aiStore,authStore}`, `features/ai/{MessageList,AssistantPanel,ResponseExtras,ConfirmActionDialog,AiShopPage}`, `AiCommerceSearch` (80 of 150), `support/{SupportChatBlock}`, `SupportApp` (90 of 293), `inventory/{InventoryApp,Copilot}` (130 of 130 lines for the parts read) | `package.json` (grep), `vite.config.ts` | cards/tables/blocks components, `MyCasesPage`, `parts.tsx`, `charts`, `tabs`, `blocks`, order/payment/checkout pages, `types/*` |
| Tests | test **names** of the main Python suites (agent, tools, guards, auth, observability, support, MCP, live) and Java support tests; counts of `def test_` for all Python files; Java per-module counts from the validation log | assertions inside most tests were not read | bodies of most tests |
| Evaluation and validation | result JSON `metrics` of support dev/holdout files, dataset hashes, `validate-all.sh` (Phase 3 and structure), summary/logs of the Java and consolidated runs, Phase 1 and Phase 2 evaluation/stabilisation docs | `run_support_eval.py` (structure and tail) | `run_eval.py`, `run_inventory_eval.py`, `eval/*.yaml` datasets in detail |
| Configuration and ops | `docker-compose.yml`, `.env.example` (non-secret lines), `init-ai-db.sql` | `e2e/README.md` | `Dockerfile`, `ROADMAP.md`, `.github` (empty), corpus document contents |

Because of the "not inspected" column, **exhaustive coverage is not claimed**. The five required journeys and the AI-critical paths (routing, LLM calls, tools, MCP, RAG, forecasting, support workflow, case lifecycle, security, evaluation) were traced from source.

## 3. Coverage of required topics

| Required topic | Document(s) | Evidence used | Incomplete / uncertain |
|---|---|---|---|
| Master guide | 00 | all | |
| Business context | 01 | `AGENTS.md`, ADR-009..011, phase docs | no business-impact data exists |
| System architecture, 8 diagram types | 02 | compose, settings, routes, code | container path of the AI service not verified in recent runs (UNKNOWN); a Cloud Run configuration does not exist |
| Java backend | 03 | security, catalog trace, returns, support, config | order/payment/shipment internals not read (summarised from controllers, migrations, docs) |
| Frontend | 04 | routes, clients, stores, assistant/support/inventory components | many presentational components not read; no unit tests exist |
| Python AI service + graphs generated from code | 05 | source + `draw_mermaid` output | internal sub-steps of nodes are not graph nodes (documented) |
| LLM, prompts, routing | 06 | source; three traced LLM calls; five call sites found by search | Vertex data-handling terms UNKNOWN |
| RAG, Tika, OCR, embeddings, pgvector | 07 | source, schema, live test names | OCR accuracy on real documents UNKNOWN; one scanned sample |
| Tools and MCP | 08 | registry run, source, tests | MCP over network used only by tests |
| Phase 1 | 09 | source, Phase 1 docs and evaluation | MRR inconsistency between two reports (see section 4) |
| Phase 2 | 10 | source for every algorithm | accuracy numbers are from the stabilisation report on synthetic data |
| Phase 3 | 11 | source, tests, evaluation JSON | |
| Database | 12 | schemas V44/V35/V40, AI SQL | other Java tables listed by name only; ERDs drawn only from read schemas |
| Security and reliability | 13 | code + tests | pentest not performed |
| Testing and evaluation | 14 | logs, JSON, docs | no single clean consolidated run exists (stated) |
| Observability, performance, cost | 15 | code + recorded reports | no Java latency or load measurements |
| Decisions | 16 | ADRs, code | alternatives marked "not formally evaluated" where no evidence |
| Source index | 17 | file listing with line counts | some Java module files summarised at module level |
| Manager guide | 18 | all | |
| Interview bank (110 questions) | 19 | all | |
| Glossary and roadmap | 20 | all | |
| Journeys A-E | 21 (and 04, 07, 11) | source | |
| Consolidated guide | `DSports-AI-Complete-Learning-Guide.md` | modular docs | |

## 4. Conflicting or outdated documentation found (not silently reconciled)

| # | Conflict | Where | Resolution in this guide |
|---|---|---|---|
| C1 | Phase 1 docs say `create_support_case` is not implemented and asking for a ticket gets a "not available" answer | `docs/ai/05-customer-agent.md:65-66`, `07-mcp-tools.md:26`, `13-e2e.md:89`, `14-structured-responses-and-ux.md:32` | Outdated by Phase 3 (ADR-011); code and tests are authoritative: four support WRITE tools exist. **Original docs left unchanged.** |
| C2 | Stale code: `support_case()` and the module docstring in `ai-service/app/agent/support.py:1-2,41-46` still describe "no case/ticket creation"; the function is unreferenced | code | recorded in doc 05 as dead code; not changed (documentation-only task) |
| C3 | Migration range in an architecture overview says V1-V42 | `docs/ai/01-architecture.md:55` | code has V1-V44; the guide uses V44 |
| C4 | ADR-009 status text says "Implementation starts in Phase B; nothing of the service exists yet" | `docs/adr/ADR-009-python-ai-service.md:5` | the service exists; outdated status text; left unchanged |
| C5 | Retrieval MRR: 1.00 in the Phase 1 evaluation doc versus 0.94 in the Phase 2 stabilisation report for the same 83-case set | `docs/ai/09-evaluation.md`, `docs/ai/phase2/14-final-stabilization-report.md` section 7 | cannot be reconciled from the repository; doc 07 reports both and treats the later one as the later measurement |
| C6 | MCP `propose_action` description names only `create_return, reorder_product`, but six WRITE tools exist | `ai-service/app/mcp/server.py:94` | documented in doc 08; behaviour correct, text stale |
| C7 | Phase 3 result JSON files copied from re-runs carry the label `FIRST RUN (unseen)` although they are regression runs | `ai-service/eval/results/support-holdout-REGRESSION-*.json` | explained in the new `SUPPORT-RESULTS-NOTE.md` and docs 14; files not edited |
| C8 | `eval/support/holdout.yaml` hash differs from the recorded hash; file untracked in git | `eval/results/support-holdout.sha256` | documented in doc 14 and the note |
| C9 | My own earlier Phase 3 docs contained inaccuracies found by this audit: analytics metrics (first-response/resolution time do not exist), `support_cases` column names, support error-code names, a looser lifecycle diagram | `docs/ai/phase3/09`, `02`, `03` | **corrected in place on 2026-10-13**, each with a dated correction note |
| C10 | Earlier plan and docs mention counts that later changed (operations, test totals) | various | the guide cites the latest recorded numbers |

## 5. Changes made outside `docs/learning-guide/` (all documentation-level)
1. `docs/ai/phase3/09-audit-and-observability.md` (corrected analytics metric table), `02-case-data-model.md` (corrected column and error-code names), `03-case-lifecycle.md` (exact transition table), `10-evaluation.md` (label caveat).
2. `ai-service/eval/results/SUPPORT-RESULTS-NOTE.md` (new explanatory note).
Earlier in the same session (before this documentation task) Phase 3 work changed code and tests; those changes are reported in `docs/ai/phase3/14-final-report.md`.

## 6. Undocumented-but-important code and unverified claims
* Recommendation kits data (`ai-service/app/agent/kits.json`, generated from the catalogue spec) is described but its content was not read.
* The outbox dispatcher/handlers and `BusinessMetricsListener` in Java were not inspected.
* Unverified (UNKNOWN): Docker image execution of the AI service, real OCR accuracy, HNSW recall, Vertex data-handling terms, billing reconciliation, the final count of OpenAPI operations, whether `ROADMAP.md` matches `AGENTS.md`, any Phase 1 browser-journey intermittency beyond what is recorded.
* Not run by this task: tests, evaluations, builds (the task was documentation-only; existing recorded results are cited).

## 7. Synthetic vs real data check
Every statement about inventory data, forecasts, costs, suppliers, regions and events is labelled `SYNTHETIC / POC DATA`; catalogue/prices/stock are described as real DSports data (demo catalogue); payments as a mock; documents as samples. No result is presented as real-world accuracy.

## 8. Final audit run (2026-10-13)

`verify_docs.py` over the 24 Markdown files in `docs/learning-guide/` (22 numbered/consolidated documents plus this report, counted as the script's file list): **538 source references checked, 0 problems** (all internal links resolve; every referenced file exists; every line range lies within its file; every reference that names a symbol contains that symbol inside the cited range). The first run found 10 problems (links to files not yet written, `...` path elisions, and 6 class-level symbols attached to method ranges or an off-by-one); all were fixed and the script was re-run until clean. A random sample of 85 references without a symbol was inspected by hand: in every case the first line of the cited range was the intended definition or statement.

Secret/identifier scan over the same files: no private keys, API keys, JWTs, credential assignments, connection strings with passwords, e-mail addresses, cloud project identifiers or local user names. (The sample customer name "Priya" appears only inside example refusal phrases taken from the demo/evaluation scripts; it is not personal data.)

Limits of the audit: it proves references are structurally valid and symbol-consistent, **not** that every sentence is true; statements were checked against the code as read (section 2). The audit script lives in the session scratchpad and was not added to the repository.
