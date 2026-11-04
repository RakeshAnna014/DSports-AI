# 12 — Local development

## Start the stack

```bash
docker compose up -d postgres                       # pgvector Postgres: databases dsports + dsports_ai
docker compose --profile ai up -d tika              # document extraction + OCR (needed to ingest, not to answer)

# backend (port 8080)
cd backend && mvn -B -o clean install -DskipTests
JWT_SECRET=<32+ bytes> DB_NAME=dsports java -jar bootstrap/target/dsports-bootstrap-1.0.0-SNAPSHOT.jar --spring.profiles.active=local

# AI service (port 8090)
cd ai-service && cp .env.example .env   # fill JWT_SECRET (same as the backend), AI_CUSTOMER_HASH_KEY, AI_DATABASE_URL, GOOGLE_APPLICATION_CREDENTIALS
uv sync
uv run python -m app.cli index-products            # derived product index (live catalogue -> embeddings)
uv run python -m app.cli ingest-corpus             # sample knowledge base (Tika, OCR, chunks, embeddings)
uv run uvicorn app.main:create_app --factory --port 8090

# frontend (port 3000, proxies /api and /ai-api)
cd frontend && pnpm install && pnpm dev     # the frontend is a pnpm project (npm cannot read its node_modules layout)
```

See also [../SETUP-AND-DEMO.md](../SETUP-AND-DEMO.md) for the consolidated runbook, ports, test commands and demo steps.

Or run the service in Docker: `docker compose --profile ai up -d --build ai-service` (needs `JWT_SECRET`, `AI_CUSTOMER_HASH_KEY`,
`GOOGLE_APPLICATION_CREDENTIALS` in the environment; it reaches Postgres, Tika and the host backend itself).

## Configuration (all environment variables)

| Variable | Default | |
|---|---|---|
| `JWT_SECRET`, `AI_CUSTOMER_HASH_KEY` | – (required) | ≥ 32 / ≥ 16 bytes; the first must equal the backend's |
| `DSPORTS_BASE_URL` | `http://localhost:8080` | |
| `AI_DATABASE_URL` | – | `dsports_ai` DSN; unset ⇒ memory-only state, no retrieval |
| `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT` | – | project defaults to the credential's |
| `AI_VERTEX_LOCATION` / `AI_EMBEDDING_LOCATION` | `us-central1` / same | embeddings were ~0.2 s from `asia-south1` vs ~1.7 s from `us-central1` |
| `AI_INTENT_MODEL`, `AI_REASONING_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIM` | flash-lite, flash, text-embedding-005, 768 | |
| `AI_MAX_TOKENS`, `AI_TEMPERATURE`, `AI_TOP_K`, `AI_MAX_TOOL_CALLS`, `AI_TIMEOUT_S` | 1024, 0.2, 5, 8, 30 | |
| `AI_THINKING_BUDGET` | 0 | Gemini 2.5 thinking tokens off (they ate the output budget and are billed) |
| `AI_REQUEST_TIMEOUT_S`, `AI_REQUEST_TOKEN_BUDGET`, `AI_MAX_ITERATIONS` | 90, 8000, 12 | per-request limits |
| `AI_CONFIRMATION_TTL_S`, `AI_CONVERSATION_TTL_H`, `AI_CACHE_TTL_S`, `AI_RATE_LIMIT_PER_MIN` | 300, 24, 3600, 30 | |
| `AI_TIKA_URL`, `AI_OCR_MIN_CHARS_PER_PAGE`, `AI_CHUNK_TOKENS`, `AI_CHUNK_OVERLAP_TOKENS`, `AI_RETRIEVAL_CANDIDATES`, `AI_MIN_SIMILARITY` | localhost:9998, 50, 450, 60, 20, 0.5 | RAG |
| `AI_BREAKER_FAILURES`, `AI_BREAKER_COOLDOWN_S` | 3, 30 | LLM circuit breaker |
| `AI_PRICE_TABLE_JSON` | `{}` | USD/1M tokens per model; empty ⇒ no cost estimate |
| `AI_MCP_ENABLED`, `AI_MCP_ALLOWED_HOSTS` | true, localhost | |

## Tests

```bash
cd ai-service
uv run pytest                                       # hermetic unit tests (no network)
AI_TEST_DATABASE_URL=postgresql://…/dsports_ai_test uv run pytest -m integration      # real PostgreSQL + pgvector (throwaway DB)
AI_RUN_LIVE_TESTS=1 AI_TEST_DATABASE_URL=… uv run pytest -m live                      # real Vertex, Tika+OCR, MCP over HTTP (stack must be up)
uv run python eval/run_eval.py                      # evaluation (AI service started with AI_RATE_LIMIT_PER_MIN=1000)
python e2e/ai/ai_scenarios.py                       # end-to-end scenarios A-M
```

Caveats: the Gemini/embedding calls are real and cost a little; Windows + Git Bash needs `PYTHONUTF8=1` for the ₹ sign in console
output; `docker build` must be run from PowerShell (credential helper), see the repository memory notes.
