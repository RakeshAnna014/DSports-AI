# 03 — Infrastructure

## Local development architecture

```text
docker compose
  postgres  pgvector/pgvector:pg16-trixie   :5432   databases: dsports, dsports_ai
  tika      apache/tika:latest-full         :9998   profile "ai"  (Tika 4.1.0 + Tesseract 5.5.0)

host
  backend   java -jar dsports-bootstrap.jar (profile local)   :8080   -> dsports
  frontend  vite dev server                                    :3000   proxies /api -> :8080
  ai-service  (foundation, Phase B; compose profile "ai")      :8090   -> dsports_ai, DSports REST, Vertex AI
```

```bash
docker compose up -d postgres                 # database
docker compose --profile ai up -d tika        # document extraction + OCR (only needed for RAG ingestion)
```

## PostgreSQL with pgvector

- Image `pgvector/pgvector:pg16-trixie` replaces `postgres:16`. **Same major version (16) and same glibc (2.41) as
  `postgres:16`**, and the data volume `postgres-data` is reused unchanged.
- The glibc match matters: the plain `pgvector/pgvector:pg16` tag is Debian *bookworm* (glibc 2.36). Opening a
  database created on glibc 2.41 with it prints a *collation version mismatch* warning and risks inconsistent text
  indexes. The `-trixie` tag avoids that.
- `vector` 0.8.7 is available in the image. It is created in `dsports_ai` only; `dsports` does not load it.

## The `dsports_ai` database

Created by `ai-service/db/init-ai-db.sql` (idempotent, `\gexec`). On a **fresh** volume Postgres runs it
automatically (mounted into `docker-entrypoint-initdb.d`). On an **existing** volume init scripts do not run, so:

```bash
docker exec -i dsports-postgres psql -U dsports -d dsports < ai-service/db/init-ai-db.sql
```

The AI service creates its own minimal schema at startup (`ai_meta`, `conversations`, `audit`, `usage`; see
[04-ai-service.md](04-ai-service.md)). There are no embeddings, product index or RAG tables yet; the RAG phase adds them
(and `CREATE EXTENSION vector`).

## Tika

`apache/tika:latest-full` bundles Tesseract (languages include `eng`, `deu`, `fra`), so no local OCR install is needed.
It is under the compose profile `ai`, so `docker compose up` for normal development does not start it. The plan is to
call it only for document ingestion, and to run OCR only for pages whose extracted text is below a threshold.
`latest-full` is a moving tag — pin a digest before any production use.

## Configuration rules

No new secrets were introduced. `POSTGRES_PASSWORD` can now be overridden (`${POSTGRES_PASSWORD:-dsports123}`); the
default remains the existing **local-only** development password.

## Flyway on an old local database

The backend validates migration checksums (the former `validate-on-migrate: false` shortcut was removed in the
hardening phase). A local `dsports` database created before 2026-07-23 recorded an edited V19
(`create checkout tables`, checksum `82320501`); the repository's V19 has checksum `-1226301269`. Such a database
fails startup with *Migration checksum mismatch for migration version 19*. This is **pre-existing and unrelated to
V39–V42**. Fix: recreate the database, or on that database only run
`UPDATE flyway_schema_history SET checksum = -1226301269 WHERE version = '19';`. This only rewrites the recorded checksum; it was verified on a copy of such a database, where the remaining migrations then applied cleanly (back up first, and check that `checkouts` has the columns V19/V20 define).

## Verification record

Performed on 2026-10-07 against real PostgreSQL (pgvector image) and real Docker containers.

| Check | Result |
|---|---|
| Existing `dsports` data after image change | preserved (22 Flyway rows, 50 products, 3 customers) |
| Collation warning after switching to `pg16-trixie` | none |
| `vector` extension | 0.8.7 available; `CREATE EXTENSION` and a distance query succeed in `dsports_ai` |
| `dsports_ai` creation | created; re-running the script is a no-op |
| Clean database, V1→V42 | 42 migrations applied, 42 successful, no duplicate versions, no warnings |
| Existing database copy (V22 → V42) | after the V19 checksum fix above: 20 migrations applied, customers/products preserved, prices converted to INR |
| Backend unit tests (`mvn clean install`) | 524 run, 0 failed, 0 errors, 0 skipped |
| Integration tests (`-Pintegration-test`, Testcontainers) | 22 run, 0 failed, 0 errors, 0 skipped (7 are new Returns tests) |
| `e2e/api-e2e.sh` on a fresh database | 122 passed, 0 failed (72 existing + 50 Returns) |
| Tika | container starts, `/version` = Apache Tika 4.1.0, Tesseract 5.5.0 present |
| Application start | starts and reports `UP` on the migrated database |
| Frontend `tsc -b` | passes |

The advisory-lock concurrency tests were checked for sensitivity: with `lockOrderLine` disabled both concurrency
tests fail; with it enabled they pass.
