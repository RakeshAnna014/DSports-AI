#!/usr/bin/env bash
# DSports-AI consolidated validation: Phase 1 (customer AI commerce) + Phase 2 (inventory intelligence) on the REAL integrated stack.
#
#   bash e2e/validate-all.sh                 full run (about 60 minutes)
#   bash e2e/validate-all.sh --skip-build    skip the two Maven builds (about 45 minutes)
#   bash e2e/validate-all.sh --only inv-     run only the steps whose name starts with a prefix (e.g. inv-, py-, java-)
#   bash e2e/validate-all.sh --with-holdout2 also re-run the CONSUMED holdout v2 (informational only: it is no longer an unseen set)
#
# Honest reporting: every step ends as PASS (exit 0 AND its success marker found in the log), FAIL, BLOCKED (a precondition such as Docker or Gemini is missing:
# an ENVIRONMENT problem, not an application result) or SKIP (explicitly not run). Nothing is ever reported as passing when it did not execute.
# Results: e2e/validation-results/<timestamp>/ (one log per step, summary.tsv, SUMMARY.md). Nothing is committed. The real DSports database is never dropped or recreated.
# Needs: Docker (postgres with pgvector, tika), Java 21 + Maven, Node, Python + uv, Chrome, Google credentials for Gemini, ai-service/.env (never printed).
cd "$(dirname "$0")/.." || exit 1
ROOT="$(pwd)"
SKIP_BUILD=0; ONLY=""; HOLDOUT2=0
while [ $# -gt 0 ]; do case "$1" in --skip-build) SKIP_BUILD=1;; --only) ONLY="$2"; shift;; --with-holdout2) HOLDOUT2=1;; *) echo "unknown option $1"; exit 2;; esac; shift; done

export PYTHONUTF8=1 DOCKER_HOST="${DOCKER_HOST:-npipe:////./pipe/dockerDesktopLinuxEngine}"
TS="$(date +%Y%m%d-%H%M%S)"; OUT="$ROOT/e2e/validation-results/$TS"; mkdir -p "$OUT"; TSV="$OUT/summary.tsv"; : > "$TSV"
TMPD="${TEMP:-/tmp}"
# Git Bash hands Windows child processes (pnpm.cmd, python subprocess, cmd.exe) a truncated PATH when it is very long, so they cannot find node/docker/uv.
# Putting the directories of the tools this script needs first keeps them findable. Local to this process; nothing on disk or in the user's PATH changes.
for _t in node docker uv python; do _p="$(command -v "$_t" 2>/dev/null)" && PATH="$(dirname "$_p"):$PATH"; done; export PATH
E2E_PORT=18081     # a port no other local project uses (8081 collides with other Spring services on a shared machine)
say() { printf "[%s] %s\n" "$(date +%H:%M:%S)" "$*"; }
record() { printf "%s\t%s\t%s\t%s\t%s\n" "$1" "$2" "$3" "$4" "$5" >> "$TSV"; say "$2  $1  ${5:+($5)}"; }
wanted() { [ -z "$ONLY" ] || [[ "$1" == "$ONLY"* ]]; }
# relevant PREFIX: setup/teardown that only matters when a step starting with PREFIX is selected (so --only never has side effects outside its subset)
relevant() { [ -z "$ONLY" ] || [[ "$1" == "$ONLY"* || "$ONLY" == "$1"* ]]; }

# step NAME 'success regex or -' command...   (runs in $ROOT; logs to $OUT/NAME.log)
step() {
  local name="$1" marker="$2"; shift 2
  wanted "$name" || { record "$name" SKIP 0 0 "not selected"; return; }
  say "START $name"; local t0=$SECONDS
  "$@" > "$OUT/$name.log" 2>&1; local rc=$?; local dt=$((SECONDS - t0))
  local note=""; local status=PASS
  if [ $rc -ne 0 ]; then status=FAIL; note="exit code $rc"
  elif [ "$marker" != "-" ] && ! grep -qE "$marker" "$OUT/$name.log"; then status=FAIL; note="exit 0 but success marker /$marker/ not found"; fi
  record "$name" "$status" "$rc" "$dt" "$note"
}
blocked() { record "$1" BLOCKED 0 0 "$2"; }
skip()    { record "$1" SKIP 0 0 "$2"; }
port_up() { local h=127.0.0.1; [ "$1" = 3000 ] && h=localhost; curl -s -m3 -o /dev/null "http://$h:$1$2"; }      # Vite listens on IPv6 (localhost) only; the services on IPv4
kill_port() { bash e2e/ai/free-port.sh "$1" >> "$OUT/foreign-ports.log" 2>&1; }      # only this project's own processes are stopped; a foreign holder is reported, never killed
start_backend() {
  kill_port 8080; sleep 2
  export JWT_SECRET="$(grep '^JWT_SECRET=' ai-service/.env | cut -d= -f2-)"
  ( cd backend && DB_NAME=dsports nohup java -jar bootstrap/target/dsports-bootstrap-1.0.0-SNAPSHOT.jar --spring.profiles.active=local > "$TMPD/dsports_backend.log" 2>&1 & )
  for _ in $(seq 1 90); do curl -s -m2 http://127.0.0.1:8080/actuator/health | grep -q UP && return 0; sleep 2; done; return 1
}
start_ai() { bash e2e/ai/start-ai.sh; }
start_vite() { port_up 3000 / || powershell -NoProfile -c "Start-Process -FilePath npm.cmd -ArgumentList 'run','dev' -WorkingDirectory '$ROOT/frontend' -WindowStyle Hidden"; for _ in $(seq 1 30); do port_up 3000 / && return 0; sleep 1; done; return 1; }
json() { python -c "import sys,json;print(json.load(sys.stdin)['$1'])" | tr -d '\r'; }

# ============================================================================================================ 0. environment
say "validation run $TS -> $OUT"
{ echo "date: $(date)"; echo "branch: $(git branch --show-current)"; echo "head: $(git log --oneline -1)"; git status --short | wc -l | xargs echo "changed paths:"; java -version 2>&1 | head -1; node --version | xargs echo node; python --version; uv --version; } > "$OUT/environment.txt" 2>&1
DOCKER_OK=0; docker ps > /dev/null 2>&1 && DOCKER_OK=1
GEMINI_OK=0
if [ -f ai-service/.env ]; then ( cd ai-service && uv run python -m app.llm.smoke > "$OUT/preflight-gemini.log" 2>&1 ) && grep -q "reachable=True" "$OUT/preflight-gemini.log" && GEMINI_OK=1; fi
[ $DOCKER_OK = 1 ] && record env-docker PASS 0 0 || record env-docker BLOCKED 0 0 "docker is not reachable: Testcontainers, database and Tika steps cannot run"
[ $GEMINI_OK = 1 ] && record env-gemini PASS 0 0 || record env-gemini BLOCKED 0 0 "Gemini/Vertex is not reachable: every step that needs the model is BLOCKED (not failed)"

# ============================================================================================================ 1. backend (Java)
if [ $SKIP_BUILD = 1 ]; then skip java-build "--skip-build"; skip java-integration "--skip-build"
elif [ $DOCKER_OK = 0 ]; then blocked java-build "needs Docker (Testcontainers) for 29 container tests; running without it would silently skip them"; blocked java-integration "needs Docker"
else
  # The build replaces the jar, so the running backend must stop first: but only when a Java step is actually selected (a --only run of another prefix must not kill the stack).
  if relevant java-; then kill_port 8080; sleep 2; fi
  step java-build 'BUILD SUCCESS' bash -c 'cd backend && mvn -B -o clean install -Dapi.version=1.44'
  step java-integration 'BUILD SUCCESS' bash -c 'cd backend && mvn -B -o verify -Pintegration-test -Dapi.version=1.44'
  python e2e/validate-count-java.py "$OUT/java-build.log" > "$OUT/java-test-count.txt" 2>&1 || true
fi

# ============================================================================================================ 2. bring the stack up
[ $DOCKER_OK = 1 ] && { docker exec dsports-postgres pg_isready -U dsports > /dev/null 2>&1 || docker compose up -d postgres tika > "$OUT/compose.log" 2>&1; }
step stack-backend - start_backend
step stack-ai - start_ai
step stack-vite - start_vite
STACK_OK=1; port_up 8080 /actuator/health && port_up 8090 /health && port_up 3000 / || STACK_OK=0
if [ $STACK_OK = 0 ]; then say "the stack is not up: remaining steps are BLOCKED"; fi

# ============================================================================================================ 3. API E2E on a fresh database
if ! relevant api-e2e; then :
elif [ $DOCKER_OK = 1 ] && [ $STACK_OK = 1 ]; then
  if ! bash e2e/ai/free-port.sh $E2E_PORT > "$OUT/api-e2e-port.log" 2>&1; then blocked api-e2e "port $E2E_PORT is held by another project's process (see api-e2e-port.log); not touching it"; else
  docker exec dsports-postgres psql -U dsports -d postgres -c "DROP DATABASE IF EXISTS dsports_e2e_val" -c "CREATE DATABASE dsports_e2e_val" > "$OUT/api-e2e-db.log" 2>&1
  ( cd backend && DB_NAME=dsports_e2e_val nohup java -jar bootstrap/target/dsports-bootstrap-1.0.0-SNAPSHOT.jar --spring.profiles.active=local --server.port=$E2E_PORT > "$TMPD/dsports_backend_e2e.log" 2>&1 & )
  for _ in $(seq 1 90); do curl -s -m2 http://127.0.0.1:$E2E_PORT/actuator/health | grep -q UP && break; sleep 2; done
  step api-e2e 'RESULT: 122 passed, 0 failed' bash e2e/api-e2e.sh dsports_e2e_val $E2E_PORT
  kill_port $E2E_PORT
  fi
else blocked api-e2e "needs Docker and the running stack"; fi

# ============================================================================================================ 4. Python
step py-unit '[0-9]+ passed' bash -c 'cd ai-service && uv run pytest -q'
TESTDB="$(grep '^AI_DATABASE_URL=' ai-service/.env | cut -d= -f2- | sed 's#/dsports_ai$#/dsports_ai_test#')"
if [ $DOCKER_OK = 1 ]; then
  step py-integration '[0-9]+ passed' env AI_TEST_DATABASE_URL="$TESTDB" bash -c 'cd ai-service && uv run pytest -m integration -q'
else blocked py-integration "needs the PostgreSQL container"; fi
if [ $STACK_OK = 1 ] && [ $GEMINI_OK = 1 ]; then step py-live '[0-9]+ passed' env AI_RUN_LIVE_TESTS=1 AI_TEST_DATABASE_URL="$TESTDB" bash -c 'cd ai-service && uv run pytest -m live -q'
else blocked py-live "needs the stack and Gemini"; fi

# ============================================================================================================ 5. frontend
step fe-tsc 'TSC_OK' bash -c 'cd frontend && node node_modules/typescript/bin/tsc -b && echo TSC_OK'
step fe-build 'built in' bash -c 'cd frontend && node node_modules/typescript/bin/tsc -b && node node_modules/vite/bin/vite.js build'
[ $STACK_OK = 1 ] && step fe-catalog 'Catalog validation passed' bash -c 'cd frontend && node scripts/validate-catalog.mjs --live' || blocked fe-catalog "needs the backend"

# ============================================================================================================ 6. inventory mart present?
MART=0
if [ $STACK_OK = 1 ]; then ( cd ai-service && uv run python -m app.inventory.cli status > "$OUT/mart-status.log" 2>&1 ) && grep -q '"runs"' "$OUT/mart-status.log" && ! grep -q '"runs": 0' "$OUT/mart-status.log" && MART=1; fi
if [ $MART = 0 ] && [ $STACK_OK = 1 ] && relevant inv-; then
  step inv-seed 'demand_rows' bash -c 'cd ai-service && uv run python -m app.inventory.cli seed'
  step inv-train 'run_id' bash -c 'cd ai-service && uv run python -m app.inventory.cli train'
  step inv-corpus - bash -c 'cd ai-service && uv run python -m app.inventory.cli ingest-corpus'
fi

# ============================================================================================================ 7. Phase 1 customer journeys (real Chrome, real Gemini)
if [ $STACK_OK = 1 ] && [ $GEMINI_OK = 1 ]; then
  relevant cust-journeys && bash e2e/ai/failure-instances.sh start > "$OUT/failure-instances.log" 2>&1
  rm -f scratch_shots/ui-done.flag; mkdir -p scratch_shots
  step cust-journeys 'ui-done' bash -c 'bash e2e/run-ai-ui.sh ../scratch_shots; echo ui-done'
  for j in drawer commerce context redesign; do
    wanted cust-journeys && { grep -q "RESULT: all steps passed" "scratch_shots/ui-$j.log" && record "cust-journey-$j" PASS 0 0 "" || record "cust-journey-$j" FAIL 1 0 "see scratch_shots/ui-$j.log"; cp "scratch_shots/ui-$j.log" "$OUT/cust-journey-$j.log" 2>/dev/null; }
  done
  # M12 needs a delivered order inside the 7-day return window; seeded demo orders age out, so one fresh DEMO order is added when needed (additive, see the script)
  step cust-multisport-prep 'PREP_OK' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 python refresh_cricket_return_order.py'
  step cust-multisport 'RESULT: all steps passed' bash -c 'cd e2e && node ai-multisport.mjs ../scratch_shots'
  relevant cust-journeys && bash e2e/ai/failure-instances.sh stop > /dev/null 2>&1
  step scenarios-A-M 'RESULT: 64 passed, 0 failed' python e2e/ai/ai_scenarios.py
  step eval-main '"cases_passed": 83' bash -c 'cd ai-service && uv run python eval/run_eval.py'
  step eval-holdout1 '"cases_passed": 26' bash -c 'cd ai-service && uv run python eval/run_eval.py --cases eval/holdout.yaml'
else blocked cust-journeys "needs the stack and Gemini"; blocked cust-multisport "needs the stack"; blocked scenarios-A-M "needs the stack and Gemini"; blocked eval-main "needs Gemini"; blocked eval-holdout1 "needs Gemini"; fi
if [ $STACK_OK = 1 ]; then
  step cust-baseline-flow 'no console errors, page errors or 5xx' bash -c "cd e2e && node ui-flow.mjs happy flow-\$RANDOM\$RANDOM@example.com 00000000-0000-0000-0000-000000001005"
else blocked cust-baseline-flow "needs the stack"; fi

# ============================================================================================================ 8. Phase 2 inventory
step inv-unit '[0-9]+ passed' bash -c 'cd ai-service && uv run pytest tests/inventory tests/unit/test_inventory_copilot.py tests/unit/test_inventory_security.py tests/unit/test_inventory_guards.py tests/unit/test_inventory_authz_matrix.py -q'
step inv-eval-numeric-dev 'passed ->' bash -c 'cd ai-service && uv run python eval/run_inventory_eval.py --suite numeric-dev'
step inv-eval-numeric-holdout 'passed ->' bash -c 'cd ai-service && uv run python eval/run_inventory_eval.py --suite numeric-holdout'
if [ $STACK_OK = 1 ] && [ $GEMINI_OK = 1 ] && port_up 8090 /health; then
  step inv-measure '"findings": \[\]' bash -c 'cd e2e/ai && PYTHONPATH=. python inventory_measure.py'
  step inv-eval-dev 'thresholds missed: none' bash -c 'cd ai-service && uv run python -u eval/run_inventory_eval.py --suite dev'
  [ $HOLDOUT2 = 1 ] && step inv-eval-holdout2-rerun 'turns fully passed' bash -c 'cd ai-service && uv run python -u eval/run_inventory_eval.py --suite holdout2 --out eval/results/rerun' || skip inv-eval-holdout2 "consumed set; its first run is preserved (use --with-holdout2 to re-run for information)"
  if relevant inv-browser || relevant inv-failures; then INV="$(PYTHONPATH=e2e/ai python e2e/ai/inventory_setup.py)"; A="$(echo "$INV" | json adminEmail)"; C="$(echo "$INV" | json customerEmail)"; P="$(echo "$INV" | json password)"; fi
  step inv-browser 'RESULT: all steps passed' bash -c "cd e2e && node inventory-ui.mjs '$A' '$C' '$P' ../scratch_shots"
  if ! relevant inv-failures; then skip inv-failures "not selected"
  elif [ $DOCKER_OK = 1 ]; then
    ( cd ai-service && AI_TEST_DATABASE_URL="$TESTDB" uv run python ../e2e/ai/prepare_failure_mart.py > "$OUT/failure-mart.json" 2> "$OUT/failure-mart.err" )
    bash e2e/ai/failure-instances.sh start > "$OUT/failure-instances2.log" 2>&1
    VID="$(json victimId < "$OUT/failure-mart.json")"; VNAME="$(json victimName < "$OUT/failure-mart.json")"
    if [ -z "$VID" ] || [ -z "$VNAME" ]; then blocked inv-failures "the insufficient-data mart was not prepared (see failure-mart.err)"
    else step inv-failures 'RESULT: all steps passed' bash -c "cd e2e && node inventory-failures.mjs '$A' '$P' '$VID' '$VNAME' ../scratch_shots"; fi
    bash e2e/ai/failure-instances.sh stop > /dev/null 2>&1
  else blocked inv-failures "needs the PostgreSQL container for the insufficient-data mart"; fi
else blocked inv-measure "needs the stack and Gemini"; blocked inv-eval-dev "needs the stack and Gemini"; blocked inv-browser "needs the stack and Gemini"; blocked inv-failures "needs the stack and Gemini"; fi

# ============================================================================================================ 8b. Phase 3 customer support
step sup-unit '[0-9]+ passed' bash -c 'cd ai-service && uv run pytest tests/support -q'
if [ $STACK_OK = 1 ]; then
  step sup-corpus - bash -c 'cd ai-service && uv run python -m app.support.cli ingest-corpus'
  step sup-live '[0-9]+ passed' env AI_RUN_LIVE_TESTS=1 bash -c 'cd ai-service && uv run pytest -m live tests/live/test_stack_support.py -q'
  step sup-eval-dev 'ALL GATES MET' bash -c 'cd ai-service && uv run python -u eval/run_support_eval.py --suite dev'
  step sup-world - bash -c 'python e2e/ai/support_data.py --out scratch_shots/world.json'
  step sup-browser 'RESULT: all steps passed' bash -c 'node e2e/support-ui.mjs scratch_shots/world.json scratch_shots'
  skip sup-eval-holdout "first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md)"
else blocked sup-live "needs the stack"; blocked sup-eval-dev "needs the stack and Gemini"; blocked sup-browser "needs the stack and Gemini"; fi

# ============================================================================================================ 8c. management feedback (tickets, profiles/family, budget-aware recommendations)
step fe-unit 'Tests +[0-9]+ passed' bash -c 'cd frontend && NO_COLOR=1 node node_modules/vitest/vitest.mjs run'
if [ $STACK_OK = 1 ]; then
  step mgmt-api '[0-9]+/[0-9]+ checks passed' bash -c 'cd e2e/ai && PYTHONUNBUFFERED=1 uv run --project ../../ai-service python -u mgmt_feedback_e2e.py'
  step mgmt-browser 'RESULT: all steps passed' bash -c 'cd e2e && node mgmt-feedback-ui.mjs ../scratch_shots'
else blocked mgmt-api "needs the stack"; blocked mgmt-browser "needs the stack"; fi

# ============================================================================================================ 8d. Phase 4 Engineering AI (log monitoring and incident intelligence)
step eng-unit '[0-9]+ passed' bash -c 'cd ai-service && uv run pytest tests/engineering -q'
if [ $DOCKER_OK = 1 ]; then
  step eng-integration '[0-9]+ passed' env AI_TEST_DATABASE_URL="$TESTDB" bash -c 'cd ai-service && uv run pytest tests/integration/test_engineering_db.py -m integration -q'
  step eng-eval '"passed": true' env AI_TEST_DATABASE_URL="$TESTDB" bash -c 'cd ai-service && uv run python eval/engineering/run_eval.py --out eval/results/ENGINEERING-RESULTS.json'
else blocked eng-integration "needs the PostgreSQL container"; blocked eng-eval "needs the PostgreSQL container"; fi
if [ $STACK_OK = 1 ]; then step eng-e2e '[0-9]+/[0-9]+ checks passed' bash -c 'cd e2e/ai && PYTHONUNBUFFERED=1 uv run --project ../../ai-service python -u engineering_e2e.py'
else blocked eng-e2e "needs the stack"; fi

# ============================================================================================================ 8b. master-mission features (promotions, fulfilment/returns, staff AI, case chat, incidents)
if [ $DOCKER_OK = 1 ]; then
  step master-db-integration '[0-9]+ passed' env AI_TEST_DATABASE_URL="$TESTDB" bash -c 'cd ai-service && uv run pytest tests/integration/test_engineering_incidents_db.py tests/integration/test_staff_assist_db.py -m integration -q'
else blocked master-db-integration "needs the PostgreSQL container"; fi
if [ $STACK_OK = 1 ]; then
  step master-checkout 'RESULT: ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u checkout_features_e2e.py'
  step master-returns 'RESULT: ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u verify_return_scenarios.py'
  step master-support-staff '^ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u support_staff_e2e.py'
  step master-case-chat-incidents '^ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u case_chat_and_incidents_e2e.py'
else blocked master-checkout "needs the stack"; blocked master-returns "needs the stack"; blocked master-support-staff "needs the stack"; blocked master-case-chat-incidents "needs the stack"; fi

# ============================================================================================================ 8c. Phase 5: Product & Supplier Intelligence
step p5-unit '[0-9]+ passed' bash -c 'cd ai-service && uv run pytest tests/sourcing -q'
if [ $DOCKER_OK = 1 ]; then
  # its own throwaway database, so it never touches the data of the other integration suites (CREATE only if missing; nothing is dropped except the src_* tables of that database)
  relevant p5- && { docker exec dsports-postgres psql -U dsports -d postgres -tAc "select 1 from pg_database where datname='dsports_ai_src_test'" | grep -q 1 || docker exec dsports-postgres psql -U dsports -d postgres -c "CREATE DATABASE dsports_ai_src_test" > /dev/null 2>&1; }
  SRCDB="$(grep '^AI_DATABASE_URL=' ai-service/.env | cut -d= -f2- | sed 's#/dsports_ai$#/dsports_ai_src_test#')"
  step p5-db-integration '[0-9]+ passed' env SOURCING_TEST_DATABASE_URL="$SRCDB" bash -c 'cd ai-service && uv run pytest -m integration tests/integration/test_sourcing_db.py -q'
else blocked p5-db-integration "needs the PostgreSQL container"; fi
if [ $STACK_OK = 1 ]; then
  step p5-e2e '^ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u sourcing_e2e.py'
  step p5-world - bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python sourcing_data.py > ../../scratch_shots/sourcing-world.json'
  step p5-browser 'RESULT: all steps passed' bash -c 'node e2e/sourcing-ui.mjs scratch_shots/sourcing-world.json scratch_shots'
else blocked p5-e2e "needs the stack"; blocked p5-browser "needs the stack"; fi

# ============================================================================================================ 8d. Phase 6: Sales & Inventory Planning
step p6-unit '[0-9]+ passed' bash -c 'cd ai-service && uv run pytest tests/planning -q'
if [ $DOCKER_OK = 1 ]; then
  relevant p6- && { docker exec dsports-postgres psql -U dsports -d postgres -tAc "select 1 from pg_database where datname='dsports_ai_pln_test'" | grep -q 1 || docker exec dsports-postgres psql -U dsports -d postgres -c "CREATE DATABASE dsports_ai_pln_test" > /dev/null; }
  PLNDB="$(grep '^AI_DATABASE_URL=' ai-service/.env | cut -d= -f2- | sed 's#/dsports_ai$#/dsports_ai_pln_test#')"
  step p6-db-integration '[0-9]+ passed' env PLANNING_TEST_DATABASE_URL="$PLNDB" bash -c 'cd ai-service && uv run pytest -m integration tests/integration/test_planning_db.py -q'
else blocked p6-db-integration "needs the PostgreSQL container"; fi
if [ $STACK_OK = 1 ]; then
  step p6-e2e '^ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u planning_e2e.py'
  step p6-audit '^ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u planning_audit.py'
  step p6-world - bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python planning_data.py > ../../scratch_shots/planning-world.json'
  step p6-browser 'RESULT: all steps passed' bash -c 'node e2e/planning-ui.mjs scratch_shots/planning-world.json scratch_shots'
  step p6-cleanup - bash -c 'cd e2e/ai && uv run --project ../../ai-service python planning_data.py --cleanup'
else blocked p6-e2e "needs the stack"; blocked p6-audit "needs the stack"; blocked p6-browser "needs the stack"; fi

# ============================================================================================================ 8e. Security gate (the project's own tests; not a penetration test)
step sec-unit '[0-9]+ passed' bash -c 'cd ai-service && uv run pytest tests/security -q'
step sec-fe-audit 'No known vulnerabilities found' bash -c 'cd frontend && pnpm audit --prod'
if [ $STACK_OK = 1 ]; then
  step sec-e2e '^ALL PASS' bash -c 'cd e2e/ai && PYTHONIOENCODING=utf-8 uv run --project ../../ai-service python -u security_e2e.py'
else blocked sec-e2e "needs the stack"; fi

# ============================================================================================================ 9. the AI service stopped: the shop must not care
if ! relevant shop-with-ai-down; then skip shop-with-ai-down "not selected"
elif [ $STACK_OK = 1 ]; then
  kill_port 8090; sleep 2
  step shop-with-ai-down 'no console errors, page errors or 5xx' env AI_DOWN=1 bash -c "cd e2e && node ui-flow.mjs happy flow-\$RANDOM\$RANDOM@example.com 00000000-0000-0000-0000-000000001005"
  start_ai > /dev/null 2>&1
else blocked shop-with-ai-down "needs the stack"; fi

# ============================================================================================================ summary
{
  echo "# DSports-AI consolidated validation $TS"; echo
  echo "| Step | Status | Seconds | Note |"; echo "|---|---|---|---|"
  awk -F'\t' '{printf "| %s | **%s** | %s | %s |\n", $1, $2, $4, $5}' "$TSV"
  echo; echo "Counts: $(awk -F'	' '{c[$2]++} END {printf "PASS %d, FAIL %d, BLOCKED %d, SKIP %d", c["PASS"], c["FAIL"], c["BLOCKED"], c["SKIP"]}' "$TSV")"
  echo; echo "Environment:"; sed 's/^/    /' "$OUT/environment.txt"
} > "$OUT/SUMMARY.md"
cat "$OUT/SUMMARY.md"
awk -F'	' '$2=="FAIL" {f=1} END {exit f}' "$TSV" || exit 1
awk -F'	' '$2=="BLOCKED" {b=1} END {exit b}' "$TSV" || exit 3
exit 0
