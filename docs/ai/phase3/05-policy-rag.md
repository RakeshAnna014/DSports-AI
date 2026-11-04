# 5. Policy and knowledge RAG

Reuses the Phase 1 pipeline unchanged: Apache Tika (+ Tesseract when a scan has no text) → heading-aware chunking → `text-embedding-005` → pgvector in the separate `dsports_ai` database, hybrid vector + full-text retrieval.

## Documents

| Location | Type | Audience | Files |
|---|---|---|---|
| `ai-service/corpus/` | customer procedures | customers | missing-delivery-procedure, damaged-or-wrong-product-procedure, payment-problems-procedure, exchange-policy, support-cases-guide (plus the Phase 1 policies) |
| `ai-service/corpus_support/` | `sup_procedure` | **staff only** | support-triage-sop, support-escalation-matrix |

**Every document is a labelled demo/sample document.** Windows, time frames and eligibility in them are demonstration values and are never presented as an official DSports policy or SLA.
Where no document answers, the assistant says it has no policy information; it does not fill the gap.

## Isolation of internal documents

`retrieval.py` holds a fixed map of internal document-type prefixes (`inv_` for Phase 2, `sup_` for Phase 3). For a customer request those are excluded **in the SQL filter**, not after
retrieval; an unknown prefix fails closed. Tested against the real database (`tests/integration/test_support_documents_db.py`, 5 tests) and in the evaluation (internal text must never appear).

## Citations

A policy statement carries its source document and section; the passage shown is chosen by section heading (`best_passage`) so the answer quotes the relevant rule. Retrieved text is
treated as untrusted data (delimited, scanned for instructions) as in Phase 1.

## Ingest

```
cd ai-service
uv run python -m app.support.cli ingest-corpus      # corpus_support (internal)
uv run python -m app.cli ingest-corpus               # corpus (customer)
```
Idempotent by content hash.
