# DSports-AI consolidated validation 20261016-135413

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 218 |  |
| java-integration | **PASS** | 114 |  |
| stack-backend | **PASS** | 25 |  |
| stack-ai | **PASS** | 15 |  |
| stack-vite | **PASS** | 0 |  |
| api-e2e | **PASS** | 96 |  |
| py-unit | **PASS** | 111 |  |
| py-integration | **PASS** | 387 |  |
| py-live | **PASS** | 156 |  |
| fe-tsc | **PASS** | 18 |  |
| fe-build | **PASS** | 40 |  |
| fe-catalog | **PASS** | 0 |  |
| cust-journeys | **PASS** | 232 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport-prep | **PASS** | 1 |  |
| cust-multisport | **PASS** | 166 |  |
| scenarios-A-M | **PASS** | 60 |  |
| eval-main | **PASS** | 195 |  |
| eval-holdout1 | **PASS** | 72 |  |
| cust-baseline-flow | **PASS** | 15 |  |
| inv-unit | **PASS** | 12 |  |
| inv-eval-numeric-dev | **PASS** | 0 |  |
| inv-eval-numeric-holdout | **PASS** | 1 |  |
| inv-measure | **PASS** | 42 |  |
| inv-eval-dev | **PASS** | 84 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 102 |  |
| inv-failures | **PASS** | 33 |  |
| sup-unit | **PASS** | 12 |  |
| sup-corpus | **PASS** | 2 |  |
| sup-live | **PASS** | 28 |  |
| sup-eval-dev | **PASS** | 271 |  |
| sup-world | **PASS** | 28 |  |
| sup-browser | **PASS** | 68 |  |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| fe-unit | **PASS** | 70 |  |
| mgmt-api | **PASS** | 20 |  |
| mgmt-browser | **PASS** | 60 |  |
| eng-unit | **PASS** | 6 |  |
| eng-integration | **PASS** | 16 |  |
| eng-eval | **PASS** | 1 |  |
| eng-e2e | **PASS** | 14 |  |
| master-db-integration | **PASS** | 17 |  |
| master-checkout | **PASS** | 5 |  |
| master-returns | **PASS** | 1 |  |
| master-support-staff | **PASS** | 2 |  |
| master-case-chat-incidents | **PASS** | 11 |  |
| p5-unit | **PASS** | 4 |  |
| p5-db-integration | **PASS** | 22 |  |
| p5-e2e | **PASS** | 30 |  |
| p5-world | **PASS** | 1 |  |
| p5-browser | **PASS** | 66 |  |
| p6-unit | **PASS** | 4 |  |
| p6-db-integration | **PASS** | 110 |  |
| p6-e2e | **FAIL** | 60 | exit code 1 |
| p6-audit | **PASS** | 27 |  |
| p6-world | **PASS** | 4 |  |
| p6-browser | **FAIL** | 89 | exit code 1 |
| p6-cleanup | **PASS** | 1 |  |
| sec-unit | **PASS** | 39 |  |
| sec-fe-audit | **PASS** | 2 |  |
| sec-e2e | **PASS** | 46 |  |
| shop-with-ai-down | **PASS** | 18 |  |

Counts: PASS 64, FAIL 2, BLOCKED 0, SKIP 2

Environment:
    date: Fri, Oct 16, 2026  1:54:13 PM
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 321
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
