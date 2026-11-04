#!/usr/bin/env bash
# Starts the AI service on :8090 detached from the calling shell (survives the tool/session that launched it).
# Same settings as start-ai.ps1: a high rate limit for evaluation, the embedding region, and the price table used for cost estimates.
cd "$(dirname "$0")/../../ai-service" || exit 1
bash ../e2e/ai/free-port.sh 8090 || { echo "port 8090 is held by another project's process; not starting" >&2; exit 1; }
sleep 2
export AI_RATE_LIMIT_PER_MIN=1000 AI_EMBEDDING_LOCATION=asia-south1
export AI_PRICE_TABLE_JSON='{"gemini-2.5-flash":{"input_per_1m":0.30,"output_per_1m":2.50},"gemini-2.5-flash-lite":{"input_per_1m":0.10,"output_per_1m":0.40}}'
# Product & Supplier Intelligence: ONE real, permitted research source (the manufacturer's own site). Nothing in .env is touched; override these in the environment to change them.
DEFAULT_PROVIDERS='[{"type":"victor_sport","max_products":12,"supplier_countries":["India"]}]'
export SOURCING_PROVIDERS="${SOURCING_PROVIDERS:-$DEFAULT_PROVIDERS}"
export SOURCING_ALLOWED_HOSTS="${SOURCING_ALLOWED_HOSTS:-www.victorsport.com,dkjulgymkya8y.cloudfront.net}" SOURCING_IMAGE_HOSTS="${SOURCING_IMAGE_HOSTS:-dkjulgymkya8y.cloudfront.net}"
nohup .venv/Scripts/python.exe -m uvicorn app.main:create_app --factory --port 8090 --no-access-log > "${TEMP:-/tmp}/ai_service_main.log" 2>&1 &
for _ in $(seq 1 45); do curl -s -m2 localhost:8090/health | grep -q UP && { echo "ai-service up"; exit 0; }; sleep 1; done
echo "ai-service did not start" >&2; exit 1
