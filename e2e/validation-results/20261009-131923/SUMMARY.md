# DSports-AI consolidated validation 20261009-131923

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 168 |  |
| java-integration | **PASS** | 71 |  |
| stack-backend | **PASS** | 29 |  |
| stack-ai | **PASS** | 20 |  |
| stack-vite | **PASS** | 1 |  |
| api-e2e | **BLOCKED** | 0 | port 8081 is held by another project's process (see api-e2e-port.log); not touching it |
| py-unit | **PASS** | 45 |  |
| py-integration | **PASS** | 127 |  |
| py-live | **PASS** | 158 |  |
| fe-tsc | **FAIL** | 8 | exit code 1 |
| fe-build | **FAIL** | 2 | exit code 1 |
| fe-catalog | **FAIL** | 2 | exit code 1 |
| cust-journeys | **PASS** | 427 |  |
| cust-journey-drawer | **PASS** | 0 |  |
| cust-journey-commerce | **PASS** | 0 |  |
| cust-journey-context | **PASS** | 0 |  |
| cust-journey-redesign | **PASS** | 0 |  |
| cust-multisport | **PASS** | 239 |  |
| scenarios-A-M | **PASS** | 120 |  |
| eval-main | **FAIL** | 336 | exit code 1 |
| eval-holdout1 | **FAIL** | 2 | exit code 1 |
| cust-baseline-flow | **PASS** | 25 |  |
| inv-unit | **PASS** | 29 |  |
| inv-eval-numeric-dev | **PASS** | 2 |  |
| inv-eval-numeric-holdout | **PASS** | 2 |  |
| inv-measure | **FAIL** | 1 | exit code 1 |
| inv-eval-dev | **FAIL** | 8 | exit code 1 |
| inv-eval-holdout2 | **SKIP** | 0 | consumed set; its first run is preserved (use --with-holdout2 to re-run for information) |
| inv-browser | **FAIL** | 537 | exit code 1 |
| inv-failures | **FAIL** | 32 | exit code 1 |
| shop-with-ai-down | **PASS** | 16 |  |

Counts: PASS 22, FAIL 9, BLOCKED 1, SKIP 1

Environment:
    date: Fri Oct  9 13:19:23 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 227
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
