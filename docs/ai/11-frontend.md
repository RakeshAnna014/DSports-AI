# 11 — Frontend integration

DSports is an e-commerce site powered by AI, not a shop with a chatbot attached. The **primary AI interface is the search box on the
Home page** (and above the normal filters on the Products page). The same Customer Agent also backs a **secondary chat drawer**, opened
from a small icon in the header. Both use one conversation, so a follow-up typed in either place continues the same session.

```
Home ─ DSports AI ─ [ Ask anything: products, orders, tracking, returns, refunds…  ✨ ]
        example prompts
        ── AI results (only after a question): "AI understood your request" · Verified from DSports · AI explanation
        ── Featured products (real catalog, cricket first, normal Add to cart)   ← always visible; works without AI
Products ─ [ same AI search ]  ·  existing filters (search, sport, category, brand, sort)  ·  product grid
Header ─ Home · Products · Track · (small AI chat icon) · Wishlist · Cart · Profile
```

The AI results sit **above** the storefront and never replace it. The normal Products search box and filters are untouched: they filter
the catalog in the browser and never call the AI service.

## Request flow

```
AiCommerceSearch (Home / Products)  ──┐
AssistantPanel   (header-icon drawer) ┴─▶ aiStore.send()  ─▶ POST /ai-api/v1/chat   (Vite proxy, same bearer token)
   ─▶ Python FastAPI :8090  ─▶ guard ─▶ router (Tier 0 rules ▸ Tier 1 model) ─▶ LangGraph Customer Agent
        shopping │ orders-returns │ support subgraph ─▶ typed tools ─▶ Java DSports APIs :8080 (customer's JWT)
   ◀─ structured response: intent, products, comparison, orders, tracking, eligibility, refunds, sources, meta.slots …
UnderstoodRequest + AssistantMessage render it: verified data first, the AI text separately labelled
```

## Components

| File | Role |
|---|---|
| `features/ai/AiCommerceSearch.tsx` | the primary AI search box (`hero` on Home, `compact` on Products): example prompts, follow-up placeholder, loading bar, the latest answer, "Open chat", "Clear" |
| `features/ai/ProductContextChip.tsx` | the "Asking about / Comparing" indicator (search box and drawer) |
| `features/ai/UnderstoodRequest.tsx` | **"AI understood your request"** chips (sport, product, experience, preference, budget) from `meta.slots` of a product search |
| `features/ai/MessageList.tsx` | `AssistantMessage`: a **"Verified from DSports"** block (cards/tables built from tool data) separate from the text labelled **"AI explanation"** (model) or **"From DSports data"** (deterministic); also the drawer transcript |
| `features/ai/ProductResultCards.tsx` | live price, stock, rating, attributes, weight, "above your budget" flag; View / Add to cart (existing cart store) |
| `features/ai/ComparisonTable.tsx` | attribute table; missing values shown as "Not available in the product data" |
| `features/ai/OrderStatusCard.tsx` | order, tracking, cart, payment, refund and return-eligibility cards |
| `features/ai/ConfirmActionDialog.tsx` | every proposed write waits here; Confirm → `POST /v1/confirm` |
| `features/ai/SourcesList.tsx` | citation chips (document, page, type) with the snippet on hover |
| `features/ai/AssistantPanel.tsx`, `AssistantLauncher.tsx` | the secondary drawer (opened by the header icon) and the confirmation dialog host |
| `features/home/HomePage.tsx`, `FeaturedProducts.tsx` | Home: AI search first, then the real catalog (cricket first, then newest) with live price and stock |
| `store/aiStore.ts`, `types/ai.ts`, `api/ai.ts` | one shared conversation/state, response types (mirror the service), client (`/ai-api`, same auth and refresh as the shop client) |

## Product context ("Ask AI")

Every product card has **Ask AI** (Home featured grid, Products grid, and the AI result cards). Clicking it puts that product in context:
the AI search box scrolls into view and takes focus, a chip shows **✨ Asking about: MRF Cricket Bat - Virgin** (removable), and the
placeholder becomes *"Ask about this product, compare it, or find alternatives..."*. After a comparison the chip reads **Comparing: A vs B**
and the placeholder *"Ask about these products or compare them..."*. With no product selected the normal follow-up placeholder is kept.

The **product detail page** has an *Ask AI about this product* section that already knows its product (example chips: tell me more, suitable
for a beginner, reviews, cheaper alternative). The UI sends only the product **id**; the service re-reads the product from DSports, and the
chip always shows the service's confirmed context, not a local guess. Reviews, product facts, comparisons and alternatives render as verified
cards (`ReviewsCard`, product cards, comparison table) with the explanation below, labelled separately.

## Behaviour

- **Deterministic vs AI:** a short keyword query ("cricket bat", "nike shoes") is answered by the service from the catalog with **no model
  call**; natural-language requests use rules or the cheap model to extract filters and the larger model only to explain. Account lookups
  (orders, tracking, refund status) are template answers over live data.
- **Guests** get product, knowledge and policy help (Add to cart there needs sign-in); account questions return a sign-in prompt.
- A conversation belongs to whoever started it: signing in or out starts a new one (the id lives in `sessionStorage`).
- **AI unavailable:** an unreachable service shows "AI assistance is temporarily unavailable. You can still use standard product search."
  Simple searches and account lookups keep working; a search the rules can parse still shows the **verified** products with the AI
  explanation marked unavailable. Nothing is faked. Checkout never depends on the AI service.
- After a confirmed reorder or an Add to cart the header badge refreshes. Model text is rendered as plain text with bold only.

`vite.config.ts` proxies `/ai-api` → `http://localhost:8090` (prefix stripped).

Verified by `tsc -b`, `vite build` and two Playwright journeys against the real stack ([13-e2e.md](13-e2e.md)). The repository has no unit-test
runner for the frontend (pre-existing); component behaviour is covered by the browser journeys.
