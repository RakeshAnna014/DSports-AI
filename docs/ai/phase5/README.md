# Phase 5: Product & Supplier Intelligence

Decision support for a buyer or category manager: what to add to the catalogue, where to source it, what it really costs, and whether it is worth selling. Design record: [ADR-014](../../adr/ADR-014-product-supplier-intelligence.md).
Status vocabulary follows [STATUS.md](../../STATUS.md). **Everything that is not connected is reported as unavailable, never simulated.**

## What a buyer can do

Open **Product Intelligence** (`/product-intelligence`, header link for buyers):

| Tab | Purpose |
|---|---|
| Dashboard | Counts from stored records (awaiting review, shortlisted, approved, with offers, comparable offers, margin ≥ 25% estimates, blocked), recent runs, which sources are connected. Empty state explains how to start. |
| Product discovery | Server-side search, filters (status, new product / catalogue gap, source, evidence freshness, demand, supplier risk, supplier offers, margin range, sport/brand) and paging. Detail: identity, catalogue match, evidence with source links, score, offers, comparison, workflow, proposal, history. |
| Suppliers | Directory and "add a supplier" (type, location, brands, brands **with authorisation evidence**, verification). |
| Cost calculator | The deterministic calculator without an opportunity (what-if). |
| Shortlist & approvals | Shortlisted and in-progress opportunities, and whether you may approve. |
| Research runs | Start research; history with per-source status and reasons. |
| Onboarding proposals | Drafts for the catalogue team. |
| Ask a question | Plain-language questions answered from the tools. |

## Data sources and what is real

| Source | Status |
|---|---|
| Customer demand (searches the catalogue could not satisfy) | IMPLEMENTED, real: recorded by the shopping assistant (redacted, hashed). Needs ≥ `SOURCING_DEMAND_MIN_CUSTOMERS` distinct customers to become an opportunity. |
| Buyer-entered products, suppliers, quotations | IMPLEMENTED, real. |
| External feeds (JSON / schema.org JSON-LD, allow-listed hosts) | IMPLEMENTED and unit-tested with a fake transport; **not connected to any real provider** (no feed is configured and none is invented). Configure `SOURCING_FEEDS` + `SOURCING_ALLOWED_HOSTS`. |
| Brand/manufacturer APIs, marketplace APIs, sports event calendars | NOT IMPLEMENTED (no credentials or source). Adding one is a new `Provider` returning `Candidate`s. |
| Inventory AI demand signal | Not used for new products (it forecasts only products with sales history); the score lists it as missing. |
| Exchange rates | Operator-supplied with source and date (`SOURCING_FX_RATES`). Absent = no conversion. |
| Demonstration data | `POST /v1/sourcing/demo/seed` (or the dashboard button): clearly labelled DEMO; never mixed with real rows without the label and filter. |

## Financial model (`app/sourcing/calc.py`)

```
purchase (base)        = unit price at the quantity (highest price-break tier ≤ quantity) × quantity × fx rate
customs value          = purchase + freight               duties = customs value × duty %
non-recoverable taxes  = (customs value + duties) × non-recoverable tax %      (recoverable input tax is NOT a cost)
handling + packaging   = (handling per unit + packaging per unit) × quantity
total procurement cost = purchase + freight + duties + non-recoverable taxes + handling + packaging + other fixed costs
sellable units         = quantity × (1 − defect %)        landed cost per unit = total procurement cost / sellable units
net revenue            = selling price / (1 + tax %) if the price includes tax, else the selling price
gross profit / margin  = (net revenue − landed − variable cost) ; margin = profit / net revenue ; markup = profit / landed cost
break-even net price   = (landed + fixed variable) / (1 − variable %) ;  target price = (landed + fixed variable) / (1 − variable % − target margin %)
```

Worked example (unit-tested and reproduced in the browser test): 500 units × USD 10 at 83, freight 20,000, duty 10%, handling 5 + packaging 2 per unit, other 1,500 → total 483,500, landed **967.00**.
Quantity below the MOQ is `INVALID`; missing quantity, price or a sourced fx rate is `INCOMPLETE` naming what is missing. Assumptions are printed next to every result and stored with each saved snapshot.
**Assumptions to confirm with finance:** duty on cost + freight; freight is a lump sum per order; no landed-cost allocation across multiple SKUs; sensitivity covers freight ±20%, fx ±5%, defect +2 points, quantity +50%.

## Scoring (`app/sourcing/scoring.py`, model `v1`)

Dimensions (default weights): unmet demand 22%, catalogue gap 14%, market evidence 14%, margin 20%, supplier availability 10%, lead time 5%, MOQ exposure 5%, inventory signal 10%. Weights are re-normalised over the
dimensions that have data; override with `SOURCING_SCORE_WEIGHTS`. Outputs: opportunity 0-100, priority, **evidence confidence** (completeness, freshness, independent sources, verified quote), **supplier risk**
(verification, authorisation, expiry, lead time, single supplier) and `INSUFFICIENT_EVIDENCE` when under 35% of the model could be evaluated. A high score never hides LOW confidence or HIGH supplier risk (priority is capped).

## Workflow and roles

`DISCOVERED → UNDER_REVIEW → SHORTLISTED → SUPPLIER_RESEARCH → QUOTE_COMPARISON → APPROVED_FOR_ONBOARDING`, with `REJECTED`, `ON_HOLD`, `NEEDS_MORE_EVIDENCE` reachable from the working states (reason required).
Roles: `PROCUREMENT_BUYER`, `CATEGORY_MANAGER`, `ADMIN` read and prepare; **approving**, reopening a rejection and marking a supplier VERIFIED need `CATEGORY_MANAGER` or `ADMIN`. Approval needs an active entered/verified quote and a
saved margin calculation, and uses the version the user was looking at (a stale version is refused). Grant a role by adding a row to `customer_roles` (there is no role-admin UI); the identity module now accepts both names.

## API (`/ai-api/v1/sourcing`, OpenAPI at the AI service `/docs`)

`GET /dashboard`, `/sources`, `/queue`, `/runs`, `/runs/{id}`, `POST /runs`; `GET|POST /opportunities`, `GET|PATCH /opportunities/{id}`, `POST /opportunities/{id}/status|notes|score|calculate|compare|quotes|proposal`;
`GET|POST /suppliers`, `GET|PATCH /suppliers/{id}`; `PATCH /quotes/{id}`, `POST /quotes/{id}/verify`; `POST /calculator`, `/ask`, `/demo/seed`; `GET /proposals`, `POST /proposals/{id}/review`.
MCP / typed tools (READ only, buyer roles): `get_sourcing_overview`, `search_product_opportunities`, `get_opportunity_evidence`, `get_opportunity_score`, `get_unmet_demand`, `compare_supplier_offers`, `calculate_landed_cost`.

## Security

Server-side role checks on every route and tool (denials audited); strict request models; SSRF-safe fetching (allow-list, redirects re-checked per hop, private addresses refused, connection pinned to the validated address, size/time caps); retrieved text sanitised as data and
never executed; secrets only from environment; per-hour research rate limit; append-only decision log; customer identity never leaves the demand signal (hash only). Known limits: JWT in `localStorage` and the shared
signing secret (see SECURITY.md); roles are granted by SQL; no distributed rate limiting.

## Run and test

```bash
# configuration: ai-service/.env.example (SOURCING_*). Start as usual (e2e/ai/start-backend.sh, e2e/ai/start-ai.sh, npm run dev)
cd ai-service && uv run pytest tests/sourcing -q                                   # unit: calculator, scoring, workflow, providers, URL safety, demand, tools
SOURCING_TEST_DATABASE_URL=postgresql://<user>:<pw>@localhost:5432/<throwaway> uv run pytest -m integration tests/integration/test_sourcing_db.py -q
python e2e/ai/sourcing_e2e.py                                                      # real stack: roles, demand -> opportunity, quotes, comparison, workflow, proposal, restart
python e2e/ai/sourcing_data.py > world.json && node e2e/sourcing-ui.mjs world.json scratch_shots    # real browser
cd frontend && pnpm exec vitest run src/features/sourcing
```

## Known limitations and decisions needed

Exactly one real research source is connected (the Victor Sport official site, badminton only; see the overhaul section below). Everything else about the market is unavailable and says so. No event calendar, so no seasonal opportunities. Duty/tax treatment and the landed-cost allocation rules are assumptions for finance to confirm.
The catalogue has no draft API, so approved products stay as proposals. Quote and supplier data are not shared with any procurement system. The inventory dimension is empty for products DSports has never sold.

## Overhaul: discovery, images, suppliers and data quality (2026-10-15)

Why: the first version could not tell test, demo, buyer, customer-demand and live records apart, had no real source, no pictures and a single blended score. Findings and treatment: [DATA-QUALITY-AUDIT.md](DATA-QUALITY-AUDIT.md). Schema changes are additive and idempotent (`schema_sourcing.sql`); the AI service still never writes the commerce database.

### Provenance
Every product, supplier and quote carries `provenance` (`LIVE_EXTERNAL_SOURCE`, `BUYER_ENTERED`, `CUSTOMER_DEMAND_SIGNAL`, `DEMO_FIXTURE`, `TEST_FIXTURE`, `UNKNOWN_PROVENANCE`). Lists, dashboard, suppliers, scoring, margins and the assistant are **live only** by default; `include_fixtures=true` (UI toggle "Show demo, test and unclassified data") shows the rest, always labelled. A quote inherits fixture status from its supplier or product. A buyer can reclassify with a reason (audited), never into "live external source".

### Product identity and demand gaps
A product record keeps the source's own title (`source_title`), the normalised name, brand (alias table, e.g. "victor sport" gives "Victor"), manufacturer, model, manufacturer SKU, GTIN (checksum validated), sport, category, variant, description, product URL, specifications, images, provenance and timestamps. Nothing is invented: a page with no SKU leaves it empty and the readiness check says so. Catalogue matching returns `EXACT`, `PROBABLE_DUPLICATE`, `SIMILAR`, `REVIEW` or `NONE` with the reason, and **never merges**.
Customer searches that found nothing are **demand gaps** (`record_kind = DEMAND_GAP`): they have no price, supplier, margin or score ("Awaiting product discovery"), cannot be shortlisted, approved or quoted, and a buyer can link one to a real product.

### Research providers
`SOURCING_PROVIDERS` (JSON list), with `SOURCING_ALLOWED_HOSTS` (SSRF allow-list) and `SOURCING_IMAGE_HOSTS`. Implemented and live-verified: `victor_sport`, the manufacturer's own site (robots.txt allows `sitemap_products.xml` and product pages; runs read at most `max_products` pages, spaced out). It yields product name, SKU where shown, the specification table, Open Graph data, images on the brand CDN, and the brand's local distributors (`/page/distributors`, filtered by `supplier_countries`) as supplier candidates, **discovered and not verified**. The pages carry **no prices**, so a margin still needs a supplier quotation. Brand and sport outside Victor/badminton are reported as "not covered", not faked. The generic JSON/JSON-LD feed adapter remains for permitted feeds you supply. No Google Shopping or marketplace API is assumed or used.
A run is asynchronous (`POST /runs` returns 202 `RUNNING`; poll `GET /runs/{id}`; `wait: true` for tests), one identical run at a time, stale runs recovered, ends `SUCCEEDED`, `PARTIAL` or `FAILED` with per-source status, counts, skipped URLs with reasons, and a results list linking each product to its source page. Re-running is idempotent (refreshes, no duplicates).
Safe fetching (`fetch.py`): host allow-list, https only, no credentials or odd ports in URLs, private/loopback/link-local addresses refused, redirects followed by hand with every hop re-checked, size, content-type and time limits, per-host spacing, robots.txt honoured (an unreadable robots file means "do not crawl"). Page text is untrusted: scripts removed, instruction-like text dropped.

### Images
Stored as **references with provenance, not copies**: URL, source page, attribution, a rights note ("linked, not copied"), match level (`EXACT_MODEL`: the product's own page declares it for that model; `LIKELY_MATCH`; `SIMILAR_REFERENCE`; `UNVERIFIED`), and a validation status (`VALID`, `BROKEN`, `BLOCKED`, `UNSUPPORTED`, found by fetching the first bytes and sniffing the type). Cards show the picture, the match label and a placeholder when none is verified; a picture that fails to load in the browser falls back to the placeholder. A buyer can add an image (allow-listed host only, validated before saving), mark the main one, re-check a link, or **report a mismatch** (hidden everywhere, audited). A reported or broken image never counts for readiness.

### Specifications
Canonical keys per product family with a required set per family. Only what a source's own table states is stored; anything else is shown as "not stated", never inferred (no certifications, dimensions or materials are guessed).

### Suppliers
A verification ladder: Discovered, Website checked, Identity verified, Brand authorisation verified (each rung needs a note and optionally an evidence link; the last two need an approver). Quotation received, quotation verified and buyer approval live on the quote. Manual quotation entry is first-class. Brand authorisation is listed only after evidence is recorded.

### Scoring v2 and margins
Separate outputs: opportunity score, evidence confidence, data completeness, supplier risk, financial status (`QUOTED`, `ESTIMATED`, `INCOMPLETE`, `NONE`). A single weak source cannot give high confidence; unknown provenance is capped at low; "Not enough evidence" replaces an invented number. Each saved calculation stores its basis (product, quote, supplier, quantity, currency, price, tax basis, time) and a label (Quoted / Estimated / Incomplete); margin is profit over price and markup profit over cost, labelled as such. The margin KPI counts only margins saved against an active, buyer-entered or verified quote on a live product.

### Approval and onboarding readiness (separate)
Stages: Discovered, Under review, Shortlisted, **Approved as a business opportunity** (approver; needs brand + identifier and evidence), Supplier research, Quote comparison, **Approved for sourcing preparation** (approver; needs an active entered/verified quote and a saved calculation). An independent server-side **readiness** check (name, brand, sport, category, description, identifier, required specifications, a usable image with its source, evidence, chosen quotation, saved calculation, price, quantity) gates the proposal and review: an approved product can still be "not ready". Approval never orders, prices or publishes; there is no catalogue draft API, so proposals stay proposals.

### Screens (`/product-intelligence`)
Dashboard (live counts, excluded fixtures, source status), Product discovery (card grid default, table toggle, filters for status, image, image match, catalogue match, evidence, suppliers, supplier verification, margin, risk, confidence, score, sport/category/brand, demo/test toggle), Customer demand (gaps), Suppliers (ladder and offers), Cost calculator, Shortlist & approvals, Research runs (provider status, counts, results, skipped), Onboarding proposals, Ask a question (live data only). The product page has nine sections: overview with gallery, specifications, market evidence, customer demand, catalogue match, supplier offers, cost and margin, score and its limits, decisions and readiness.

### Configuration
`ai-service/.env.example` (`SOURCING_*`). The local stack's `e2e/ai/start-ai.sh` sets, without touching `.env`: `SOURCING_PROVIDERS=[{"type":"victor_sport","max_products":12,"supplier_countries":["India"]}]`, `SOURCING_ALLOWED_HOSTS=www.victorsport.com,dkjulgymkya8y.cloudfront.net`, `SOURCING_IMAGE_HOSTS=dkjulgymkya8y.cloudfront.net`. Exchange rates still need `SOURCING_FX_RATES` with a source and date.

### Tests and what they prove
`uv run pytest tests/sourcing` (unit: fetching and SSRF, extraction, images, specs, providers on fixtures that mimic the site's structure, workflow, scoring, normalisation); `SOURCING_TEST_DATABASE_URL=… uv run pytest -m integration tests/integration/test_sourcing_db.py` (real PostgreSQL: persistence, idempotency, provenance isolation, migration backfill and reconciliation, calculation association, stages, readiness, ladder, REST roles). `e2e/ai/sourcing_e2e.py` (real stack API), `e2e/ai/sourcing_live_e2e.py` (the **live** run against the real site), `e2e/sourcing-ui.mjs` (real browser, including a live run through the UI). Mocked transports prove parsing and safety rules only; they are not evidence of a live integration, which only the live scripts provide.

### Limits that remain
One live source, and it has no prices: margins exist only after a buyer enters a quotation. Yonex and Nivia product sites were not reachable from the test machine in a usable time, so they are not connected. Victor lists the same product on more than one page; those are flagged, not merged. DNS rebinding between the address check and the connection is not closed (the HTTP client resolves again). Image URLs are hotlinked: the brand's CDN may block or change them, which the UI shows as "could not be loaded". Page text and images are the manufacturer's: they are referenced and attributed, not redistributed, and the rights note says so. Roles are still granted by SQL.
