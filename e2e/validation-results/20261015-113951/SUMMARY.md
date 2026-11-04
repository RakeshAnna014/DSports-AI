# DSports-AI consolidated validation 20261015-113951

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 206 |  |
| java-integration | **PASS** | 119 |  |
| stack-backend | **PASS** | 26 |  |
| stack-ai | **PASS** | 19 |  |
| stack-vite | **PASS** | 0 |  |
| api-e2e | **PASS** | 103 |  |
| py-unit | **PASS** | 74 |  |
| py-integration | **PASS** | 307 |  |
| py-live | **PASS** | 171 |  |
| fe-tsc | **PASS** | 28 |  |
| fe-build | **PASS** | 59 |  |
| fe-catalog | **PASS** | 1 |  |
| cust-journeys | **PASS** | 443 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **FAIL** | 0 | see scratch_shots/ui-context.log |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport | **FAIL** | 276 | exit code 1 |
| scenarios-A-M | **PASS** | 129 |  |
| eval-main | **PASS** | 269 |  |
| eval-holdout1 | **PASS** | 74 |  |
| cust-baseline-flow | **FAIL** | 15 | exit code 1 |
| inv-unit | **PASS** | 16 |  |
| inv-eval-numeric-dev | **PASS** | 2 |  |
| inv-eval-numeric-holdout | **PASS** | 2 |  |
| inv-measure | **PASS** | 48 |  |
| inv-eval-dev | **PASS** | 92 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 81 |  |
| inv-failures | **PASS** | 25 |  |
| sup-unit | **PASS** | 11 |  |
| sup-corpus | **PASS** | 4 |  |
| sup-live | **PASS** | 31 |  |
| sup-eval-dev | **PASS** | 418 |  |
| sup-world | **PASS** | 29 |  |
| sup-browser | **PASS** | 61 |  |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| fe-unit | **PASS** | 63 |  |
| mgmt-api | **PASS** | 22 |  |
| mgmt-browser | **PASS** | 59 |  |
| eng-unit | **PASS** | 7 |  |
| eng-integration | **PASS** | 21 |  |
| eng-eval | **PASS** | 3 |  |
| eng-e2e | **PASS** | 19 |  |
| master-db-integration | **PASS** | 23 |  |
| master-checkout | **PASS** | 6 |  |
| master-returns | **PASS** | 2 |  |
| master-support-staff | **PASS** | 3 |  |
| master-case-chat-incidents | **PASS** | 14 |  |
| shop-with-ai-down | **PASS** | 20 |  |

Counts: PASS 47, FAIL 3, BLOCKED 0, SKIP 2

Environment:
    date: Thu Oct 15 11:39:52 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 304
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
