# ADR-012: Shopper profiles in Java; budget- and context-aware recommendations in the AI service

Status: accepted (management feedback implementation)

## Context

Management asked for four things: a customer ticket lifecycle, profile preferences with family profiles, recommendations that respect
budget and context (a ₹10,000 bat must not be recommended a ₹9,000 grip), and a consistent UI. The existing architecture
(ADR-009) already splits responsibility: Java owns commerce data and authorisation, Python owns AI orchestration.

## Decisions

1. **Profiles and preferences live in the Java `identity` module** (`shopper_profiles`, `shopper_active_profile`, migration V46), not in the
   AI database. They are authoritative customer data with ownership rules, so they sit beside the account. The AI service reads them
   through `GET /api/customers/me/profiles/active` with the customer's own token (new READ tool `get_shopper_profile`). A profile id of another
   account answers 404, exactly like a missing one.
2. **Family members are rows owned by the account**, not users: no credentials, no login, an age band instead of a date of birth, a hard
   limit (6), and a hard delete as the retention rule. Purchase history belongs to the account holder, so it never shapes a family
   member's recommendations and is ignored when personalisation is switched off for a profile.
3. **Sports are validated against the live catalogue** through a port (`SportTaxonomyPort`) implemented in `bootstrap`, so `identity`
   does not depend on `catalog`.
4. **The recommendation pipeline is deterministic code with no model call.** `ai-service/app/reco/policy.py` is pure (no I/O); `engine.py`
   gathers candidates with the existing tools. Order: scenario → reference → candidates → hard constraints → budget band → scoring →
   diversity. Prices, stock, discounts and the band are computed from DSports data, so an LLM outage cannot change a recommendation and
   an LLM cannot invent a price (`meta.llm_tokens` is always 0).
5. **Hard constraints are separate from the budget.** In stock, a known price, not the reference, not already bought, sport/role
   compatibility and the child-profile age rule are checked without any band, so no fallback can relax them.
6. **The complementary ceiling is configuration**: `AI_RECO_COMPLEMENTARY_CEILING_PCT` (default `0.20` = 20% of the reference product's
   effective price). Widening bands (`AI_RECO_COMPLEMENTARY_FALLBACK_PCTS`, default `0.30,0.40`) are tried only when fewer than
   `AI_RECO_MIN_CANDIDATES` real candidates fit, and the response says which band was applied and why. An explicit customer budget
   always wins and is never widened.
7. **"Frequently bought together" needs evidence.** `GET /api/v1/insights/co-purchases` (order module) returns distinct-order counts only,
   never below a server-side minimum, from non-cancelled orders. With no evidence the answer is an empty list and a message, not a guess.
8. **Tickets extend the Phase 3 case domain** (ADR-011) rather than adding a second one: two categories (V45), a staff priority change with a
   reason and a staff-only audit event, date/customer filters, and a customer form.

## Alternatives considered

* Profiles in the AI database: rejected, it would duplicate authoritative customer data and bypass Java authorisation.
* Letting the LLM pick related products: rejected, it can neither guarantee compatibility nor honour a price ceiling.
* A fixed 20% in code: rejected, management expects to tune it; it is one setting.
* Evidence upload for tickets: not built. There is no file-storage architecture (no object store, scanning or retention policy), so
  adding one would be a new platform decision, not a feature.

## Consequences

* A scenario recommendation costs several tool calls but no tokens; the tool budget for it is 30.
* Co-purchase evidence is only as good as real order volume: the demo data has little, so most products honestly show "not enough history".
* The PERSONALIZED fallback (no profile, no history) shows catalogue top picks only on the product-page API, never on the home page.
