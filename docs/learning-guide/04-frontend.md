# 04. React frontend code walkthrough

Root: `frontend/` (React 19, TypeScript, Vite, MUI 6, React Router 7, Zustand 5, TanStack Query 5, Axios). Scripts (`frontend/package.json`): `dev` = `vite`, `build` = `tsc -b && vite build`, `lint` = ESLint (see limitations). There is **no unit-test framework** in the frontend (no Vitest/Jest dependency); UI verification is done with Playwright scripts in `e2e/` (doc 14).

## 1. Entry point and routing

* `frontend/src/main.tsx` -> `App.tsx` (providers: MUI theme, React Query, router) -> `routes/index.tsx`.
* `createBrowserRouter @ frontend/src/routes/index.tsx:30-64` defines two kinds of areas:
  * **Internal consoles with their own layout** (no storefront chrome): `/inventory-ai/:tab?` (`InventoryApp`, line 32) and `/support-cases/:id?` (`SupportApp`, line 34). Comments at lines 31 and 33 state that access is enforced by the backend on every request.
  * **Storefront inside `MainLayout`**: `/`, `/login`, `/register`, `/products`, `/products/:id`, `/cart`, `/wishlist`, `/checkout`, `/orders`, `/orders/:id`, `/payment*`, `/notifications*`, `/shipments/:id`, `/track`, `/profile`, `/ai` (AI Shop page), `/returns`, `/support/:ref?` (customer cases, `MyCasesPage`), `*` (404).
* `MainLayout` (`layouts/MainLayout.tsx`) mounts `AssistantLauncher` (line 43), so the assistant drawer is available on every storefront page. `AssistantLauncher` renders the floating button, `AssistantPanel` and `ConfirmActionDialog` (`features/ai/AssistantLauncher.tsx`).

## 2. Organisation

| Folder | Purpose |
|---|---|
| `api/` | Axios clients and typed API functions (one file per backend area); `client.ts` is the shared auth interceptor |
| `store/` | Zustand stores: `authStore`, `cartStore`, `wishlistStore`, `notificationStore`, `aiStore`, `uiStore`, `recentStore` |
| `types/` | TypeScript response types (`ai.ts`, `support.ts`, `inventoryAi.ts`, ...) |
| `features/<area>/` | pages and feature components: `ai/` (assistant), `support/`, `inventory/`, `orders/`, `payments/`, `products/`, `returns/`, ... |
| `components/` | shared UI (`ProductCard`, `common/States.tsx`, layout, cart drawer) |
| `design/tokens.ts`, `theme.ts` | design tokens and MUI theme |

## 3. API clients, authentication, error representation

* **One authentication mechanism for both services.** `installAuth @ frontend/src/api/client.ts:7-45` adds `Authorization: Bearer <accessToken>` (from `localStorage`) to every request and, on a 401 (not on `/auth/*`), refreshes once via `POST /api/auth/refresh`, stores the new tokens and retries; on failure it clears tokens and redirects to `/login`.
* `apiClient` (`client.ts:47-52`) has base `/api` (proxied to the Java backend `:8080`). `aiClient` (`api/ai.ts:6-12`) has base `/ai-api` (proxied to the AI service `:8090`, prefix stripped, `vite.config.ts:16-26`) and a 120 s timeout. The inventory client (`api/inventoryAi.ts:8`) also uses `/ai-api/v1/inventory`.
* `authStore.login @ store/authStore.ts:18-26` stores `accessToken` and `refreshToken` in `localStorage` and keeps `{userId,email,roles}`; `hydrate` (46-64) reads the roles by **decoding the JWT payload with `atob` without verifying it**. This is only for showing/hiding UI (`isSupportStaff`, `isBusinessUser`); every real decision is made by the backend. A forged client-side role would show a console that then receives 403s (this is exactly what the browser security steps test).
* **Errors:** backend errors use the common body, parsed by `getApiError @ api/errors.ts:15-23` (checks `code` and `message`); AI errors use `getAiErrorMessage` (`api/ai.ts:48-55`), where 429 becomes "You are sending messages too quickly"; `isAiUnreachable` (41-46) is true when there is no response or a 502-504, which drives the "assistant is not available, the shop still works" state. `supportError @ api/support.ts:31-40` maps unreachable/403/404.

## 4. The customer AI experience

Components under `features/ai/`:

* `AiCommerceSearch.tsx` (`AiSearchBox`, `AiSearchResults`): the one search box used on the home hero, the products page (`ProductsPage.tsx:135`) and product pages (`ProductDetailPage.tsx:347`, with the product as context). Popular chips (`POPULAR`, lines 11-18). It uses the same `useAiStore` as the drawer.
* `AiShopPage.tsx` (`/ai`): a full-width conversation.
* `AssistantPanel.tsx` (drawer): header "DSports AI", guest notice ("Sign in to ask about your orders..."), suggestion chips, `MessageList`, input (max length 1000, `AssistantPanel.tsx:78-84`). A conversation belongs to whoever started it: signing in or out calls `reset()` (lines 30-36).
* `MessageList.tsx`: `AssistantMessage` decides what to render from the structured response: verified components (`ProductResultCards`, `ComparisonTable`, `OrderCards`, `OrderTimeline`, `CartCard`, `PaymentCards`, `RefundCards`, `ReviewsCard`, `RecommendationBlock`, `ReturnOptions`) inside a **"VERIFIED FROM DSPORTS"** block (lines 43-51, 68-84); the text inside a paper labelled **"AI EXPLANATION"** when `messageSource === 'ai'` and **"FROM DSPORTS DATA"** otherwise (lines 91-98); sources (`SourcesList`), disclaimers, and suggestion chips. `RichText` renders only `**bold**` and line breaks, never HTML from the model (lines 19-25). `responseKind` (28-38) is a frontend adapter that classifies old responses by what they contain.
* `ResponseExtras.tsx`: `ConfirmationCard` (summary of what is waiting; "Nothing happens until you confirm"), action result alert, `ResponseError` (friendly error with retry; "DSports AI is taking a break" when `ai_unavailable`).
* `ConfirmActionDialog.tsx`: **every WRITE proposal stops in this dialog** (titles for all six WRITE tools, lines 4-8); the button calls `confirmPending`.

### State: `useAiStore` (`store/aiStore.ts`)
* `send` (88-111): appends the user entry, calls `aiApi.chat(message, conversationId, {selectedProductId, clearProductContext, choiceId})`, saves the conversation id in `sessionStorage`, stores the structured response, sets `pendingAction` when `requiresConfirmation`. The service is the source of truth for the product context (line 97).
* `confirmPending` (121-137): `aiApi.confirm(token, conversationId)`; if the result changed the cart it refreshes the cart badge.
* `dismissPending` (139-142): sends the message `"no"`, so cancelling goes through the same Tier-0 path.
* Failures become a `ChatEntry` of role `error` with `unavailable` set from `isAiUnreachable` (52-59): "The AI assistant is not available right now. The rest of the shop works as usual".

## 5. Journey A traced: natural-language product discovery (UI -> Python -> Java -> UI)

1. **UI event.** The user types "lightweight cricket bat for a beginner under 8000" in the hero/products search box and presses Enter. `submit` -> `useAiStore.send` (`AiCommerceSearch.tsx:67-71`, `aiStore.ts:88`).
2. **API request.** `POST /ai-api/v1/chat` with `{message, conversationId, selectedProductId, clearProductContext, choiceId}` and the bearer token if signed in (`api/ai.ts:16-29`). Vite rewrites to `POST :8090/v1/chat`.
3. **Python endpoint.** `chat @ ai-service/app/api/routes.py:19-23` -> `optional_principal` (guest or customer) -> `ChatService.chat @ service.py:143` (rate limit, conversation, graph invoke).
4. **Graph and understanding.** `guard_in` allows; `route_node`: Tier 0 `extract_shopping_slots` rules may match ("cricket bat ... under 8000": sport/category/price) -> `Route(PRODUCT_SEARCH, tier 0)`. Only an unclear message reaches Tier 1 (Gemini Flash-Lite JSON). Model routing and cost: doc 06.
5. **Tools.** `shopping.search` -> `rt.call("search_products", args)` -> `ProductSearch.search @ ai-service/app/shop/search.py:81-153`: hybrid retrieval over `product_index` (pgvector + full text), then **Java calls**: `GET /api/prices?productIds=`, `GET /api/inventory?productIds=` (batch, live), `GET /api/v1/reviews/product/{id}/summary`, `GET /api/catalog/products/{id}` and `/attributes` (`Catalog.live`, `shop/catalog.py:111-174`), budget filter, `rank(...)` (`shop/ranking.py:86-130`, weights `WEIGHTS` lines 30-33).
6. **Explanation.** `explain` calls Gemini Tier 2 to explain the ranked list unless it is a plain keyword search.
7. **Validation.** `guard_out` runs `check_grounding`; failure replaces text with the deterministic text.
8. **Response.** `ChatResponse` with `products[]` (cards built by `product_card`), `message`, `messageSource`, `suggestions`, `meta`.
9. **Render.** `AssistantMessage` shows `ProductResultCards` inside "VERIFIED FROM DSPORTS" and the explanation under "AI EXPLANATION". Card actions ("Ask AI", add to cart) call the shop APIs or `askAboutProduct`.

So the request calls **both** Python (understanding, retrieval, ranking, explanation) and Java (live price, stock, product facts, ratings). Java is never asked to rank or explain.

## 6. Customer support UI (Phase 3)

* In the assistant: `SupportChatBlock.tsx` renders `InvestigationEvidence` + `ResolutionSummary` for an investigation, `SupportCaseCard` for a created/looked-up case, `SupportCaseList` for the customer's cases, and `support-ask` for a clarifying question (lines 9-28). It shows exactly what the backend returned for case number, status, priority and escalation.
* `/support/:ref?` -> `MyCasesPage.tsx` (the customer's cases via `supportApi.mine/get/message/escalate/reopen`).
* Staff console `/support-cases/:id?` -> `SupportApp.tsx` (293 lines): role gate `isSupportStaff` (line 13: ADMIN or SUPPORT_EXECUTIVE; again only a UX gate), analytics KPIs (`Analytics`, lines 26-46: unresolved, review requested "nobody has taken them yet", unassigned, reopened, resolved by a person with "automatic: 0", oldest unresolved with "no SLA is defined"), case list filters (status, category, priority, escalation, unassigned, free text), and `Actions` (53-): take case, set queue, internal note, reply, change status, resolve with an outcome. Every mutation sends `expectedVersion: c.version` (line 64-73); on `OPTIMISTIC_LOCKING_CONFLICT` it shows "Someone else changed this case. It has been reloaded" (line 63). Source chips label evidence as "Backend verified" or "AI investigation (not re-checked)" (lines 48-51).
* Types in `types/support.ts`; `fromChat` adapts the assistant's snake_case payload to the page's case type.

## 7. Inventory Intelligence UI (Phase 2)

`features/inventory/InventoryApp.tsx` (99 lines): route `/inventory-ai/:tab?`. States: loading skeleton, **sign-in required**, **Restricted** (not ADMIN; `isBusinessUser`, lines 23, 57-67), then the app with tabs `overview, forecast, risk, replenishment, overstock, promotions, events` (lines 13-21), a "Refresh forecast" button (`POST /models/train`), a `DataBadge` ("POC synthetic data") and the chip "Decision support: nothing here changes prices, orders or stock" (lines 79-80). The Copilot (`Copilot.tsx`) is docked on wide screens and a drawer on narrow ones. `ErrorBoundary.tsx` wraps the copilot (added after a crash caused by an empty `provenance` object, `docs/ai/phase2/14-final-stabilization-report.md` defect 7). `api/inventoryAi.ts` has one function per endpoint (`overview`, `forecast`, `risk`, `replenishment`, `overstock`, `slowMoving`, `aging`, `clearance`, `events`, `eventImpact`, `regions`, `quality`, `scenario`, `train`, `copilot`) and `getInventoryError` maps 403/404 `INSUFFICIENT_DATA`/unreachable to messages. Components: `tabs.tsx`, `charts.tsx`, `blocks.tsx` (copilot answer blocks: overview, risk_table, clearance_table, notice, quality, ...), `ui.tsx`.

## 8. Loading, empty, error and AI-unavailable states
* Loading: `Thinking` and skeletons (`components/common/States.tsx`, `data-testid="ai-loading"`).
* Error with retry: `ErrorState`/`ResponseError`; `retry` re-sends the last request after removing the failed exchange (`aiStore.ts:113-119`).
* AI-unavailable: the chat entry above, or `status: ai_unavailable` from the service; **the rest of the shop and the support pages keep working** (verified by browser steps "the AI service down: the assistant says so, and the case pages (Java) still work" in `e2e/support-ui.mjs`, and `shop-with-ai-down` in `validate-all.sh`).
* Guests: product/policy questions only; account intents return `needs_login` with a Sign-in button (`MessageList.tsx:100-102`).

## 9. Client-side assumptions that could affect correctness
1. Roles read from the JWT payload are trusted for UI gating only (backend enforces).
2. Tokens are in `localStorage` (inherited XSS exposure, documented as a known limitation).
3. The Vite proxy answers `/ai-api` with HTTP 500 when the AI service is down (a production gateway would answer 502/503); `isAiUnreachable` only treats no-response and 502-504 as unreachable, so a 500 from the dev proxy would be shown via the generic error path (documented as limitation 8 in `docs/ai/phase2/14-final-stabilization-report.md`).
4. The conversation id lives in `sessionStorage`; the server returns a fresh conversation if the id is unknown/expired/another owner's (`ChatService._conversation`).
5. 15-minute access tokens: a long-running tool (evaluation scripts, not the UI) can see 401s; the UI refreshes once and retries.
6. `lint` is `eslint . --ext ts,tsx`, but no ESLint configuration file exists in `frontend/` (a search found config files only inside `node_modules`), consistent with `docs/baseline/10-final-hardening.md:27,197` ("ESLint has no config (pre-existing)"). Lint is therefore not a quality gate; `tsc -b` and the production build are.

## 10. Browser and end-to-end tests
Playwright (`e2e/package.json`: playwright 1.63.0), run with Node directly: `e2e/ui-flow.mjs` (register -> cart -> checkout -> pay -> refund request; also the AI-down variant), `ai-commerce.mjs` (T1-T15 customer journey), `ai-ui.mjs`, `ai-product-context.mjs`, `ai-redesign.mjs`, `ai-multisport.mjs`, `inventory-ui.mjs` (23 steps), `inventory-failures.mjs`, `support-ui.mjs` (23 steps: customer investigation + case, staff console, access control, AI down, API failing). Details and results: doc 14.
