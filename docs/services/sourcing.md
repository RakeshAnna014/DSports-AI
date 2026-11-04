# Product & Supplier Intelligence (sourcing)

Code: `ai-service/app/sourcing/`. Depth: [../ai/phase5/README.md](../ai/phase5/README.md), [DATA-QUALITY-AUDIT](../ai/phase5/DATA-QUALITY-AUDIT.md), [ADR-014](../adr/ADR-014-product-supplier-intelligence.md). UI: `/product-intelligence`.

## 1. Business purpose
Help a buyer or category manager decide which products to add, where they might be sourced, what they would really cost, and whether they are worth selling, while keeping what is known, estimated, entered or demonstrated strictly apart.

## 2. Entry points
`/v1/sourcing/*` (37 operations: dashboard, sources, queue, runs, opportunities, suppliers, quotes, proposals, calculator, ask, demo seed) and 7 read-only tools (`get_sourcing_overview`, `search_product_opportunities`, `get_opportunity_evidence`, `get_opportunity_score`, `get_unmet_demand`, `compare_supplier_offers`, `calculate_landed_cost`).

## 3. Request lifecycle
*Discovery*: `POST /runs` (202, asynchronous, one identical run at a time) → providers (`providers.py`, `victor.py`, `typed_providers.py`) fetch only through `SafeFetcher` (`fetch.py`) → extraction and normalisation (`extract.py`, `normalize.py`, `specs.py`, `images.py`) → records stored with provenance (`repository.py`). *Evaluation*: a buyer enters or verifies a quotation → `calc.py` (landed cost, margin, break-even) → `scoring.py` (opportunity, evidence confidence, supplier risk, completeness, financial status) → `workflow.py` (state machine and approver checks) → a proposal for the catalogue team.

## 4. Business rules
* Landed cost, margin, break-even and scores are deterministic; a missing input makes the result `INCOMPLETE` naming what is missing; a foreign-currency figure is converted only with an operator-supplied, dated rate (`SOURCING_FX_RATES`).
* Every record has provenance (`LIVE_EXTERNAL_SOURCE`, `BUYER_ENTERED`, `CUSTOMER_DEMAND_SIGNAL`, `DEMO_FIXTURE`, `TEST_FIXTURE`, `UNKNOWN_PROVENANCE`); lists, scores, margins and assistant answers use live provenance only unless the buyer asks for demo or test data.
* A customer search that found nothing is a **demand gap**, never a product: it cannot be scored, quoted or approved.
* Product identity (brand, model, SKU, GTIN, specifications, images) comes only from a source or a buyer; an image is an attributed reference with a match level, never proof of identity. Supplier discovery is not verification: verification is a ladder with recorded evidence.
* Workflow: `DISCOVERED → UNDER_REVIEW → SHORTLISTED → SUPPLIER_RESEARCH → QUOTE_COMPARISON → APPROVED_FOR_ONBOARDING` plus `REJECTED`, `ON_HOLD`, `NEEDS_MORE_EVIDENCE`. Business approval, sourcing-preparation approval and catalogue readiness are three separate things; approving never publishes, orders or prices. Approving needs `CATEGORY_MANAGER` or `ADMIN`, an active entered or verified quote, a saved margin calculation, and the version the user saw.
* Real, connected research sources: exactly one (the Victor Sport official site, which publishes no prices), so margins exist only after a buyer enters a quotation. Mock and fixture providers prove parsing and safety rules only.

## 5. Data ownership
`dsports_ai.src_*`. The service reads the catalogue only through Java's REST API and never writes commerce data. Customer identity never enters the demand signal (hash only).

## 6. External dependencies
Allow-listed public hosts (`SOURCING_ALLOWED_HOSTS`, `SOURCING_IMAGE_HOSTS`), Java REST for catalogue matching, optional Gemini for the question box wording.

## 7. Security and authorization
Roles `PROCUREMENT_BUYER`, `CATEGORY_MANAGER`, `ADMIN` per route on the server (observed: customers, support and planners 403; buyer, category manager and admin 200); approvals and VERIFIED marks need an approver; every decision is appended to the audit log. **SSRF controls** in `fetch.py` / `normalize.check_url`: host allow-list, `https` only, no user-info or odd ports, every resolved address must be public (IPv4-mapped IPv6 included), redirects followed by hand with every hop re-checked, the connection is pinned to the validated address (`PinnedTransport`) so a DNS change cannot redirect it, size, content-type and time caps, robots.txt. Retrieved page text is sanitised and treated as data. Tests: `tests/security/test_ssrf_and_hostile_sources.py` (64) and `tests/sourcing`.

## 8. Failure handling and observability
A run ends `SUCCEEDED`, `PARTIAL` or `FAILED` with per-source status and skipped-URL reasons; stale runs are recovered; a rate limit applies per hour (`SOURCING_RUNS_PER_HOUR`). Sources that are not connected are reported as unavailable, never simulated.

## 9. Run and test
Configuration `SOURCING_*` (see `ai-service/.env.example`); `e2e/ai/start-ai.sh` sets the single real provider. Tests: `uv run pytest -q tests/sourcing tests/security`, `uv run pytest -m integration tests/integration/test_sourcing_db.py`, real stack `e2e/ai/sourcing_e2e.py` and `sourcing_live_e2e.py`, browser `node e2e/sourcing-ui.mjs`.

## 10. Implemented / partial / missing
Implemented: demand gaps, buyer-entered products/suppliers/quotes, one live source, landed-cost calculator, scoring v2, workflow, proposals, UI. Partial: one real source only; no event calendar; the catalogue has no draft API so approved products remain proposals. Missing: marketplace and manufacturer APIs, a procurement-system link.

## 11. First files to read
`sourcing/fetch.py`, `normalize.py`, `calc.py`, `scoring.py`, `workflow.py`, `repository.py`, `api.py`.
