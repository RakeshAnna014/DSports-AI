# DSports-AI consolidated validation 20261014-114019

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **SKIP** | 0 | --skip-build |
| java-integration | **SKIP** | 0 | --skip-build |
| stack-backend | **PASS** | 23 |  |
| stack-ai | **PASS** | 12 |  |
| stack-vite | **PASS** | 1 |  |
| api-e2e | **PASS** | 82 |  |
| py-unit | **PASS** | 59 |  |
| py-integration | **PASS** | 232 |  |
| py-live | **PASS** | 160 |  |
| fe-tsc | **PASS** | 19 |  |
| fe-build | **PASS** | 46 |  |
| fe-catalog | **PASS** | 1 |  |
| cust-journeys | **PASS** | 266 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **FAIL** | 0 | see scratch_shots/ui-redesign.log |
| cust-multisport | **FAIL** | 199 | exit code 1 |
| scenarios-A-M | **PASS** | 109 |  |
| eval-main | **PASS** | 282 |  |
| eval-holdout1 | **PASS** | 74 |  |
| cust-baseline-flow | **FAIL** | 48 | exit code 1 |
| inv-unit | **PASS** | 20 |  |
| inv-eval-numeric-dev | **PASS** | 1 |  |
| inv-eval-numeric-holdout | **PASS** | 2 |  |
| inv-measure | **PASS** | 51 |  |
| inv-eval-dev | **PASS** | 112 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 124 |  |
| inv-failures | **PASS** | 26 |  |
| sup-unit | **PASS** | 11 |  |
| sup-corpus | **PASS** | 3 |  |
| sup-live | **PASS** | 28 |  |
| sup-eval-dev | **PASS** | 334 |  |
| sup-world | **PASS** | 30 |  |
| sup-browser | **PASS** | 69 |  |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| fe-unit | **FAIL** | 2 | exit code 1 |
| mgmt-api | **PASS** | 23 |  |
| mgmt-browser | **FAIL** | 3 | exit code 1 |
| eng-unit | **PASS** | 7 |  |
| eng-integration | **PASS** | 17 |  |
| eng-eval | **PASS** | 2 |  |
| eng-e2e | **FAIL** | 2 | exit code 1 |
| shop-with-ai-down | **PASS** | 19 |  |

Counts: PASS 37, FAIL 6, BLOCKED 0, SKIP 4

Environment:
    date: Wed Oct 14 11:40:19 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 266
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
