# DSports-AI consolidated validation 20261012-152838

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **SKIP** | 0 | --skip-build |
| java-integration | **SKIP** | 0 | --skip-build |
| stack-backend | **SKIP** | 0 | not selected |
| stack-ai | **SKIP** | 0 | not selected |
| stack-vite | **SKIP** | 0 | not selected |
| py-unit | **SKIP** | 0 | not selected |
| py-integration | **SKIP** | 0 | not selected |
| py-live | **SKIP** | 0 | not selected |
| fe-tsc | **SKIP** | 0 | not selected |
| fe-build | **SKIP** | 0 | not selected |
| fe-catalog | **SKIP** | 0 | not selected |
| cust-journeys | **SKIP** | 0 | not selected |
| cust-multisport | **SKIP** | 0 | not selected |
| scenarios-A-M | **SKIP** | 0 | not selected |
| eval-main | **SKIP** | 0 | not selected |
| eval-holdout1 | **SKIP** | 0 | not selected |
| cust-baseline-flow | **SKIP** | 0 | not selected |
| inv-unit | **SKIP** | 0 | not selected |
| inv-eval-numeric-dev | **SKIP** | 0 | not selected |
| inv-eval-numeric-holdout | **SKIP** | 0 | not selected |
| inv-measure | **SKIP** | 0 | not selected |
| inv-eval-dev | **SKIP** | 0 | not selected |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **SKIP** | 0 | not selected |
| inv-failures | **PASS** | 34 |  |
| shop-with-ai-down | **SKIP** | 0 | not selected |

Counts: PASS 3, FAIL 0, BLOCKED 0, SKIP 25

Environment:
    date: Mon Oct 12 15:28:38 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 227
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
