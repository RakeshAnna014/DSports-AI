# DSports-AI consolidated validation 20261016-164235

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **SKIP** | 0 | not selected |
| java-integration | **SKIP** | 0 | not selected |
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
| cust-multisport-prep | **SKIP** | 0 | not selected |
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
| inv-failures | **SKIP** | 0 | not selected |
| sup-unit | **SKIP** | 0 | not selected |
| sup-corpus | **SKIP** | 0 | not selected |
| sup-live | **SKIP** | 0 | not selected |
| sup-eval-dev | **SKIP** | 0 | not selected |
| sup-world | **SKIP** | 0 | not selected |
| sup-browser | **SKIP** | 0 | not selected |
| sup-eval-holdout | **SKIP** | 0 | first run preserved in ai-service/eval/results; later runs are regression (see docs/ai/phase3/10-evaluation.md) |
| fe-unit | **SKIP** | 0 | not selected |
| mgmt-api | **SKIP** | 0 | not selected |
| mgmt-browser | **SKIP** | 0 | not selected |
| eng-unit | **SKIP** | 0 | not selected |
| eng-integration | **SKIP** | 0 | not selected |
| eng-eval | **SKIP** | 0 | not selected |
| eng-e2e | **SKIP** | 0 | not selected |
| master-db-integration | **SKIP** | 0 | not selected |
| master-checkout | **SKIP** | 0 | not selected |
| master-returns | **SKIP** | 0 | not selected |
| master-support-staff | **SKIP** | 0 | not selected |
| master-case-chat-incidents | **SKIP** | 0 | not selected |
| p5-unit | **SKIP** | 0 | not selected |
| p5-db-integration | **SKIP** | 0 | not selected |
| p5-e2e | **SKIP** | 0 | not selected |
| p5-world | **SKIP** | 0 | not selected |
| p5-browser | **SKIP** | 0 | not selected |
| p6-unit | **PASS** | 11 |  |
| p6-db-integration | **PASS** | 150 |  |
| p6-e2e | **PASS** | 83 |  |
| p6-audit | **PASS** | 48 |  |
| p6-world | **PASS** | 7 |  |
| p6-browser | **PASS** | 87 |  |
| p6-cleanup | **PASS** | 2 |  |
| sec-unit | **SKIP** | 0 | not selected |
| sec-fe-audit | **SKIP** | 0 | not selected |
| sec-e2e | **SKIP** | 0 | not selected |
| shop-with-ai-down | **SKIP** | 0 | not selected |

Counts: PASS 9, FAIL 0, BLOCKED 0, SKIP 54

Environment:
    date: Fri, Oct 16, 2026  4:42:36 PM
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 342
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
