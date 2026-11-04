# DSports-AI consolidated validation 20261012-153038

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 181 |  |
| java-integration | **PASS** | 67 |  |
| stack-backend | **PASS** | 21 |  |
| stack-ai | **PASS** | 10 |  |
| stack-vite | **PASS** | 0 |  |
| api-e2e | **PASS** | 55 |  |
| py-unit | **PASS** | 57 |  |
| py-integration | **PASS** | 101 |  |
| py-live | **PASS** | 152 |  |
| fe-tsc | **PASS** | 15 |  |
| fe-build | **PASS** | 40 |  |
| fe-catalog | **PASS** | 1 |  |
| cust-journeys | **PASS** | 355 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **FAIL** | 0 | see scratch_shots/ui-commerce.log |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport | **PASS** | 211 |  |
| scenarios-A-M | **PASS** | 94 |  |
| eval-main | **PASS** | 213 |  |
| eval-holdout1 | **PASS** | 63 |  |
| cust-baseline-flow | **PASS** | 18 |  |
| inv-unit | **PASS** | 17 |  |
| inv-eval-numeric-dev | **PASS** | 2 |  |
| inv-eval-numeric-holdout | **PASS** | 2 |  |
| inv-measure | **PASS** | 43 |  |
| inv-eval-dev | **PASS** | 156 |  |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **PASS** | 87 |  |
| inv-failures | **PASS** | 22 |  |
| shop-with-ai-down | **PASS** | 16 |  |

Counts: PASS 31, FAIL 1, BLOCKED 0, SKIP 1

Environment:
    date: Mon Oct 12 15:30:38 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 227
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
