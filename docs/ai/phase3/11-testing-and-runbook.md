# 11. Testing and runbook

## Prerequisites

Docker (PostgreSQL with pgvector, Tika), Java 21 + Maven, Node, Python 3.13 + uv, Chrome, Google credentials for Gemini, `ai-service/.env`. Windows: run bash scripts with
`& 'C:\Program Files\Git\bin\bash.exe' e2e/<script>.sh`. Testcontainers needs `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` and the quoted `'-Dapi.version=1.44'`, otherwise Docker tests skip silently.

## Start the stack

```
bash e2e/ai/start-backend.sh        # Java :8080 (Flyway applies V44 forward-only; the real database is never recreated)
cd ai-service && uv run python -m app.support.cli ingest-corpus && uv run python -m app.cli ingest-corpus
bash e2e/ai/start-ai.sh             # AI service :8090 (start detached)
cd frontend && node node_modules/vite/bin/vite.js    # :3000
```
`e2e/ai/free-port.sh <port>` stops only this project's processes; other projects holding 8081/8090 are never touched.

## Test suites (counts are measured; see the final report for the run that produced them)

| Suite | Command | Count at last measurement |
|---|---|---|
| Java unit + module tests | `cd backend && mvn -B -o clean install '-Dapi.version=1.44'` | **546 run, 0 failed, 0 skipped** (support: 20) |
| Java integration (Testcontainers) | `mvn -B -o verify -Pintegration-test '-Dapi.version=1.44'` | **36 run, 0 failed** (support 14, returns 7, identity 7, inventory 4, order 4) |
| Python unit | `cd ai-service && uv run pytest tests` | **1084 passed**, 142 live tests deselected (support: `tests/support/`, 164 in that step incl. Phase 2 neighbours) |
| Python real-PG | `uv run pytest tests/integration/test_support_documents_db.py` | 5 |
| Python live (running stack) | `uv run pytest tests/live/test_stack_support.py` | 11 passed |
| Frontend | `cd frontend && node node_modules/typescript/bin/tsc -b && node node_modules/vite/bin/vite.js build` | tsc exit 0, build OK (no unit-test framework exists in the project) |
| Browser | `node e2e/support-ui.mjs scratch_shots/world.json scratch_shots` | 23 steps, all passed on the real stack |
| Evaluation | `uv run python -u eval/run_support_eval.py --suite dev` | 45/45 on the final code, gates met; held-out regression 23/24 (H08); see doc 10 |
| Live (everything) | `AI_RUN_LIVE_TESTS=1 uv run pytest -m live` (needs `AI_TEST_DATABASE_URL`) | support 11/11; Vertex 4/4; see doc 14 for the original run |
| Everything | `bash e2e/validate-all.sh` (`--only sup-`, `--skip-build`) | steps `sup-*` added; honest PASS/FAIL/BLOCKED/SKIP per step |

## Troubleshooting

- **Replies come back "out of scope" for ordinary complaints**: the intent model hit `LLM_QUOTA`. Check `outcome` in the service usage log. Wait for quota; evaluation results during a quota block are not valid.
- **Backend jar locked on rebuild**: stop the old backend (`e2e/ai/free-port.sh 8080`).
- **Evaluation blames the assistant for order changes**: the backend cancels unpaid orders after ~30 minutes; do not run long evaluations on an old world, and do not create orders during one.
- **UTF-8 BOM** from PowerShell `Set-Content -Encoding utf8` breaks javac/YAML; use the editor or `-Encoding utf8NoBOM`.
- Do not edit `validate-all.sh` while it is running.
