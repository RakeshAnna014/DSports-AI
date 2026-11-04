# DSports-AI consolidated validation 20261015-145027

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 226 |  |
| java-integration | **PASS** | 151 |  |
| stack-backend | **PASS** | 30 |  |
| stack-ai | **PASS** | 21 |  |
| stack-vite | **PASS** | 1 |  |
| api-e2e | **PASS** | 110 |  |
| py-unit | **PASS** | 84 |  |
| py-integration | **PASS** | 325 |  |
| py-live | **PASS** | 187 |  |
| fe-tsc | **PASS** | 31 |  |
| fe-build | **PASS** | 83 |  |
| fe-catalog | **PASS** | 1 |  |
| cust-journeys | **PASS** | 343 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport-prep | **PASS** | 6 |  |
| cust-multisport | **PASS** | 227 |  |
| scenarios-A-M | **PASS** | 87 |  |
| eval-main | **PASS** | 265 |  |
| eval-holdout1 | **PASS** | 74 |  |
| cust-baseline-flow | **PASS** | 19 |  |
| inv-unit | **PASS** | 14 |  |
| inv-eval-numeric-dev | **PASS** | 1 |  |
| inv-eval-numeric-holdout | **PASS** | 1 |  |
| inv-measure | **PASS** | 77 |  |
| inv-eval-dev | **PASS** | 215 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 79 |  |
| inv-failures | **PASS** | 24 |  |
| sup-unit | **PASS** | 13 |  |
| sup-corpus | **PASS** | 3 |  |
| sup-live | **PASS** | 28 |  |
| sup-eval-dev | **PASS** | 328 |  |
| sup-world | **PASS** | 32 |  |
| sup-browser | **PASS** | 71 |  |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| fe-unit | **PASS** | 83 |  |
| mgmt-api | **PASS** | 24 |  |
| mgmt-browser | **PASS** | 58 |  |
| eng-unit | **PASS** | 12 |  |
| eng-integration | **PASS** | 22 |  |
| eng-eval | **PASS** | 2 |  |
| eng-e2e | **PASS** | 20 |  |
| master-db-integration | **PASS** | 28 |  |
| master-checkout | **PASS** | 5 |  |
| master-returns | **PASS** | 1 |  |
| master-support-staff | **PASS** | 3 |  |
| master-case-chat-incidents | **PASS** | 13 |  |
| p5-unit | **PASS** | 7 |  |
| p5-db-integration | **PASS** | 19 |  |
| p5-e2e | **FAIL** | 30 | exit code 1 |
| p5-world | **PASS** | 2 |  |
| p5-browser | **PASS** | 45 |  |
| shop-with-ai-down | **PASS** | 21 |  |

Counts: PASS 55, FAIL 1, BLOCKED 0, SKIP 2

Environment:
    date: Thu Oct 15 14:50:28 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 308
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
