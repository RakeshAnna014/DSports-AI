# DSports-AI consolidated validation 20261012-200629

| Step | Status | Seconds | Note |
|---|---|---|---|
| env-docker | **PASS** | 0 |  |
| env-gemini | **PASS** | 0 |  |
| java-build | **PASS** | 263 |  |
| java-integration | **PASS** | 112 |  |
| stack-backend | **SKIP** | 0 | not selected |
| stack-ai | **SKIP** | 0 | not selected |
| stack-vite | **SKIP** | 0 | not selected |
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
| inv-unit | **SKIP** | 0 | not selected |
| inv-eval-numeric-dev | **SKIP** | 0 | not selected |
| inv-eval-numeric-holdout | **SKIP** | 0 | not selected |
| inv-measure | **BLOCKED** | 0 | needs the stack and Gemini |
| inv-eval-dev | **BLOCKED** | 0 | needs the stack and Gemini |
| inv-browser | **BLOCKED** | 0 | needs the stack and Gemini |
| inv-failures | **BLOCKED** | 0 | needs the stack and Gemini |
| sup-unit | **SKIP** | 0 | not selected |
| sup-live | **BLOCKED** | 0 | needs the stack |
| sup-eval-dev | **BLOCKED** | 0 | needs the stack and Gemini |
| sup-browser | **BLOCKED** | 0 | needs the stack and Gemini |
| shop-with-ai-down | **SKIP** | 0 | not selected |

Counts: PASS 4, FAIL 0, BLOCKED 15, SKIP 12

Environment:
    date: Mon Oct 12 20:06:29 IST 2026
    branch: dsports_Code
    head: 4552785 feat: complete PR #18 Payment Management - add capture endpoint, processing state, post-payment handler, cart completion, inventory events
    changed paths: 231
    java version "21.0.6" 2025-01-21 LTS
    node v22.20.0
    Python 3.13.3
    uv 0.12.8 (68209e5c6 2026-08-31 x86_64-pc-windows-msvc)
