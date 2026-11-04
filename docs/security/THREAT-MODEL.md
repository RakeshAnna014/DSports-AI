# Threat model (project's own review, 2026-10-16)

Scope: the Java backend, the Python AI service (REST, MCP, tools, planning, sourcing, inventory, engineering, support), the React app, the databases, and the external retrieval pipeline.
This is the project's own analysis and testing. **It is not a penetration test, an external audit or a certification**, and a passing test suite is evidence of tested behaviour, not proof that the system has no vulnerability.

## Assets
Customer identities, orders, payments and addresses; staff roles and approval authority; sales plans, forecasts, targets and approvals; stock positions, supplier terms and quotations; the signing secret; model-provider credentials; audit records.

## Actors and trust boundaries
Anonymous visitor, customer, support executive, sales planner, store manager (scoped), regional manager (scoped), procurement buyer, category manager, administrator; the language model (never trusted); external web sources and supplier pages (never trusted); a compromised browser (XSS).

```
Browser ──/api──► Java :8080 (authoritative: commerce data, identity, roles) ──► PostgreSQL dsports
   └──/ai-api──► AI service :8090 ──REST with the caller's own token──► Java
                        ├──► PostgreSQL dsports_ai (plans, forecasts, sourcing, audit)
                        ├──► model provider (receives only fenced, minimised text)
                        └──► allow-listed external sites (SafeFetcher: allow-list, IP checks, pinned connection, caps)
```

## Threats, controls and the evidence

| # | Threat | Where | Control | Evidence (test) | Status |
|---|---|---|---|---|---|
| T1 | Anonymous access to a protected route | Java filter chain; every AI route | Default `authenticated()`; per-area role checks before any state is read | `SecurityPolicyTest` (33), `test_route_sweep.py` (every operation of the OpenAPI document, 25), `security_e2e.py` | Fixed/verified |
| T2 | Forged, expired, unsigned, tampered, foreign-audience or foreign-issuer token | Java `JwtTokenProvider`; AI `verify_token` | HS algorithm family only, `exp`, `sub`, **`iss` and `aud` required on both sides** (added this pass) | `JwtTokenProviderTest`, `SecurityPolicyTest`, `test_route_sweep.py` (11 bad-credential kinds), `security_e2e.py` (9 forged tokens against both services) | Fixed/verified |
| T3 | Privilege escalation by a client-supplied role, id or header | Java register/login; AI | Roles only from the signed token (which comes from the database); `X-User-Id`/`X-Roles` ignored; register ignores role fields; role names matched exactly | `security_e2e.py` (mass assignment, forged headers), `test_route_sweep.py` (wrong-case and wrong-shape roles) | Verified |
| T4 | Object-level access (another customer's order, another store's plan line) | Java ownership checks; planning scope | Owner checks in Java; `pln_scope` applied in every list, detail, edit, investigate, decide, analyse and bulk path | `api-e2e.sh` (cross-customer), `test_planning_db.py` (scoped manager, region, analysis, proposals, bulk), `planning_audit.py`, `security_e2e.py` | Verified |
| T5 | Approval bypass, self-approval, stale or racing decisions | Planning workflow | Server-side role, separation of duties, row lock plus version check, audit in the same transaction | `test_planning_db.py` (concurrent approvers, replay, self-approval, frozen plan), unit tests | Verified |
| T6 | Operational data exposed to any signed-in user (metrics, docs, health detail) | Java actuator; AI `/metrics`, `/docs`, `/health/dependencies` | Actuator beyond health is ADMIN-only; AI metrics need an administrator or a scrape token outside local development; docs off in production; health detail for administrators only (**fixed this pass**) | `SecurityPolicyTest`, `test_ops_and_config.py` | Fixed/verified |
| T7 | Prompt injection through user text, retrieved documents, supplier pages, stored names | Customer AI, support, engineering, planning analysis | Instruction-like text removed and the rest fenced as `<untrusted_data>`; model output validated (no links, markup, code, instruction-like text, length); numbers grounded in tool results; the model has no write tool | `test_tools_and_prompt_injection.py` (8 payloads, 10 hostile outputs), eval suites, `security_e2e.py` (live model, 5 attacks, counts unchanged) | Verified |
| T8 | Unauthorised or unsafe tool use | Tool registry, MCP facade | Allow-list; strict argument schemas (extra fields rejected); per-tool role gate; internal tools read-only; writes need a single-use confirmation; call budget | `test_tools_and_prompt_injection.py` (every tool against every role), `test_mcp.py` | Verified |
| T9 | SSRF and hostile external content | `app/sourcing/fetch.py`, providers, images | Host allow-list; scheme, user-info, port checks; every resolved address must be public (incl. IPv4-mapped IPv6); redirects followed by hand and re-checked per hop; **connection pinned to the validated address with the real Host and TLS name (DNS rebinding, added this pass)**; size, type, time caps; robots; page text sanitised; images by magic bytes | `test_ssrf_and_hostile_sources.py` (64) | Fixed/verified |
| T10 | SQL injection and malformed input | Repositories, query parameters, bodies | Parameterised SQL; sort fields from allow-lists; typed models; database "data exception" errors become 422 (**fixed this pass**: five endpoints returned 500 for a malformed id) | `security_e2e.py` (690 hostile query/path values, 360 hostile bodies, 9 Java probes) | Fixed/verified |
| T11 | Secrets and personal data in logs, errors, prompts | Logging, error handlers, LLM prompts | Redaction filter; generic error bodies; no first-party stack traces to clients; names only to the model; address, phone, e-mail, surname never sent | `test_observability.py`, `test_ops_and_config.py`, `test_tools_and_prompt_injection.py` (7 customer messages), `security_e2e.py` (service logs searched for this run's tokens, passwords, e-mail addresses) | Verified |
| T12 | Stored link used for script injection | Product Intelligence | Server accepts only http(s) links; UI renders only http(s) links (`safeHref`); no `dangerouslySetInnerHTML` | `test_ops_and_config.py`, `safeUrl.test.ts` | Fixed/verified |
| T13 | Vulnerable dependencies | Frontend, Python, Java | `pnpm audit`: production dependencies upgraded (axios, react-router) to zero known advisories | `pnpm audit --prod` | Frontend verified; see residual risks R5, R6 |
| T14 | Cost and availability abuse of the model | Chat, planning analysis | Per-customer chat limiter; per-person analysis limiter; token and call budgets; timeouts and bounded retries; failure falls back to the deterministic result | `test_tools_and_prompt_injection.py`, existing LLM-layer tests | Verified |
| T15 | Brute force on login | Java rate limiter | Per-address window | `RateLimitWebFilterTest` | Verified (per instance) |

## Residual risks (not fixed, with owner and mitigation)

| # | Risk | Impact | Mitigation now | Owner / next step |
|---|---|---|---|---|
| R1 | Access and refresh tokens are kept in `localStorage` | Any XSS reads them | No injection sinks found; links sanitised; short access-token life | Frontend/identity: HttpOnly cookie session with a CSRF design (ADR first) |
| R2 | One shared HS256 secret between Java and the AI service | A compromise of the AI service could mint tokens | Secret only from the environment, 32+ bytes enforced, `iss`/`aud` required | Identity: asymmetric signing |
| R3 | Roles in a token live until it expires (15 minutes) | A removed role works for up to 15 minutes | Short expiry | Identity: token revocation list or per-request role lookup |
| R4 | Database accounts are not least-privilege (one owner account per database, also used for migrations) | A code defect has broad database reach | Parameterised SQL, tests | Platform: separate migration and runtime roles |
| R5 | Development-only dependencies with advisories (vitest 3.x line and its helpers `tinypool`, `braces`) | Only affects a developer machine; not shipped; the Vitest UI server is not used | Not exposed; `pnpm audit --prod` is clean | Frontend: move to vitest 4 |
| R6 | Java and Python dependency scanners were **not run** (no scanner is configured or available offline) | Unknown advisories in those trees | None | Platform: add OWASP dependency-check and `pip-audit` to CI |
| R7 | Rate limiters are in-memory and per instance | Limits multiply with instances | Documented | Platform: shared store |
| R8 | Retention and deletion of conversations, research results and audit records is not defined | Unbounded growth, privacy obligations unmet | None | Product/legal: decide retention periods |
| R9 | The model provider receives prompt text (fenced, minimised) | Data-processing terms are a business decision | Minimisation tested | Legal: confirm terms |
| R10 | Local development leaves `/metrics` and the API docs open and the `local` profile has a documented dev-only secret | Must never run with `AI_ENV=local` or the local profile in production | Production profile disables docs; metrics are protected outside `local`/`test`/`dev` | Platform: add a startup guard that refuses `local` against a non-local database |
| R11 | An allow-listed domain whose owner turns hostile can still serve hostile content | Content is treated as untrusted data, but the host is trusted for reachability | Allow-list of a few named hosts, caps, sanitising | Buyer admin: review the allow-list |
