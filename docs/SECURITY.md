# DSports AI — Security Posture (POC / internal review)

Status vocabulary: **VERIFIED** = a test or command was run and observed; **IMPLEMENTED** = in code, not exercised end to end; **KNOWN SECURITY RISK** = open, accepted for an internal demo only.
No external penetration test or third-party security assessment has been performed. Everything below is the project's own review and tests.

## Trust boundaries

```
Browser (React) ──/api──────────► Java backend :8080  (authoritative: price, stock, orders, payments, refunds, returns, cases, RBAC)
        └─────────/ai-api (Vite rewrite → /v1/*)──► Python AI service :8090 ──► Java backend (REST, customer's own JWT forwarded)
                                                                       └──► Postgres dsports_ai (pgvector), Gemini/Vertex, Tika
```

The AI service holds no commerce authority. It calls Java only through its public REST API with the **customer's** token, so Java's ownership checks and RBAC are the enforcement point, not the model.

## Controls

| Control | Status | Evidence / note |
|---|---|---|
| JWT authentication (HS256, 15 min access / 7 d refresh); secret only from `JWT_SECRET` | IMPLEMENTED | No default in `application.yml`; the `local` profile has a labelled dev-only default |
| Ownership checks on orders, payments, returns, cases, shipments | VERIFIED | Backend unit/integration suites and the API E2E (cross-customer 403/404 cases) |
| ADMIN-only Inventory Intelligence and Engineering AI (role enforced in the backend / tool registry `required_role`) | VERIFIED | Python unit + integration suites, `mgmt-*` and `eng-*` E2E stages |
| Writes need a single-use confirmation token bound to customer + tool + argument hash; idempotency keys; audit rows | VERIFIED | Python unit and live suites |
| AI never states price/stock/order/refund facts it did not receive from a tool | VERIFIED | Output guard tests, dev evaluations; hallucinated product/price count 0 in recorded runs |
| Prompt-injection handling: retrieved text and logs treated as data; injected log text is withheld from model output | VERIFIED | Python security tests, Engineering AI evaluation (synthetic injection string in a log line) |
| Secret/PII redaction on log ingestion (Engineering AI) and in AI-service logs | VERIFIED | Unit tests; redaction happens before storage |
| RAG ingestion takes only files listed in a server-side manifest (no end-user upload endpoint) | IMPLEMENTED | `app/rag/ingest.py` — type allow-list by extension, content goes through Tika |
| **Rate limiting (Java)**: login/register/refresh per client address; ticket creation per authenticated user (messages 3×) | VERIFIED (unit + live) | `RateLimitWebFilterTest` 6/6. Live, 2026-10-14: a second backend on :8081 with `RATE_LIMIT_AUTH_PER_MINUTE=3` answered login attempts 1–3 with 401 and attempts 4–5 with 429, `Retry-After: 60`, standard error body (`RATE_LIMITED`). Ticket-creation limiting was verified by unit test only. The instance was then stopped. The long-running :8080 backend was started before this change and does not have the filter until it is rebuilt and restarted |
| Rate limiting design | — | Fixed window, **in-memory, per instance**. Client address is the socket's remote address; `X-Forwarded-For` is deliberately ignored (it is client-controlled). Behind a reverse proxy every client shares the proxy's address until a trusted-proxy setting exists. Configure via `RATE_LIMIT_ENABLED`, `RATE_LIMIT_AUTH_PER_MINUTE`, `RATE_LIMIT_TICKETS_PER_MINUTE`; the `local` profile raises the limits so test harnesses are not blocked |
| Frontend lint gate (ESLint 9 flat config, `--max-warnings 0`) | VERIFIED | `pnpm lint` exit 0 in this mission |
| **Product & Supplier Intelligence (Phase 5)**: buyer roles on every route and tool (`PROCUREMENT_BUYER`, `CATEGORY_MANAGER`, `ADMIN`); approvals, reopening a rejection and VERIFIED supplier status need an approver | VERIFIED | `tests/sourcing` (workflow, tools), `test_sourcing_db.py` API flow, `e2e/ai/sourcing_e2e.py` (anonymous 401, customer and support executive 403, buyer cannot approve, stale version 409) and `e2e/sourcing-ui.mjs`. Denied requests are audited (`src_decisions`) |
| Phase 5 external retrieval (SSRF), `app/sourcing/fetch.py`: host allow-list, https only, no embedded credentials, ports 80/443, no private/loopback/link-local/reserved address after DNS resolution, redirects followed by hand with **every hop re-checked**, size, content-type and time caps, per-host request spacing, robots.txt honoured (unreadable robots = do not crawl); page text treated as untrusted data (scripts removed, instruction-like text dropped). Buyer-added images only from `SOURCING_IMAGE_HOSTS`, validated by first-bytes sniffing | VERIFIED (unit tests with a mock transport for the rules; **one live fetch path exercised** against the Victor Sport site by `e2e/ai/sourcing_live_e2e.py` and the browser journey) | `tests/sourcing/test_fetch.py`, `test_extract_images_specs.py`, `test_victor.py`. DNS rebinding is closed by `PinnedTransport` (2026-10-16): the name is resolved once at connection time, every address must be public, and the request goes to that exact address with the real Host and TLS name |
| Phase 5 customer privacy: unmet searches stored as salted hash + redacted phrase (digit runs, e-mail and URLs removed, 80 characters) | VERIFIED | `test_demand.py`; the real-stack test checks the opportunity evidence holds no customer e-mail or identity |
| Phase 5 cannot change commerce data: no code path writes catalogue, price, order or payment data; approval records a decision only | VERIFIED | `sourcing_e2e.py` compares product, order, payment, shipment and price snapshots before and after an approval and a proposal (identical); `test_store.py` guards the `src_*` schema |

## Known security risks

1. **JWT in `localStorage` — KNOWN SECURITY RISK.** Any XSS in the SPA can read the access and refresh tokens. Mitigations present: React escaping, no `dangerouslySetInnerHTML` on model/user text in the AI panels, short access-token life. Not migrated: moving to HttpOnly, `SameSite` cookies needs a CSRF strategy and a refresh-flow change across Java and the SPA, which is not a safe last-minute change. *Next action:* design the cookie-based session (ADR first), then migrate. *Blocks:* production, not an internal demo.
2. **Shared HS256 secret between Java and Python — KNOWN SECURITY RISK.** The AI service can mint tokens in principle. *Next action:* asymmetric signing (RS256/EdDSA) with a public key in the AI service. *Blocks:* production only.
3. **Rate limiter is per instance and in-memory — POC ONLY.** Multiple instances multiply the effective limit; a restart clears counters. *Next action:* shared store (Redis is already on the roadmap) behind the same filter interface.
4. **No evidence/attachment upload for tickets — NOT IMPLEMENTED (REQUIRES BUSINESS DECISION).** No storage service, retention period or malware-scanning capability exists, and none is claimed. Building upload without them would create an unsafe feature. *Interim:* tickets accept text only. *Next action:* decide retention + storage, then implement type/size/signature validation, server-generated names, private access and tests. *Blocks:* nothing in the demo.
5. **Dependency scanning — PARTLY RUN.** `pnpm audit --prod` was run on 2026-10-16 and found 13 advisories in `axios` and `react-router`; both were upgraded and the production audit is now clean. Development-only advisories remain (vitest 3.x line, see the threat model R5). **Not run:** OWASP dependency-check (Java) and `pip-audit` (Python): no scanner is configured or available offline. *Next action:* add them to CI.
6. **Gemini/Vertex receives prompt content** (customer-visible text, tool results). Only first names and trimmed order fields are sent; no payment data. Data-processing terms are a business/legal decision (**REQUIRES BUSINESS DECISION**).
8. **Buyer roles are granted by SQL — KNOWN LIMITATION.** There is no role-administration UI or API; a row in `customer_roles` is the grant (as for the other staff roles). The identity module now accepts `PROCUREMENT_BUYER` and `CATEGORY_MANAGER`; before this change an unknown stored role name made that user's login fail (`UserRole.valueOf`), which is also why roles must never be granted outside the enum. *Next action:* a role-management endpoint with its own audit trail.
7. **Demo accounts and POC credentials** (e.g. the inventory admin) exist only in local seed/runbook docs and must never be deployed.

## Security gate pass (2026-10-16)

The project's own review of authentication, authorization, AI tools, external retrieval, injection, data handling and workflows. **This is not a penetration test or a certification.**
Documents: [threat model](security/THREAT-MODEL.md) (threats, controls, evidence, residual risks with owners) and [observed authorization matrix](security/AUTHORIZATION-MATRIX.md) (generated from the running stack).

**Vulnerabilities and defects found and fixed in this pass**

| Finding | Severity (own judgement) | Fix | Regression test |
|---|---|---|---|
| Any signed-in customer could read `/actuator/metrics`, `/actuator/info` and every other actuator endpoint (only health should be public) | Medium | Actuator beyond health is ADMIN-only | `SecurityPolicyTest` |
| Access tokens had no issuer or audience; a validly signed token for any other purpose was accepted | Medium (depends on the shared secret) | `iss` and `aud` set by Java, required by Java and the AI service | `JwtTokenProviderTest`, `SecurityPolicyTest`, `test_route_sweep.py`, `security_e2e.py` |
| API documentation reachable in every environment (Java `/api-docs`, `/swagger-ui.html`; AI `/docs`, `/openapi.json`) | Low | Off in the production profile / `AI_ENV=prod` | `test_ops_and_config.py` |
| AI `/metrics` public; `/health/dependencies` returned models, hosts and locations to anyone | Low | `/metrics` needs an administrator or `AI_METRICS_TOKEN` outside local development; health detail for administrators only | `test_ops_and_config.py` |
| DNS rebinding gap in the external fetcher (documented earlier, now closed) | Medium | Pinned connection, per-connection address check, IPv4-mapped IPv6 handled, empty user-info refused | `test_ssrf_and_hostile_sources.py` |
| Five AI endpoints returned **500** for a malformed id (`/v1/inventory/forecast/{id}`, `/v1/inventory/regions`, `/v1/planning/scenarios`, `/scenarios/{id}`, `/pilots/{id}/assessment`) | Low (robustness; no data exposed) | Database "data exception" errors become 422 globally; the inventory resolver validates ids | `security_e2e.py` (690 hostile values), `test_ops_and_config.py` |
| Planning analysis: stored text reached the model unfenced, model output was shown unvalidated, a model error other than the expected one could fail the request, and there was no per-person limit | Low-Medium | Sanitise and fence inputs; validate output (links, markup, code, instruction-like text, length); catch every model failure; 6 analyses per person per minute | `test_tools_and_prompt_injection.py` |
| Product page and quotation source links were not restricted to http(s) | Low | Server pattern plus a UI helper | `test_ops_and_config.py`, `safeUrl.test.ts` |
| Production `axios` (12 advisories) and `react-router` (1) | High (as rated by the advisory database; browser use limits exposure) | Upgraded to axios 1.20.0 and react-router-dom 7.18.2 | `pnpm audit --prod`: no known vulnerabilities |

**Security tests added:** `SecurityPolicyTest` (Java, 33), `JwtTokenProviderTest` (+5), `tests/security/` (Python: route sweep 25, SSRF and hostile sources 64, tools and prompt injection 31, operations and configuration 17), planning object-level and concurrency tests in `test_planning_db.py` (+7), `safeUrl.test.ts` (15), and the real-stack `e2e/ai/security_e2e.py` (47 checks) and `e2e/ai/planning_audit.py` (56).

## Regression guards added for defects found

- H08 (human request lost when no open case): unit tests in `ai-service/tests/support/test_flow_products_returns.py`.
- Brute force on authentication endpoints: `RateLimitWebFilterTest` (limit, window reset, spoofed `X-Forwarded-For`, per-user ticket limit).
