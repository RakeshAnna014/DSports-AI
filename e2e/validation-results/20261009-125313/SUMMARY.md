# DSports-AI consolidated validation 20261009-125313

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **SKIP** | 0 | --skip-build |
| java-integration | **SKIP** | 0 | --skip-build |
| stack-backend | **SKIP** | 0 | not selected |
| stack-ai | **SKIP** | 0 | not selected |
| stack-vite | **SKIP** | 0 | not selected |
| api-e2e | **BLOCKED** | 0 | needs Docker and the running stack |
| py-unit | **SKIP** | 0 | not selected |
| py-integration | **SKIP** | 0 | not selected |
| py-live | **BLOCKED** | 0 | needs the stack and Gemini |
| fe-tsc | **SKIP** | 0 | not selected |
| fe-build | **SKIP** | 0 | not selected |
| fe-catalog | **BLOCKED** | 0 | needs the backend |
| cust-journeys | **BLOCKED** | 0 | needs the stack and Gemini |
| cust-multisport | **BLOCKED** | 0 | needs the stack |
| scenarios-A-M | **BLOCKED** | 0 | needs the stack and Gemini |
| eval-main | **BLOCKED** | 0 | needs Gemini |
| eval-holdout1 | **BLOCKED** | 0 | needs Gemini |
| cust-baseline-flow | **BLOCKED** | 0 | needs the stack |
| inv-unit | **PASS** | 12 |  |
| inv-eval-numeric-dev | **SKIP** | 0 | not selected |
| inv-eval-numeric-holdout | **SKIP** | 0 | not selected |
| inv-measure | **BLOCKED** | 0 | needs the stack and Gemini |
| inv-eval-dev | **BLOCKED** | 0 | needs the stack and Gemini |
| inv-browser | **BLOCKED** | 0 | needs the stack and Gemini |
| inv-failures | **BLOCKED** | 0 | needs the stack and Gemini |
| shop-with-ai-down | **BLOCKED** | 0 | needs the stack |

Counts: PASS , FAIL , BLOCKED , SKIP 

Environment:
    date: Fri Oct  9 12:53:13 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 227
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
