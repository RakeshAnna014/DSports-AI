# Phase 4: Engineering AI (log monitoring and incident intelligence)

Design record: [ADR-013](../../adr/ADR-013-engineering-ai.md). Code: `ai-service/app/engineering/`. UI: `/engineering-ai` (ADMIN only).

## 1. Which log sources are actually connected

| Source | Connected? | How | Notes |
|---|---|---|---|
| Java backend (Spring Boot) | **Yes** | `cli ingest --service backend` reads the stdout log file (`ENG_BACKEND_LOG`, default `%TEMP%/dsports_backend.log`) | Plain text, `[correlationId]` in every line; multi-line stack traces/SQL are attached to their record; DEBUG is skipped by default |
| Python AI service | **Yes** | `cli ingest --service ai-service` reads the JSON-lines stdout log (`ENG_AI_LOG`) | Has `correlationId`, request status and latency; uvicorn's own plain lines are counted as unparsed, not guessed |
| PostgreSQL / Tika containers | **Yes** (postgres verified) | `cli docker --container dsports-postgres --service postgres` (`docker logs --timestamps`) | Infrastructure level only |
| Frontend (browser) | **No** | n/a | The app logs to the browser console only; no collector exists. Needs a client-error endpoint or a browser SDK |
| Deployments | **Manual** | `cli deploy --service S --version V` or `POST /v1/engineering/deployments` | There is no CI/CD in the repo to read from; a feed (GitHub Actions) would call the same endpoint |
| Metrics / traces | **Partly** | The AI service exposes Prometheus metrics (`/metrics`); Java has Actuator | Not ingested here: logs only. There is no tracing stack, so correlation uses correlation ids; `trace_id` is stored if a log carries one |
| A central platform (Loki/ELK/Cloud Logging) | **No** | n/a | None exists in this repository; replace the ingesters, keep the repository interface |

`GET /v1/engineering/sources` lists exactly what is stored per service, origin and source, with counts and time range.

## 2. Capabilities implemented

1. **Visibility**: parse, redact, deduplicate (position-based hash, idempotent re-ingest) and store; filters by time window, service, level, text terms,
   correlation id, HTTP status; every record labelled `real` or `synthetic`.
2. **Natural-language investigation** (`POST /v1/engineering/investigate`, `cli ask`): a rule-based parser turns the question into a window, services,
   topics and an intent (investigate, clusters, search, health, trace); retrieval happens before any model is involved; facts cite `[L<id>]`.
3. **Clustering and defect intelligence**: normalised error signatures (ids, numbers, timestamps, line numbers removed; root-cause exception chosen),
   counts, first/last seen, change versus the previous equal window, duplicate-defect candidates (never auto-merged).
4. **Incident investigation**: cross-service correlation by correlation id (first error, services crossed), dependency classification (database,
   timeout, auth, LLM, downstream, validation), deployment correlation (errors first seen after a release, in minutes), trend direction,
   hypotheses with confidence and "to confirm", next steps and limitations.
5. **Operational insights**: per-service health (errors, warnings, 5xx) derived from logs, trend of error volume. These are derived observations,
   not alerts; no alerting system exists and none is claimed.
6. **Security**: ADMIN-only REST and tools, audit, ingest-time redaction, untrusted-log handling (message withheld on instruction-like text),
   bounded typed filters (31-day cap, 200 rows), no SQL/shell/file arguments.
7. **Optional AI wording** (`useLlm`): Gemini re-words the evidence; the output is rejected if it cites an unknown record or states an unhedged cause.

## 3. Use it

```text
cd ai-service
uv run python -m app.engineering.cli ingest --service backend      # real backend log
uv run python -m app.engineering.cli ingest --service ai-service
uv run python -m app.engineering.cli docker --container dsports-postgres --service postgres --since 72h
uv run python -m app.engineering.cli deploy --service backend --version v1.2.3
uv run python -m app.engineering.cli synthetic                     # the labelled test incident
uv run python -m app.engineering.cli ask "Why did checkout fail?" [--origin synthetic] [--llm]
```

REST (ADMIN token): `POST /v1/engineering/investigate`, `GET /sources|/logs|/clusters|/health|/deployments|/trace/{correlationId}`, `POST /deployments`.
Tools in the shared registry (and MCP): `eng_search_logs`, `eng_error_clusters`, `eng_service_health`, `eng_trace`, `eng_investigate`.

## 4. The demonstrated incidents

**Synthetic (clearly labelled)**: "checkout DB-pool exhaustion after a release": backend v2.4.0 deploys; 5 minutes later payment capture fails with R2DBC pool
timeouts and HTTP 500, the AI service logs the downstream failure under the same correlation id; unrelated noise, a secret, an e-mail and a
prompt-injection attempt are included. Start to finish (`e2e/ai/engineering_e2e.py`): search → top cluster (database, 506 occurrences) → deployment
correlation (first seen 5 minutes after v2.4.0, timing only) → trace across backend and AI service → summary with citations and unconfirmed hypotheses.

**Real**: the recurring warning "Could not expire unpaid order …" (a scheduled job retrying two orders whose payment is being captured) was found in the
real backend log with exactly the count obtained by counting the raw file, with a LOW-confidence hypothesis labelled as such. The only ERROR in the
real Postgres log is the "database already exists" caused by the test-database creation during this work, correctly reported as an isolated record.

## 5. Tests and results (2026-10-14)

| Check | Command | Result |
|---|---|---|
| Engineering unit tests (parsers, redaction, signatures, role gate, question parsing, analysis rules, claim guard) | `uv run pytest tests/engineering` | 51 passed |
| Engineering integration tests (real PostgreSQL) | `AI_TEST_DATABASE_URL=… uv run pytest tests/integration/test_engineering_db.py -m integration` | 18 passed |
| AI service regression | `uv run pytest -m "not integration and not live"` | 1209 passed |
| Evaluation vs the incident's ground truth | `AI_TEST_DATABASE_URL=… uv run python eval/engineering/run_eval.py` | 13/13 checks: question understanding 8/8, retrieval precision 1.0 / recall 1.0, citations 155/155 valid, 5/5 unsupported claims caught, 2/2 good outputs accepted, no injected text, secret or PII in store or responses |
| Live end to end (real service, real JWTs, real database, real Gemini summary) | `uv run --project ai-service python e2e/ai/engineering_e2e.py` | 23/23 |
| Frontend page | `pnpm exec vitest run src/features/engineering` | 5 passed |

Test areas required by the brief: time-window filtering, service filtering, correlation-id search, duplicate-error grouping, incident summaries,
citations of real records, prompt injection in log messages, unauthorised access (guest 401, customer 403, support executive 403), unsupported
root-cause claims.

## 6. Limitations (honest)

* **Frontend and any production logging platform are not connected**; deployments are recorded by hand. Metrics and traces are not ingested.
* Ingestion is pull-based; the AI service's own log file is overwritten at each restart, so older AI-service lines are gone unless ingested before.
* Evaluation uses one synthetic incident. It proves the pipeline against a known cause; it does not measure accuracy on unknown real incidents.
* Category and duplicate rules are heuristics over text; hypotheses are LOW/MEDIUM by design and need a human to confirm.
* The question parser is rule-based: unusual phrasings fall back to a general investigation over the default 24-hour window.
* The AI-written summary depends on Gemini availability and quota; if unavailable the deterministic summary is shown (and says so).
* Access is the existing ADMIN role; a dedicated ENGINEER role would be a change in the identity module.
