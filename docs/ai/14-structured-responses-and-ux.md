# 14 — Structured AI responses and the premium UX

The assistant no longer answers with paragraphs. Every response carries a **type**; the UI renders a component per type, and anything the
customer can choose is a **tap**, not typed text. This document is the contract between `ai-service` and the React app, and the honest list of
what the backend does *not* support yet.

## Response contract (`POST /v1/chat`)

New fields on `ChatResponse` (all existing fields are unchanged):

| Field | Meaning |
|---|---|
| `type` | one of the types below, decided **by the service** from what the response actually holds |
| `selection` | when the service needs the customer to pick: `{purpose: RETURN \| REORDER, prompt, options[]}`; each option has an opaque `choice_id`, the product name, order number, quantity, price, never an order id or product id |
| `suggestions[]` | contextual follow-ups: `{label, message}` (sent as an ordinary message) or `{label, choiceId}` (runs a step the service already resolved) |
| `context` | `{selectedProduct, comparisonProducts}` (see [05](05-customer-agent.md)) |

Request additions: `choiceId` (a tapped option), `selectedProductId` / `clearProductContext` (product context).

| `type` | Rendered as | Source of truth |
|---|---|---|
| `TEXT` | text card, labelled *AI explanation* or *From DSports data* | model / templates |
| `PRODUCT_LIST` | product cards: image, brand, rating, price, list price, **% OFF**, **SAVE ₹**, stock, wishlist, View, Add to Cart, Ask AI | catalogue, pricing, inventory |
| `PRODUCT_DETAIL` | the product card + the asked facts | catalogue |
| `PRODUCT_COMPARISON` | side-by-side table, "Add X to Cart" per product | catalogue, pricing |
| `PRODUCT_SELECTION` / `RETURN_PRODUCT_SELECTION` | **"Which product would you like to return?"** as product cards with **Select** | the customer's own orders |
| `RETURN_OPTIONS` | eligibility card (units, deadline) + **Return this item** | Returns service |
| `ORDER_LIST` | order cards with View / Track / Reorder / Return | orders |
| `ORDER_TRACKING` | **timeline**: placed → confirmed → packed → in transit → out for delivery → delivered, with the real event times | orders, shipments |
| `REVIEWS`, `CART`, `PAYMENTS`, `REFUNDS` | cards | respective services |
| `CONFIRMATION` | summary of what waits for confirmation (the dialog is the one place to confirm) or the result | confirmation tokens |
| `SUPPORT_CASE` | an honest card (see below) | n/a |
| `ERROR` | friendly error with **Try again**; raw errors are never shown | n/a |

`RECOMMENDATIONS` is served by `GET /v1/recommendations` (below), not by chat.

### Tappable choices without trusting the client or the model

When the service offers options it stores what each means (an intent and the exact `order_id`/`product_id`), and sends only `choice_id`s.
A tap sends `choiceId`; the service looks it up in the offers of **its previous response for that conversation** and runs exactly that. An
unknown, stale, replayed-by-another-customer or forged id does nothing (tests: `test_structured_responses.py`). The model never sees or
chooses an id, and "the second one" never has to be inferred.

## Recommendations (`GET /v1/recommendations?productIds=a,b&limit=4`, no model)

* "Complete your setup": same-sport products in complementary categories (bat → gloves, helmet, kit bag, shoes), live price and stock.
* Personalisation uses **only the customer's own orders** (their token): brands and categories bought, the price range they paid. A guest or a
  customer without orders gets `personalized: false` and no "Recommended for you".
* Every item carries `reasons`, each true of the data: *Completes your Cricket kit …*, *You bought MRF before (…)*, *Within your usual price
  range (₹a–₹b)*, *You haven't bought a gloves from DSports yet*, *N% off: you save ₹X*.
* `bundle.savings` is the sum of the products' own list-price discounts ("You save ₹X with this combination (vs list prices)").

## Visual system

`frontend/src/design/tokens.ts` + `theme.ts`: deep navy hero, vibrant green primary, lime highlight, orange for AI/actions, glass surfaces,
rounded cards, soft shadows, Sora display font with Inter body, reduced-motion respected. Sports artwork is **original inline SVG** (the project
ships no photography and no image URLs were invented); product images keep the existing mechanism (`getProductImageUrl`, real URLs when the
catalogue provides them, `loading="lazy"`).

Layout: dark glass header (logo mark, Home / Products / **AI Shop** / Orders / Track / Returns, **Ask DSports AI**, search, notifications,
wishlist, cart with count, profile); mobile bottom navigation (Home, **AI**, Products, Orders, Cart); cart drawer (items, subtotal, **real
savings**, *Complete your setup*, Continue shopping, Checkout); an *Added to cart* confirmation with recommendations instead of a redirect.

Pages: Home (hero + AI search, AI results in the page, Recommended for you, Complete your setup, Trending, Today's best deals, Recently
viewed, Explore sports, AI section), AI Shop (`/ai`, a continuous conversation), Orders (cards with actions), Returns (`/returns`), Products
(AI search above the unchanged filters; `?sport=` from the tiles), product detail (Ask AI about this product).

## What is NOT implemented, and the contract it would need

These were requested but DSports has no backend for them. Nothing was faked; the UI shows only what the backend supports.

| Requested | State | Backend contract required |
|---|---|---|
| Exchange flow (alternatives, price difference, delivery) | not built | exchange API: eligibility, candidate products, price difference, create exchange |
| Refund method choice (original payment / DSports wallet), "refund amount, 2–5 days", store credit | not built | refund-method + wallet/store-credit APIs and a refund estimate service; today a refund follows the existing admin refund process after a return is approved |
| Product-condition image upload and AI inspection | not built | an image-analysis service and an upload endpoint on returns; no "AI verified" claim is ever shown without it |
| Support cases (case id, status, next action, upload) | **honest card only**: the assistant says cases are not available and offers what it can do | a support-case API (create, status, attachments) |
| Products that need no pickup ("milk, vegetables") | not applicable to the sports catalogue | return-type / collection rules on the Returns service |
| Price-drop and back-in-stock notifications; "price dropped since you added" | not built | price history + notification triggers |
| Ratings on catalogue grids | shown only where the data exists (AI cards, reviews); the catalogue list API carries no ratings | rating in the catalogue summary |
| Personalised offers, limited-time badges, "customers also bought" | not built | offers/campaign service; co-purchase analytics |
| "Add this to cart" by voice/typing | the Add to Cart button is on every card; the assistant does not add silently | (by design: changes need the customer's action or confirmation) |
| Photography | none in the project | image assets or a CMS |

Future-ready: the UI consumes a framework-independent contract (`type` + data), so RAG, MCP, agents or an internal inventory-intelligence
dashboard can be added without touching the components.
