#!/usr/bin/env bash
# Runs the three real-browser AI journeys, each with its own freshly created customer (real DSports APIs, real AI service).
# Usage: bash e2e/run-ai-ui.sh [shotDir]      Logs: <shotDir>/ui-<name>.log. Needs backend :8080, AI :8090 (+ broken-credentials AI on :8091), Vite :3000.
cd "$(dirname "$0")" || exit 1
SHOTS="${1:-../scratch_shots}"; mkdir -p "$SHOTS"
export PYTHONUTF8=1
setup() { python ai/ai_ui_setup.py > "$SHOTS/setup.json" 2> "$SHOTS/setup.err"; python -c "
import json;d=json.load(open('$SHOTS/setup.json'));print(d['email'],d['password'],d['orderNumber'],d['orderId'])" | tr -d '\r'; }
read -r E P N O < <(setup); node ai-ui.mjs "$E" "$N" "$O" > "$SHOTS/ui-drawer.log" 2>&1
read -r E P N O < <(setup); node ai-commerce.mjs "$E" "$P" "$N" "$SHOTS" > "$SHOTS/ui-commerce.log" 2>&1
read -r E P N O < <(setup); node ai-product-context.mjs "$E" "$P" "$SHOTS" > "$SHOTS/ui-context.log" 2>&1
setup2() { python ai/ai_ui_setup2.py > "$SHOTS/setup2.json" 2> "$SHOTS/setup2.err"; python -c "
import json;d=json.load(open('$SHOTS/setup2.json'));print(d['email'],d['password'],d['shoesOrder'],d['batOrder'])" | tr -d ''; }
read -r E P S B < <(setup2); node ai-redesign.mjs "$E" "$P" "$S" "$B" "$SHOTS" > "$SHOTS/ui-redesign.log" 2>&1
echo done > "$SHOTS/ui-done.flag"
