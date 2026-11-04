#!/usr/bin/env bash
# Starts (or stops) three REAL extra AI-service instances used to validate failure behaviour. Nothing is mocked: each is the real application with a real fault.
#   :8091  Gemini unavailable      (unusable Google credentials; the database and the mart are the real ones)
#   :8092  database unavailable    (AI_DATABASE_URL points at a closed port)
#   :8093  insufficient data       (the throwaway test database prepared by prepare_failure_mart.py: one product has ~20 days of history)
# Usage: bash e2e/ai/failure-instances.sh start | stop      Secrets are read from ai-service/.env and never printed.
cd "$(dirname "$0")/../../ai-service" || exit 1
TMPD="$(cygpath -m "${TEMP:-/tmp}" 2>/dev/null || echo "${TEMP:-/tmp}")"      # a Windows path: Git Bash sets TEMP=/tmp, which native Python cannot resolve
stop() { for port in 8091 8092 8093; do powershell -NoProfile -c "Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force }"; done; }
start_one() { port="$1"; shift; ( env "$@" AI_RATE_LIMIT_PER_MIN=1000 nohup .venv/Scripts/python.exe -m uvicorn app.main:create_app --factory --port "$port" --no-access-log > "$TMPD/ai_failure_$port.log" 2>&1 & ); }
case "$1" in
  stop) stop ;;
  start)
    stop; sleep 2
    BROKEN="$TMPD/broken-credentials.json"
    python - <<PYEOF
import json
json.dump({"type": "service_account", "project_id": "no-such-project", "private_key_id": "x", "private_key": "not-a-key",
           "client_email": "nobody@no-such-project.iam.gserviceaccount.com", "client_id": "1", "token_uri": "https://oauth2.googleapis.com/token"}, open(r"$BROKEN", "w"))
PYEOF
    TESTDB="$(grep '^AI_DATABASE_URL=' .env | cut -d= -f2- | sed 's#/dsports_ai$#/dsports_ai_test#')"
    start_one 8091 GOOGLE_APPLICATION_CREDENTIALS="$BROKEN" AI_TIMEOUT_S=10 AI_BREAKER_FAILURES=2
    start_one 8092 AI_DATABASE_URL="postgresql://nobody:nobody@127.0.0.1:1/none" AI_TIMEOUT_S=3
    start_one 8093 AI_DATABASE_URL="$TESTDB"
    for port in 8091 8092 8093; do
      for _ in $(seq 1 45); do curl -s -m2 "localhost:$port/health" | grep -q UP && break; sleep 1; done
      printf "%s: " "$port"; curl -s -m2 "localhost:$port/health" || echo "NOT UP"; echo
    done ;;
  *) echo "usage: $0 start|stop"; exit 2 ;;
esac
