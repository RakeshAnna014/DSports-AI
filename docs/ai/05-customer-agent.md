# 05 — The Customer Agent

One LangGraph workflow (`ai-service/app/agent/graph.py`) with three subgraphs. Simple capabilities are typed tools, not agents.

```text
START → guard_in ─blocked→──────────────────────────────────────────────┐
           │                                                              │
           ▼                                                              │
         route ──needs sign-in──────────────────────────────────────────┤
           ├─ shopping subgraph      search → explain │ compare │ knowledge ┤
           ├─ orders_returns subgraph orders, order, tracking, payment,    │
           │                          refund, cart, return check/create,   │
           │                          reorder, confirm yes / no            ┤
           ├─ support subgraph       policy/FAQ/warranty │ refund-why │ case┤
           └─ smalltalk (greeting, out of scope)                           ┤
                                                                           ▼
                                                              guard_out (grounding) → END
```

Per request: `recursion_limit = AI_MAX_ITERATIONS`, a tool-call budget (`AI_MAX_TOOL_CALLS`), a model token budget
(`AI_REQUEST_TOKEN_BUDGET`) and a wall-clock timeout (`AI_REQUEST_TIMEOUT_S`). Exceeding one degrades to the deterministic answer,
it never loops.

## Tiers (cost control)

| Tier | What runs | Model tokens | Examples |
|---|---|---|---|
| **0** | rules (`router/tier0.py`) → tools → templates | **0** | show my orders, where is order X, show my cart, refund status, payment status, "yes"/"no", greetings, "return them" (after an eligibility check), clear shopping requests ("football boots under 5000"), refinements ("too expensive"), comparing named/shown products ("compare the first two", "which has better value?"), **plain keyword searches ("cricket bat", "nike shoes"): the answer is the deterministic result list with no model call at all** (`plain` slot; a brand with no products is said so, never passed off as a match), definitional questions about product terms ("what is English willow?" → cited RAG), "where is it?" after an order was shown, "I want to return my last order" (an eligibility check, never a creation) |
| **1** | `gemini-2.5-flash-lite`, JSON output, ≤300 tokens, `temperature 0` | ~0.5–1k in | messages the rules do not recognise ("something for my nephew who just started playing") |
| **2** | `gemini-2.5-flash` explanation/synthesis | ~0.6–2.4k in, ~0.1–0.2k out | explaining search results, advice/comparison, cited RAG answers |

A request's reported `tier` is the highest tier actually used. Tier-1 output is a *proposal*: numbers the customer did not write are
dropped and explicit words (sport, category, budget) override the model's reading. Tier-1 results for context-free, identifier-free
text are cached (in-process TTL) and are customer-agnostic.

## Product discovery (no model decides anything)

`search_products` = slots → hybrid retrieval over the derived product index (PostgreSQL FTS + pgvector cosine, RRF) with metadata
filters → **live** price and stock from DSports (2 batch calls) → budget/availability filters → deterministic ranking → top N, then
live details and ratings for what is shown. A product with no live price is not shown. If a filter is over-constraining it is
widened step by step and the response says so. If nothing fits the budget, only near misses (≤ 25% over) are offered, flagged
`over_budget`; otherwise the answer is empty and honest.

**Ranking** (`shop/ranking.py`, unit-tested) — `score = Σ wᵢ·fᵢ / Σ wᵢ` over the factors that apply to the request:
semantic 0.20, category 0.15, sport 0.10, skill 0.15, age group 0.10, budget 0.10, weight 0.10, availability 0.05, rating 0.05.
A factor the customer did not express is left out and the weights are renormalised, so an unmentioned preference never helps or
hurts. Ties break on price then name. The same inputs always give the same order. The model only explains the result.

## Conversation state

Stored per conversation in `dsports_ai.conversations`, owned by the customer's HMAC hash (guests: a sentinel, reachable only by the
random id), TTL 24 h (`AI_CONVERSATION_TTL_H`). It holds only: last intent, merged search slots, the products last shown (id, name,
price at that time, weight, category, sport, skill — used for "the first one", "too expensive"), the order last discussed, a return
candidate, the pending action (nonce + token) and a **rolling summary built by rules** (no model call, ≤ ~150 tokens, never prices
or stock). A refinement ("These are too expensive. Show lighter options.") merges slots: the budget ceiling becomes 80% of the
previous ceiling, `weight_pref` becomes `light`, everything else is kept. The conversation is **never** resent to the model.
If the database is unavailable the state falls back to memory for that process.

## Orders, returns, reorder

Lookups are Tier 0 (templates over tool data). **Return**: find the purchase (hint like "shoes", or an order number) in the customer's
own orders → `check_return_eligibility` (the Java Returns module decides) → the policy is retrieved and the model explains the
*verdict first* with citations → "return them" creates a **proposal** (re-checked live) and a confirmation token. **Reorder**: find the
line in past orders → live product/price/stock → proposal "add N × X to your cart at today's price" → confirmation → existing
cart API. The assistant never creates an order or a payment. `create_support_case` is not implemented because DSports has no such API;
asking for a ticket or a person gets an explicit "not available" answer (`SUPPORT_CASE`, Tier 0).

## Write safety

See [08-security.md](08-security.md): single-use HMAC confirmation tokens bound to customer, tool and arguments; 5-minute TTL;
live revalidation immediately before execution (including a check that the price/quantity the customer saw is unchanged);
`Idempotency-Key = ai-<nonce>`; an audit row for every step.

## Failure behaviour

| Failure | Result |
|---|---|
| Gemini unavailable / quota / auth | circuit breaker opens after `AI_BREAKER_FAILURES` (default 3); calls fail fast with `AI_UNAVAILABLE`; Tier 0 flows still work; plain searches fall back to keyword retrieval with a deterministic answer |
| Embeddings unavailable | retrieval degrades to keyword-only |
| Model text fails the grounding check | the model text is dropped and the deterministic answer from the same data is returned (`meta.grounding.fallback`) |
| DSports unreachable | the tool returns `UPSTREAM_UNAVAILABLE`; the answer says it could not fetch that |
| Token / tool / time budget exceeded | deterministic answer |
| Database unavailable | conversation state in memory; confirmations and audit are refused rather than executed without a trail |

Retries: only idempotent model calls (one retry on timeout/5xx). Writes are never retried.

## Empty results and context (home-search behaviour)

- A refinement that finds nothing does **not** erase the products the customer last saw: "Which one is better?" still refers to them.
- When nothing fits a budget and nothing is close, the answer names the **real lowest-priced match** (live price, flagged "above your
  budget") if it is within 2x of the budget; beyond that nothing is shown (a far-off product is noise, not help).
- "Compare the first two" / "both" / "these two" select those products; a comparison of a subset never shrinks the remembered list.
- "Show my latest order" returns one order; "my recent orders" returns the list.

## Product context ("Ask AI" on a product)

The customer can put **one product in context** and then ask about "it"; no second agent or chatbot exists. The same graph, tools and RAG
are used.

**State.** `ConversationState` gained `selected_product` (`{id, name, brand, category, sport}`) and `comparison_ids`; the shown products are
the existing `last_products`, the current search is `slots["search"]`. The response carries `context.selectedProduct` and
`context.comparisonProducts` so the UI can show them.

**How a product gets selected (never from model or client text):**
1. The UI sends `selectedProductId` (an id only) with a chat request. The service validates it as a UUID and **re-reads the product from
   DSports** (`get_product`); an unknown, inactive or forged id is ignored with a note. Name, price and stock in the request do not exist.
2. "Tell me more about the MRF one" / "the first one" resolves against the products the service itself last returned (`last_products`).
3. `clearProductContext` forgets it. A conversation belongs to one customer, so another customer's conversation id never reveals it.

**What it routes to** (rules in `router/tier0.py`, so no model is needed to understand them; Tier 1 is told the selected product too):

| Customer says | Intent | Tools | Model |
|---|---|---|---|
| "How heavy is it?", "What is its weight?", "What material is it made from?", "Tell me more about this bat" | `PRODUCT_DETAIL` | `get_product` | none: a template over the live DSports card |
| "Show me reviews" | `PRODUCT_REVIEWS` | `get_product`, `get_product_reviews` (reviewer identity removed) | none |
| "Is it good for tennis-ball cricket?", "Is it suitable for a beginner?", "What does the manufacturer recommend?" | `PRODUCT_KNOWLEDGE` (about the selected product) | `get_product` (live facts), `get_product_knowledge` (RAG, the product's own documents plus the general guides) | explains from the cited sources only |
| "Compare it with the SS bat" | `COMPARE` (selected product + the named one) | `search_products` for the name, `get_product` | explains the DSports table |
| "Is there anything similar but cheaper?", "Show me something lighter" | `PRODUCT_SEARCH` with `alternative_to` | `get_product`, `search_products` | none, or the normal search explanation |
| "Which one is better?", "Which one is lighter?" | `PRODUCT_ADVICE` over the products being compared | `get_product` each | explains the DSports table |

**Alternatives are live.** The service searches the same sport and category, then filters against the selected product's *current* price
and weight, so "cheaper" really is cheaper. When nothing is, it says so ("There is no cheaper bat than ₹6,499.00 ...") and invents nothing.
A selected product also stays in context across alternatives, comparison and follow-ups until it is cleared.
