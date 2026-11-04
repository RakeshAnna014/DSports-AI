# ADR-013: Engineering AI (log monitoring and incident intelligence) as a bounded, deterministic-first capability

Status: accepted (Phase 4)

## Context

Developers and support engineers need to investigate failures across the Java backend, the Python AI service and the infrastructure. The repository
has no centralized logging platform: the backend writes plain text (with a `[correlationId]` field) to stdout, the AI service writes redacting JSON
lines to stdout, the frontend logs only to the browser console, and there is no CI/CD feed of deployments. Logs contain text we do not control
(request data, third-party messages) and may contain secrets or personal data.

## Decisions

1. **No new vendor.** The smallest practical POC is a table (`eng_logs`) in the existing AI database, filled by deterministic ingesters for the
   formats this repository produces (Spring text, AI JSON lines, `docker logs`). Production platforms (Loki, Elasticsearch, Cloud Logging) are a
   replaceable source behind the same repository interface; their configuration is documented, not assumed.
2. **Redact at ingest, not at query.** Secrets, tokens, connection strings, e-mail, phone, card-like numbers and customer ids are removed before a
   record is stored, so no query, tool or LLM can ever see them. Correlation ids are kept: they are needed and are not personal data.
3. **Deterministic first, model last.** Ingestion, filtering, error signatures, clustering, trend, deployment and cross-service correlation, and the
   fact/hypothesis split are code. A model may only re-word the finished evidence; its text must cite record ids that were retrieved and must not
   state an unhedged cause, or it is discarded for the deterministic summary (`check_claims`).
4. **Facts versus hypotheses is structural.** Findings are counts, times and chains. Explanations are `HYPOTHESIS` objects with LOW/MEDIUM confidence
   (never HIGH) and a statement of what would confirm them. Deployment correlation reports timing, never causation.
5. **Logs are untrusted input.** Any instruction-like pattern in a message causes the whole message to be withheld (metadata kept). Tools have no
   free-form argument (no SQL, path, shell or table); a log line cannot select or run a tool because tools are chosen by the caller.
6. **Access.** Every REST route and every registry tool requires the ADMIN role, enforced server-side; denials and investigations are audited
   without log content. Synthetic records carry `origin='synthetic'` and are labelled everywhere they appear.
7. **Separate module.** `ai-service/app/engineering/` shares the service, tool registry, MCP facade and auth with Customer AI and Inventory
   Intelligence but nothing else; no commerce data is read or written.

## Alternatives considered

* Adopt Loki/ELK now: rejected for the POC (new infrastructure and vendor); the interface keeps it possible.
* Let the LLM write queries over logs: rejected (unbounded access, injection surface).
* Redact only when answering: rejected (the store itself would hold secrets).
* An LLM-based clusterer: rejected; normalised signatures are explainable and reproducible.

## Consequences

* Answers are only as good as what is logged and ingested; anything unlogged is unknown (stated in every answer).
* Ingestion is pull-based (CLI/cron), so freshness depends on how often it runs.
* The signature normaliser and category rules are heuristics; duplicate-defect candidates are suggestions for a person to confirm.
