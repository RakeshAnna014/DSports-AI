# ADR-014: Product & Supplier Intelligence (Phase 5) as a buyer-controlled, evidence-first capability in the AI service

Status: accepted (Phase 5)

## Context

Customer AI helps shoppers; Inventory AI forecasts and plans stock of products DSports already sells. Neither helps decide what to add to the catalogue, where to source it, what it really costs
or whether it is worth selling. The repository has no procurement or supplier module (the only "supplier" values are POC reference data inside Inventory AI), no market-research source, and
Customer AI does not persist what customers searched for and could not find.

## Decisions

1. **Ownership.** Discovery runs, evidence, opportunities, suppliers, quotes, calculation snapshots, score snapshots, decisions and onboarding proposals live in the AI service's own database
   (`src_*` tables, `ai-service/app/sourcing/`), following the Engineering AI and Inventory AI pattern. The Java backend keeps the catalogue, prices, orders and payments. The AI service reads the catalogue
   only through its public REST API and **cannot** write commerce data: there is no code path from this module to a product, price or order write. There is no existing supplier module to reuse or duplicate;
   if procurement is built in Java later, suppliers and quotes migrate behind the same repository interface.
2. **Deterministic numbers, no LLM arithmetic.** Landed cost, margin, markup, break-even and target price (`calc.py`, `Decimal`) and opportunity scoring (`scoring.py`, versioned) are plain code with tests.
   Missing inputs make a result `INCOMPLETE`; impossible inputs make it `INVALID`; a foreign-currency figure with no operator-sourced, dated exchange rate is never converted. Questions are answered by typed
   tools and worded from their results with templates, so nothing can be invented.
3. **Separate dimensions.** Opportunity score, evidence confidence and supplier risk are three outputs. Too little data gives `INSUFFICIENT_EVIDENCE` and no number.
4. **Provenance everywhere.** Every opportunity, supplier and quote has an `origin` (LIVE_RESEARCH, DEMAND_SIGNAL, MANUAL, DEMO) and every quote a `source_kind` (PUBLIC_LISTING, QUOTE_ENTERED, VERIFIED,
   AI_ESTIMATE). A public listing and an AI estimate can never be verified; editing the figures of a verified quote voids the verification. Demonstration rows are labelled in data and in the UI.
5. **Permitted sources only.** External research goes through allow-listed feed adapters (JSON, schema.org JSON-LD). Hosts must be configured; the URL check refuses non-http(s), embedded credentials, non-standard
   ports, unlisted hosts and any host resolving to a private, loopback, link-local or reserved address; redirects are not followed; size and time are capped; retrieved text is sanitised as untrusted data. With no
   source configured a run reports that source as `unavailable` and ends `PARTIAL`: it never substitutes fake results.
6. **Customer demand without personal data.** Unmet searches are recorded as (salted customer hash, redacted phrase, count). A search is unmet when nothing was returned, or when a distinctive term appears in no
   returned product and nowhere in the catalogue vocabulary. The buyer UI says it is interest, not purchase intent, and shows no identity.
7. **Workflow and roles.** Statuses are a graph, not a pipeline (rejection, hold and "needs more evidence" are always possible). New roles `PROCUREMENT_BUYER` and `CATEGORY_MANAGER` were added to the Java identity
   `UserRole` enum (the mapper uses `valueOf`, so an unknown stored role would have broken login). Buyers and approvers read and prepare; approving, reopening a rejection and VERIFIED supplier status need an approver
   (`CATEGORY_MANAGER` or `ADMIN`). Approval requires an active entered/verified quote and a saved margin calculation. Every decision is appended to `src_decisions` with the actor hash, time and reason.
8. **Approval is not authority.** Approval for onboarding records a recommendation. The onboarding proposal is a persisted draft (the catalogue has no draft API) that lists missing mandatory fields and states that it
   does not create, publish or price a product or authorise a purchase. The AI tools are READ-only; there is no tool to start research, change a status, approve, contact a supplier, order, publish or price.
9. **Cost control.** Research runs are deduplicated while running (unique index), rate-limited per hour, and stale RUNNING rows are failed so a crash cannot block a search for ever.

## Consequences

- Real market research needs a permitted feed. Until `SOURCING_FEEDS` is configured the module works from customer demand and buyer-entered offers and says so.
- Seasonal and sporting-event opportunities are not shown: no event calendar source exists.
- Inventory AI signals exist only for catalogue products, so the inventory dimension is reported as unavailable for new products rather than invented.
- Quote and supplier records are per-AI-database; a shared procurement system would need a migration plan.

## Addendum (2026-10-15): provenance, demand gaps, a real source, readiness

Decided after a data-quality audit ([DATA-QUALITY-AUDIT.md](../ai/phase5/DATA-QUALITY-AUDIT.md)); the architecture is unchanged (the AI service owns `src_*`, reads the catalogue only through public REST, never writes commerce data).

10. **Provenance is a first-class field** (`LIVE_EXTERNAL_SOURCE`, `BUYER_ENTERED`, `CUSTOMER_DEMAND_SIGNAL`, `DEMO_FIXTURE`, `TEST_FIXTURE`, `UNKNOWN_PROVENANCE`) on products, suppliers and quotes. Every list, KPI, score input, margin figure and assistant answer is live-only by default. Alternative considered: delete fixtures; rejected because tests need reproducible fixtures and ownership of old rows cannot always be proven, so rows are classified, not removed. Legacy rows without reliable provenance are `UNKNOWN_PROVENANCE`, never assumed live.
11. **A customer search is a demand gap, not a product.** It has no score, supplier, quote or approval until a buyer links it to a real product.
12. **Approval and catalogue readiness are separate.** Two senior decisions (business opportunity, then sourcing preparation) and an independent server-side readiness validator that gates proposals. Approval never orders, prices or publishes.
13. **A real source, behind a provider interface.** `victor_sport` reads the manufacturer's own site (robots.txt honoured, allow-listed hosts, bounded). Trade-off: a manufacturer site gives trustworthy identity, specifications and images but no prices and only one brand; marketplaces and shopping APIs were not assumed. Images are stored as attributed references, not copies.
14. **Scoring v2** reports opportunity, evidence confidence, data completeness, supplier risk and financial status separately; margins are tied to a stored basis (product, offer, quantity, currency, price, tax basis).

Consequences: runs are asynchronous with per-source results; one identical run at a time. Known gap: DNS rebinding between the address check and the connection.