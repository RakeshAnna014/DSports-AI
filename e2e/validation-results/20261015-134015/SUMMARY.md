# DSports-AI consolidated validation 20261015-134015

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 289 |  |
| java-integration | **PASS** | 143 |  |
| stack-backend | **PASS** | 27 |  |
| stack-ai | **PASS** | 26 |  |
| stack-vite | **PASS** | 0 |  |
| api-e2e | **PASS** | 94 |  |
| py-unit | **PASS** | 73 |  |
| py-integration | **FAIL** | 282 | exit code 1 |
| py-live | **PASS** | 184 |  |
| fe-tsc | **PASS** | 30 |  |
| fe-build | **PASS** | 89 |  |
| fe-catalog | **PASS** | 1 |  |
| cust-journeys | **PASS** | 315 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport-prep | **PASS** | 1 |  |
| cust-multisport | **PASS** | 223 |  |
| scenarios-A-M | **PASS** | 89 |  |
| eval-main | **PASS** | 258 |  |
| eval-holdout1 | **PASS** | 81 |  |
| cust-baseline-flow | **FAIL** | 52 | exit code 1 |
| inv-unit | **PASS** | 34 |  |
| inv-eval-numeric-dev | **PASS** | 2 |  |
| inv-eval-numeric-holdout | **PASS** | 2 |  |
| inv-measure | **PASS** | 60 |  |
| inv-eval-dev | **PASS** | 98 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 117 |  |
| inv-failures | **PASS** | 42 |  |
| sup-unit | **PASS** | 22 |  |
| sup-corpus | **PASS** | 6 |  |
| sup-live | **PASS** | 39 |  |
| sup-eval-dev | **PASS** | 359 |  |
| sup-world | **PASS** | 32 |  |
| sup-browser | **PASS** | 81 |  |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| fe-unit | **PASS** | 80 |  |
| mgmt-api | **PASS** | 34 |  |
| mgmt-browser | **PASS** | 60 |  |
| eng-unit | **PASS** | 11 |  |
| eng-integration | **PASS** | 20 |  |
| eng-eval | **PASS** | 2 |  |
| eng-e2e | **PASS** | 27 |  |
| master-db-integration | **PASS** | 27 |  |
| master-checkout | **PASS** | 5 |  |
| master-returns | **PASS** | 2 |  |
| master-support-staff | **PASS** | 3 |  |
| master-case-chat-incidents | **PASS** | 12 |  |
| p5-unit | **PASS** | 7 |  |
| p5-db-integration | **PASS** | 19 |  |
| p5-e2e | **PASS** | 27 |  |
| p5-world | **PASS** | 3 |  |
| p5-browser | **PASS** | 44 |  |
| shop-with-ai-down | **PASS** | 24 |  |

Counts: PASS 54, FAIL 2, BLOCKED 0, SKIP 2

Environment:
    date: Thu Oct 15 13:40:15 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 308
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
