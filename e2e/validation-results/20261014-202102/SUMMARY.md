# DSports-AI consolidated validation 20261014-202102

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 199 |  |
| java-integration | **PASS** | 108 |  |
| stack-backend | **PASS** | 24 |  |
| stack-ai | **PASS** | 18 |  |
| stack-vite | **PASS** | 1 |  |
| api-e2e | **PASS** | 102 |  |
| py-unit | **PASS** | 77 |  |
| py-integration | **PASS** | 314 |  |
| py-live | **PASS** | 187 |  |
| fe-tsc | **PASS** | 27 |  |
| fe-build | **PASS** | 62 |  |
| fe-catalog | **PASS** | 1 |  |
| cust-journeys | **PASS** | 323 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport | **PASS** | 230 |  |
| scenarios-A-M | **PASS** | 75 |  |
| eval-main | **PASS** | 468 |  |
| eval-holdout1 | **PASS** | 165 |  |
| cust-baseline-flow | **FAIL** | 58 | exit code 1 |
| inv-unit | **PASS** | 28 |  |
| inv-eval-numeric-dev | **PASS** | 1 |  |
| inv-eval-numeric-holdout | **PASS** | 1 |  |
| inv-measure | **PASS** | 84 |  |
| inv-eval-dev | **PASS** | 166 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 152 |  |
| inv-failures | **PASS** | 37 |  |
| sup-unit | **PASS** | 32 |  |
| sup-corpus | **PASS** | 5 |  |
| sup-live | **PASS** | 34 |  |
| sup-eval-dev | **PASS** | 377 |  |
| sup-world | **PASS** | 31 |  |
| sup-browser | **PASS** | 90 |  |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| fe-unit | **PASS** | 57 |  |
| mgmt-api | **PASS** | 30 |  |
| mgmt-browser | **PASS** | 64 |  |
| eng-unit | **PASS** | 10 |  |
| eng-integration | **PASS** | 16 |  |
| eng-eval | **PASS** | 1 |  |
| eng-e2e | **PASS** | 15 |  |
| shop-with-ai-down | **FAIL** | 46 | exit code 1 |

Counts: PASS 43, FAIL 2, BLOCKED 0, SKIP 2

Environment:
    date: Wed Oct 14 20:21:04 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 275
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
