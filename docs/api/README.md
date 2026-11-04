# API reference

| Document | Service | Source |
|---|---|---|
| [java-backend.md](java-backend.md) | Java commerce backend, port 8080, 159 operations | generated from the running backend's `/api-docs` |
| [ai-service.md](ai-service.md) | Python AI service, port 8090, 135 operations | generated from the app factory's OpenAPI document |
| [ai-tools.md](ai-tools.md) | AI tool registry, 65 tools (also exposed over MCP) | generated from `app/tools/build.py` |

Regenerate (with the services built and, for Java, running):

```bash
curl -s localhost:8080/api-docs > scratch_shots/java-openapi.json
python tools/docs/openapi_to_md.py scratch_shots/java-openapi.json "Java commerce backend REST API (Spring WebFlux, port 8080)" "<note>" > docs/api/java-backend.md
cd ai-service && uv run python -c "import json; from app.main import create_app; from tests.conftest import make_settings; print(json.dumps(create_app(make_settings()).openapi()))" > ../scratch_shots/ai-openapi.json
python ../tools/docs/openapi_to_md.py ../scratch_shots/ai-openapi.json "AI service REST API (Python / FastAPI, port 8090)" "<note>" > ../docs/api/ai-service.md
cd ai-service && uv run python ../tools/docs/tool_matrix_md.py > ../docs/api/ai-tools.md
```

Interactive documentation exists only outside production: Java `/swagger-ui.html` and `/api-docs`, AI `/docs`. The generated tables list method, path, summary, whether the OpenAPI document declares a bearer requirement, and response codes. **The OpenAPI documents do not describe role rules**; those are in [../services/java-backend.md](../services/java-backend.md) §7, [ai-tools.md](ai-tools.md) and the observed [authorization matrix](../security/AUTHORIZATION-MATRIX.md). Request and response schemas are in the live OpenAPI JSON (`components.schemas`); they are not copied here to avoid drift.

## Conventions
* JSON everywhere. Authenticated calls send `Authorization: Bearer <access token>`; the same token works for both services. Send `X-Correlation-Id` to correlate logs (it is created if absent and returned in the response).
* Money amounts are decimals with a currency code; timestamps are ISO-8601 UTC.
* Idempotent writes accept an `Idempotency-Key` header (refund requests, returns, confirmed assistant actions).
* Errors use one envelope in both services. Validation errors add `validationErrors`; unexpected errors never include internals. Malformed input is a 4xx, never a 500.

## Examples (recorded from the running stack on 2026-10-16; no credentials or personal data)

Public catalogue (Java), `GET /api/catalog/products?size=1`:
```json
[{"id":"00000000-0000-0000-0000-000000001051","sku":"CR-ARM-SG-051","name":"SG Cricket Arm Guard - Pro",
  "slug":"sg-cricket-arm-guard-pro","status":"ACTIVE","imageUrl":"/products/cricket/sg-cricket-arm-guard-pro-1.svg","images":["..."],
  "brandId":"...","categoryId":"...","sportId":"...","createdAt":"2026-10-08T04:55:10.741199Z","updatedAt":"2026-10-08T04:55:10.741199Z"}]
```

Authentication required (Java), `GET /api/v1/orders` without a token, HTTP 401:
```json
{"status":401,"error":"Unauthorized","code":"AUTHENTICATION_REQUIRED","message":"Authentication required",
 "path":"/api/v1/orders","correlationId":"5c6892d4-...","timestamp":"2026-10-16T08:39:22Z","validationErrors":null}
```

Validation error (Java), `POST /api/auth/login` with `{}`, HTTP 400:
```json
{"status":400,"error":"Bad Request","code":"VALIDATION_ERROR","message":"Validation failed","path":"/api/auth/login",
 "correlationId":"f6b00e43-...","timestamp":"2026-10-16T08:39:22Z",
 "validationErrors":[{"field":"password","message":"must not be blank"},{"field":"email","message":"must not be blank"}]}
```

Authentication required (AI service), `GET /v1/me` without a token, HTTP 401:
```json
{"status":401,"error":"Unauthorized","code":"UNAUTHORIZED","message":"Authentication is required","path":"/v1/me",
 "correlationId":"0c3c6cbc-...","timestamp":"2026-10-16T08:39:22.581+00:00"}
```

Assistant, guest asking for account data (AI service), `POST /v1/chat` with `{"message":"show my orders"}`, HTTP 200 (trimmed to the main fields; `meta` also carries tool calls, token counts, grounding and `routeSource`):
```json
{"conversationId":"9fc6fbc5-...","status":"needs_login","intent":"ORDERS_LIST","tier":0,
 "message":"Please sign in to use that. I can still help you find products and answer questions about DSports without an account.",
 "messageSource":"deterministic","products":[],"orders":[],"requiresConfirmation":false,"confirmationToken":null,
 "actions":[],"sources":[],"meta":{"llmCalls":0,"tokensIn":0,"tokensOut":0,"routeSource":"rules"}}
```
`tier: 0` and `llmCalls: 0` show that the answer came from the rule router without a model call.
