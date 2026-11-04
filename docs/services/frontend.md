# Frontend (`frontend/`, port 3000)

## 1. Business purpose
The customer storefront (browse, cart, checkout, orders, returns, support, profile, the AI shopping assistant) and the staff workspaces (support cases, fulfilment board, inventory intelligence, engineering AI, product intelligence, sales planning). It holds no business rules: every price, stock level, status and permission shown comes from the two back ends.

## 2. Entry points
* `src/main.tsx` → `src/App.tsx` → `src/routes/index.tsx` (React Router 7 route table). `src/layouts/MainLayout.tsx` is the shop shell (header, search, cart, notifications, assistant launcher).
* Staff workspaces are separate top-level routes with their own shells: `/inventory-ai/:tab?`, `/engineering-ai`, `/product-intelligence`, `/sales-planning`, `/fulfilment`, `/support-cases/:id?`.
* Shop routes (inside `MainLayout`): `/`, `/login`, `/register`, `/products`, `/products/:id`, `/profile`, `/cart`, `/wishlist`, `/checkout`, `/ai`, `/returns`, `/support/:ref?`, `/orders`, `/orders/:id`, `/order-success`, `/payment`, `/payment-success`, `/payment-failure`, `/notifications[/:id]`, `/payments`, `/shipments/:id`, `/track`.

## 3. Structure
| Folder | Content |
|---|---|
| `src/api/` | one typed Axios module per back-end area. `client.ts` builds the instance for `/api` (Java); `ai.ts` builds the one for `/ai-api` (Python). Both attach the bearer token and, on a 401, refresh once and retry |
| `src/features/<area>/` | pages and components per area (`ai`, `planning`, `sourcing`, `inventory`, `engineering`, `support`, `fulfilment`, `checkout`, `orders`, `payments`, `returns`, `shipments`, `profile`, …) |
| `src/store/` | Zustand stores: `authStore`, `cartStore`, `wishlistStore`, `notificationStore`, `aiStore`, `uiStore`, `recentStore` |
| `src/hooks/useCatalog.ts`, TanStack Query | server-state caching and invalidation |
| `src/components/analytics/`, `src/design/`, `src/theme.ts` | shared chart and design-system components, MUI theme |
| `src/utils/safeUrl.ts` | `safeHref`: only `http(s)` links without credentials are rendered as links (stored supplier URLs are untrusted) |

## 4. Request lifecycle
Component → feature hook / TanStack Query → `src/api/*.ts` → Axios (`Authorization: Bearer <accessToken>` from `localStorage`) → Vite dev proxy (`/api` → `localhost:8080`, `/ai-api` → the AI service on 8090; see `vite.config.ts`) → service. On a 401 the interceptor posts the refresh token to `/api/auth/refresh`, stores the new pair and replays the request once; if refresh fails both tokens are removed. Errors use the shared `ApiError` body (`src/api/errors.ts` extracts the message and field errors).

## 5. Business rules in the UI
None are decided here. The UI only separates "verified from DSports" data (cards, tables) from AI-written text, shows synthetic and demonstration labels returned by the services, asks for the explicit confirmation step before any assistant write, and states why a figure is unavailable instead of showing a placeholder.

## 6. Data
No database. Browser storage: `accessToken` and `refreshToken` in `localStorage` (**known risk R1**: readable by any XSS; there are no `dangerouslySetInnerHTML` uses), plus a few UI conveniences.

## 7. Dependencies
Java backend and AI service (both through the proxy), nothing else. When the AI service is down only the assistant and staff AI pages degrade; the shop keeps working (exercised by an "AI down" step in `e2e/ai-commerce.mjs`).

## 8. Security and authorization
**There is no client-side role guard in the router**: the staff routes render a shell and the back end answers 401/403 for callers without the role. The UI hiding or showing a button is never the control; the server is. Links built from stored text go through `safeHref`.

## 9. Failure handling and observability
Query errors render the typed error message; the assistant panel shows an "AI unavailable" state. There is no frontend log collection or error reporting service.

## 10. Run and test
```bash
cd frontend
pnpm install
pnpm exec vite                       # dev server on :3000 (restart it after big external edits: it can serve stale modules)
pnpm exec tsc -b && pnpm lint        # type check; ESLint with --max-warnings 0
pnpm exec vitest run                 # unit and component tests (API mocked per test)
pnpm build                           # tsc -b && vite build
node ../e2e/ui-flow.mjs              # real-stack browser journeys (see e2e/README.md)
```
Never use `npm install` (the lockfile is pnpm's).

## 11. Implemented / partial / missing / unverified
Implemented: all routes above. Partial: no streaming of assistant answers (single JSON response), no internationalisation (`src/i18n` is an empty folder). Missing: client-side route guards, cookie-based session. Verified status of each journey: [../STATUS.md](../STATUS.md). First files to read: `routes/index.tsx`, `api/client.ts`, `store/authStore.ts`, `features/ai/AssistantPanel.tsx`, `features/planning/PlanningApp.tsx`.
