# DSports-AI consolidated validation 20261012-162429

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 181 |  |
| java-integration | **PASS** | 63 |  |
| stack-backend | **PASS** | 17 |  |
| stack-ai | **PASS** | 11 |  |
| stack-vite | **PASS** | 1 |  |
| api-e2e | **PASS** | 71 |  |
| py-unit | **PASS** | 45 |  |
| py-integration | **PASS** | 171 |  |
| py-live | **PASS** | 140 |  |
| fe-tsc | **PASS** | 11 |  |
| fe-build | **PASS** | 28 |  |
| fe-catalog | **PASS** | 1 |  |
| cust-journeys | **PASS** | 224 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport | **PASS** | 145 |  |
| scenarios-A-M | **PASS** | 62 |  |
| eval-main | **PASS** | 207 |  |
| eval-holdout1 | **PASS** | 60 |  |
| cust-baseline-flow | **PASS** | 16 |  |
| inv-unit | **PASS** | 11 |  |
| inv-eval-numeric-dev | **PASS** | 1 |  |
| inv-eval-numeric-holdout | **PASS** | 0 |  |
| inv-measure | **PASS** | 41 |  |
| inv-eval-dev | **PASS** | 106 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 73 |  |
| inv-failures | **PASS** | 22 |  |
| shop-with-ai-down | **PASS** | 16 |  |

Counts: PASS 32, FAIL 0, BLOCKED 0, SKIP 1

Environment:
    date: Mon Oct 12 16:24:29 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 227
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
