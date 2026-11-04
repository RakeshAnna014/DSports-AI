# Operations: start-up, health, logs, shutdown, rebuild and recovery

This is the operator's view of the local/development stack. The first-time setup, seed data, role grants and demo runbook are in [SETUP-AND-DEMO.md](SETUP-AND-DEMO.md). Commands marked *(verified)* were executed successfully in this environment during the stabilization run recorded in [STATUS.md](STATUS.md); anything else is described from the scripts and is untested here.

## 1. Start-up order and readiness checks

| # | Component | Start | Ready when |
|---|---|---|---|
| 1 | PostgreSQL (pgvector) | `docker compose up -d postgres` | `docker exec dsports-postgres pg_isready -U dsports` |
| 2 | Tika + Tesseract (only for document ingestion) | `docker compose --profile ai up -d tika` | `curl localhost:9998/tika` |
| 3 | Java backend | `bash e2e/ai/start-backend.sh` (the same commands are used inline by `validate-all.sh`, which passed) | `curl localhost:8080/actuator/health` → `{"status":"UP"}`; Flyway has applied V1…V47 |
| 4 | AI service | `bash e2e/ai/start-ai.sh` *(verified)* | `curl localhost:8090/health` → `UP`; `GET /health/dependencies` shows database, Java backend, Tika and model state (administrators also see configuration) |
| 5 | Frontend | `cd frontend && pnpm exec vite` | `http://localhost:3000/` loads |

Order matters only for dependencies: the AI service can start without the backend, database or model (it reports `DEGRADED`), but every tool that needs Java will return a typed error until the backend is up. The scripts stop only this project's own previous process on the port and refuse to touch another project's process (`e2e/ai/free-port.sh`).

Required environment (names only; values live in the git-ignored `ai-service/.env` and are never printed): `JWT_SECRET` (same value for both services, at least 32 bytes), `JWT_ISSUER`, `JWT_AUDIENCE`, `AI_CUSTOMER_HASH_KEY`, `AI_DATABASE_URL`, `DSPORTS_BASE_URL`, `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT`. The backend script exports `JWT_SECRET` from `ai-service/.env` for you. Without Google credentials the service still starts; only model-backed wording and live-AI tests are unavailable (steps are reported BLOCKED, never PASS). `AI_ENV` selects `local`, `test`, `dev` or a production-like value (`prod`), which closes the docs and protects `/metrics`.

## 2. Database initialisation and migrations
* `dsports`: created by the container; Flyway migrates forward when the backend starts. Check with `docker exec dsports-postgres psql -U dsports -d dsports -c "select version, success from flyway_schema_history order by installed_rank desc limit 3"`.
* `dsports_ai`: created by `ai-service/db/init-ai-db.sql` on a fresh volume (for an existing volume pipe the file into `psql` as documented in `docker-compose.yml`); the service creates its tables idempotently at start.
* Seed commands for the AI side are in SETUP-AND-DEMO §5 and the per-area guides in [services/](services/README.md).
* There are no down-migrations. Do not drop `dsports` or `dsports_ai`; use a throwaway database for destructive work.

## 3. Logs and troubleshooting
| What | Where |
|---|---|
| Backend | `%TEMP%/dsports_backend.log` (the start script redirects stdout); each line carries `[correlationId]` |
| AI service | `%TEMP%/ai_service_main.log` (JSON lines, redacted, `correlationId`, request status, latency); overwritten at each restart |
| Postgres | `docker logs dsports-postgres` |
| Validation runs | `e2e/validation-results/<timestamp>/` (one log per step, `summary.tsv`, `SUMMARY.md`) |

To trace one request, take its `X-Correlation-Id` (response header) and search both logs; the Engineering AI page does the same over ingested logs (`python -m app.engineering.cli ingest --service backend`).

| Symptom | Cause and fix |
|---|---|
| Every AI call returns 401 | the two services have different `JWT_SECRET`, `JWT_ISSUER` or `JWT_AUDIENCE`; restart the backend with the script (it reads the secret from `.env`) |
| 403 on a staff page | the account lacks the role; roles are granted by SQL on purpose (`customer_roles`) and a token keeps its roles up to 15 minutes, so log in again |
| Backend `mvn package` fails with "Unable to rename jar" | the backend (or a leftover e2e backend on :18081) holds the jar; stop it first |
| `GET /actuator/metrics` returns 403 | actuator beyond `/actuator/health` is ADMIN-only |
| AI `/metrics` returns 401/403 | outside `AI_ENV` local/test/dev it needs an administrator token or `AI_METRICS_TOKEN` |
| Integration tests silently skip (Java) | `DOCKER_HOST` and `-Dapi.version=1.44` not set |
| Stale or empty UI modules | restart Vite |
| Port held by another project | the scripts report it and exit 1; choose another port |
| Planning forecast run failed | the previous run stays current; fix the cause and run it again; ingestion is idempotent |
| Live model steps BLOCKED / `LLM_QUOTA` | quota or billing; record as blocked, do not loop |

## 4. Safe shutdown and rebuild
* Stop: `bash e2e/ai/free-port.sh 8080` (backend), `8090` (AI), `3000` (Vite); `docker compose stop`. The backend shuts down gracefully (30 s per phase) so in-flight requests finish.
* **Rebuild the backend only after stopping it** (Windows locks the running jar): stop → `cd backend && mvn -B -o clean install -DskipTests` → `bash e2e/ai/start-backend.sh`. Never run `mvn package`/`verify` while the backend or an e2e backend on :18081 is running.
* Rebuild the AI image: `docker compose --profile ai up -d --build ai-service`.

## 5. Recovery
* **Outbox backlog** (notifications or shipments missing): rows stay `PENDING`/`PROCESSING` and are retried automatically (8 attempts); a `FAILED` row can be re-queued with `POST /api/v1/admin/outbox/{id}/retry` (ADMIN) after the cause is fixed.
* **Payment stuck `PROCESSING`**: capture is retry-safe; call capture again.
* **Unpaid order holding stock**: the expiry job cancels it after the payment window and releases the reservation.
* **Embedding model changed**: `/health/dependencies` reports `reindex_required`; run `index-products` and `ingest-corpus`.
* **Corrupted or abandoned test databases**: drop and recreate only the throwaway databases (`dsports_ai_test`, `dsports_ai_src_test`, `dsports_ai_pln_test`, `dsports_e2e_val`).

## 6. Known operational limitations
Single instance only (in-memory rate limiters, call budgets and caches); no backup, retention or restore procedure; no central log platform, tracing backend or alerting; no CI pipeline in the repository; the `local` profile and `AI_ENV=local` must never be used in a shared or production environment (dev-only secret defaults and open metrics/docs).

## 7. Command verification status (this stabilization pass, 2026-10-16)

"Verified" means the command or an inline equivalent exited 0 in the run recorded in [VALIDATION-MATRIX.md](VALIDATION-MATRIX.md). "Not re-run" means it is documented from the scripts and was not executed in this pass (it may have worked in an earlier session; do not rely on that).

| Command | Status |
|---|---|
| `bash e2e/validate-all.sh` and `--only p6-` | verified (run `20261016-135413`; `--only p6-` reruns `145251` and `164235`) |
| `bash e2e/ai/start-ai.sh` | verified (step `stack-ai`) |
| `bash e2e/ai/start-backend.sh` | not re-run (the validation script starts the backend with the same `java -jar ... --spring.profiles.active=local` line inline, step `stack-backend` verified) |
| `cd backend && mvn -B -o clean install -Dapi.version=1.44` and `mvn -B -o verify -Pintegration-test -Dapi.version=1.44` | verified (steps `java-build`, `java-integration`) |
| `mvn -B -o clean install -DskipTests` | not re-run (same build without tests) |
| `uv run pytest -q`, `uv run pytest -m integration` (three throwaway databases), `uv run pytest -m live` | verified |
| `cd frontend && pnpm exec tsc -b`, `pnpm lint`, `pnpm exec vitest run`, `pnpm build` | verified earlier on 2026-10-16 (the validation script uses `node node_modules/...` equivalents for `tsc`, `vite build` and `vitest`) |
| `pnpm exec vite` (dev server) | not re-run (the validation script starts Vite with `npm run dev`, step `stack-vite` verified) |
| `docker compose up -d postgres`, `docker compose --profile ai up -d tika`, `docker compose --profile ai up -d --build ai-service` | not re-run (the containers were already running; the image build was not exercised) |
| `uv run python -m app.cli index-products` / `ingest-corpus` | not re-run (the existing index and corpus were used) |
| `uv run python -m app.support.cli ingest-corpus` | verified (step `sup-corpus`) |
| `uv run python -m app.inventory.cli seed` / `train` | not re-run (existing mart and model run were used) |
| `uv run python -m app.engineering.cli ingest` / `synthetic` | not re-run directly; the pipeline is exercised by `eng-e2e` (24/24) |
| Planning and sourcing admin calls (`/v1/planning/ingest`, `/demo/seed`, `/forecast/runs`, `/v1/sourcing/runs`) | verified through `p6-*` and `p5-*` steps |
| Flyway check `select max(version::int) ... from flyway_schema_history` | verified: 47 migrations, head V47, all successful |
| `bash e2e/ai/free-port.sh <port>` | exercised indirectly by the start scripts |
| Docker-compose volume re-initialisation of `dsports_ai` | not run (destructive; documented from `docker-compose.yml` only) |
| Anything needing the model when credentials or quota are missing | would be reported BLOCKED by `validate-all.sh`, never PASS; credentials were available in this run |
