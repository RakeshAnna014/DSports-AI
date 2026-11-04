# DSports-AI consolidated validation 20261012-202456

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **SKIP** | 0 | --skip-build |
| java-integration | **SKIP** | 0 | --skip-build |
| stack-backend | **PASS** | 37 |  |
| stack-ai | **PASS** | 16 |  |
| stack-vite | **PASS** | 0 |  |
| api-e2e | **PASS** | 112 |  |
| py-unit | **PASS** | 127 |  |
| py-integration | **PASS** | 375 |  |
| py-live | **FAIL** | 218 | exit code 1 |
| fe-tsc | **PASS** | 23 |  |
| fe-build | **PASS** | 76 |  |
| fe-catalog | **PASS** | 2 |  |
| cust-journeys | **PASS** | 459 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport | **FAIL** | 341 | exit code 1 |
| scenarios-A-M | **PASS** | 124 |  |
| eval-main | **FAIL** | 1021 | exit 0 but success marker /"cases_passed": 83/ not found |
| eval-holdout1 | **FAIL** | 335 | exit 0 but success marker /"cases_passed": 26/ not found |
| cust-baseline-flow | **FAIL** | 53 | exit code 1 |
| inv-unit | **PASS** | 25 |  |
| inv-eval-numeric-dev | **PASS** | 2 |  |
| inv-eval-numeric-holdout | **PASS** | 2 |  |
| inv-measure | **PASS** | 115 |  |
| inv-eval-dev | **PASS** | 178 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 148 |  |
| inv-failures | **PASS** | 61 |  |
| sup-unit | **PASS** | 25 |  |
| sup-corpus | **PASS** | 7 |  |
| sup-live | **FAIL** | 12 | exit code 5 |
| sup-eval-dev | **PASS** | 829 |  |
| sup-world | **PASS** | 32 |  |
| sup-browser | **PASS** | 75 |  |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| shop-with-ai-down | **PASS** | 21 |  |

Counts: PASS 30, FAIL 6, BLOCKED 0, SKIP 4

Environment:
    date: Mon Oct 12 20:24:56 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 231
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
