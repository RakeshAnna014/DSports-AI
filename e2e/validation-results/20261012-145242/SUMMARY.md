# DSports-AI consolidated validation 20261012-145242

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 207 |  |
| java-integration | **PASS** | 79 |  |
| stack-backend | **PASS** | 24 |  |
| stack-ai | **PASS** | 16 |  |
| stack-vite | **PASS** | 0 |  |
| api-e2e | **PASS** | 97 |  |
| py-unit | **PASS** | 53 |  |
| py-integration | **PASS** | 111 |  |
| py-live | **PASS** | 144 |  |
| fe-tsc | **PASS** | 16 |  |
| fe-build | **PASS** | 46 |  |
| fe-catalog | **PASS** | 0 |  |
| cust-journeys | **PASS** | 251 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport | **PASS** | 180 |  |
| scenarios-A-M | **PASS** | 67 |  |
| eval-main | **PASS** | 206 |  |
| eval-holdout1 | **PASS** | 73 |  |
| cust-baseline-flow | **PASS** | 17 |  |
| inv-unit | **PASS** | 12 |  |
| inv-eval-numeric-dev | **PASS** | 1 |  |
| inv-eval-numeric-holdout | **PASS** | 0 |  |
| inv-measure | **PASS** | 44 |  |
| inv-eval-dev | **PASS** | 90 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 83 |  |
| inv-failures | **FAIL** | 32 | exit code 1 |
| shop-with-ai-down | **FAIL** | 22 | exit code 2 |

Counts: PASS 30, FAIL 2, BLOCKED 0, SKIP 1

Environment:
    date: Mon Oct 12 14:52:43 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 227
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
