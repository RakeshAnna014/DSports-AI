# Engineering AI (log monitoring and incident investigation)

Code: `ai-service/app/engineering/`. Depth: [../ai/phase4/README.md](../ai/phase4/README.md), [ADR-013](../adr/ADR-013-engineering-ai.md). UI: `/engineering-ai` (ADMIN).

## 1. Business purpose
Let an engineer ask "why did checkout fail?" or "show recurring errors in the backend in the last 3 days" and get clustered errors, cross-service traces by correlation id and deployment timing, with every claim citing a stored log record and every explanation labelled a hypothesis.

## 2. Entry points
`/v1/engineering/*` (12 operations: investigate, sources, logs, clusters, health, deployments, trace, incidents) and 5 tools (`eng_search_logs`, `eng_error_clusters`, `eng_service_health`, `eng_trace`, `eng_investigate`), all ADMIN. CLI: `uv run python -m app.engineering.cli ingest|docker|deploy|synthetic|ask`.

## 3. Request lifecycle
Question → rule-based parser (`understand.py`: window, services, topics, intent) → bounded typed queries (`repository.py`: 31-day cap, 200 rows, no SQL/path/shell arguments) → deterministic analysis (`analysis.py`, `signature.py`, `incidents.py`: clusters, trend, trace, deployment correlation) → facts plus labelled hypotheses → optional model re-wording (`--llm` / `useLlm`) that is rejected if it cites an unknown record or states an unhedged cause. Ingestion: parsers (`parsers.py`) → redaction at ingest (`redact.py`) → position-hash deduplication (idempotent re-ingest) → `eng_logs`.

## 4. Business rules
Log text is untrusted data: instruction-like messages are withheld, not shown or sent to the model. A source counts as "connected" only if records from it are actually stored (`GET /v1/engineering/sources` lists exactly what is stored). Every record is labelled `real` or `synthetic`. Findings are facts; explanations are hypotheses with a confidence and "to confirm". Deployments are recorded by hand (no CI/CD feed exists).

## 5. Data ownership
`dsports_ai`: `eng_logs`, `eng_deployments`, `eng_incident_status`, `eng_audit`. Reads log files from `ENG_BACKEND_LOG` (default `%TEMP%/dsports_backend.log`) and `ENG_AI_LOG`, and `docker logs` for containers.

## 6. External dependencies
Local log files, the Docker CLI (infrastructure logs), optionally Gemini for wording. **Not connected:** browser/frontend logs, a central log platform, metrics or traces.

## 7. Security and authorization
ADMIN only, enforced on the server for REST and tools (observed: customers, support and planners 403). Every investigation and every denial is audited without log content. Redaction (tokens, passwords, e-mail addresses) happens before storage, so even administrators and the model never see raw secrets.

## 8. Failure handling and observability
Model unavailable: the deterministic summary is shown and says so. Re-ingesting is safe. Limits: the AI service's own log file is overwritten at each restart, so ingest before restarting.

## 9. Run and test
```bash
cd ai-service
uv run python -m app.engineering.cli ingest --service backend      # real backend log
uv run python -m app.engineering.cli synthetic                     # the labelled test incident
uv run pytest -q tests/engineering ; uv run pytest -m integration tests/integration/test_engineering_db.py
uv run --project ../ai-service python ../e2e/ai/engineering_e2e.py   # real stack
```

## 10. Implemented / partial / missing
Implemented: ingestion, redaction, clustering, trace, deployment correlation, investigation, UI, audit. Partial: evaluation uses one synthetic incident (proves the pipeline against a known cause, not accuracy on unknown real incidents). Missing: frontend log collection, central platform, alerting, a dedicated ENGINEER role.

## 11. First files to read
`engineering/ingest.py`, `redact.py`, `signature.py`, `understand.py`, `investigate.py`, `analysis.py`, `safe.py`.
