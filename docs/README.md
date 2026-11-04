# DSports-AI documentation index

Start here. Documents are grouped by purpose, and each group says how far to trust it. **The source code and the executable tests are the evidence; any document that disagrees with them is wrong and should be fixed.** [STATUS.md](STATUS.md) is the single place that says what is complete, partial, synthetic or blocked.

## 1. Understand the system (current, written from the code)

| Document | What it gives you |
|---|---|
| [../README.md](../README.md) | purpose, services and ports, quick start, test commands, known limitations |
| [ARCHITECTURE-OVERVIEW.md](ARCHITECTURE-OVERVIEW.md) | service boundaries and real flows: customer AI, recommendations, inventory, engineering, authentication, commerce transaction, planning, sourcing, timeouts |
| [services/README.md](services/README.md) | one guide per service: frontend, Java backend, AI platform, customer AI, support, inventory, engineering, sourcing, planning |
| [DATABASE.md](DATABASE.md) | databases, ownership, tables by migration, live / imported / derived / synthetic data |
| [api/README.md](api/README.md) | endpoint tables generated from OpenAPI, the AI tool registry, real example requests and responses |
| [adr/](adr/) | architecture decisions ADR-008 … ADR-016 (AI service, inventory, support, profiles, engineering, sourcing, planning) |

## 2. Run, operate and test

| Document | What it gives you |
|---|---|
| [SETUP-AND-DEMO.md](SETUP-AND-DEMO.md) | prerequisites, configuration, seed data, roles, exact test commands, demo runbook, troubleshooting |
| [OPERATIONS.md](OPERATIONS.md) | start-up order, readiness checks, logs, shutdown, rebuild, recovery, limitations |
| [../e2e/README.md](../e2e/README.md) | real-stack scripts, browser journeys, `validate-all.sh` and how results are recorded |
| [STATUS.md](STATUS.md) | evidence-based status matrix and the latest validation results |
| [VALIDATION-MATRIX.md](VALIDATION-MATRIX.md) | every suite: exact command, result, basis (mocks / local DB / real stack), skips, evidence and the Phase 6 failure analysis |

## 3. Security

| Document | What it gives you |
|---|---|
| [SECURITY.md](SECURITY.md) | controls, what was reviewed and fixed, residual risks |
| [security/THREAT-MODEL.md](security/THREAT-MODEL.md) | threats T1-T15 mapped to components and tests; residual risks R1-R11 with owners |
| [security/AUTHORIZATION-MATRIX.md](security/AUTHORIZATION-MATRIX.md) | role-by-operation matrix observed on the real stack |
| [security/RISK-REGISTER.md](security/RISK-REGISTER.md) | R1-R12 by severity with scenario, mitigation, remediation, verification test; dependency-scanning and CI recommendation |

These are the project's own review and tests. No penetration test, external audit or certification has been performed.

## 4. Per-phase detail (current)

[ai/](ai/01-architecture.md) (Customer AI, files 01-17) · [ai/phase2/](ai/phase2/README.md) Inventory Intelligence · [ai/phase3/](ai/phase3/README.md) Support · [ai/phase4/](ai/phase4/README.md) Engineering AI · [ai/phase5/](ai/phase5/README.md) Product & Supplier Intelligence · [ai/phase6/](ai/phase6/README.md) Sales & Inventory Planning · [management-feedback/](management-feedback/README.md) tickets, profiles, budget-aware recommendations.

## 5. Learn the code

[learning-guide/](learning-guide/00-master-learning-guide.md) is the code-grounded study guide (22 chapters plus a consolidated edition and a coverage map); read it in the order given in its master guide in [learning-guide/23-stabilization-walkthroughs.md](learning-guide/23-stabilization-walkthroughs.md) (verified workflow walkthroughs) and in [learning-guide/24-service-reading-plan.md](learning-guide/24-service-reading-plan.md) (per service: first files, a request to trace, tables, proving tests). [learning/](learning/) holds one early DDD primer. [ENGINEERING_HANDBOOK.md](ENGINEERING_HANDBOOK.md) and [Architecture-Learning.md](Architecture-Learning.md) are general concept notes written while building the first modules.

## 6. Historical records (do not treat as current)

| Document | Why it is kept | Caveat |
|---|---|---|
| [architecture.md](architecture.md) | the original target architecture (v2.0, 1182 lines) | states itself as target, not as-built: variants, RS256, response envelope, billing/returns/franchise differ from the code |
| [identity-domain-design.md](identity-domain-design.md) | early requirements for the identity module | "Draft"; the implemented module differs in places |
| [pr10-review.md](pr10-review.md), `pr10`…`pr23-summary.md` | per-pull-request notes from the first build | describe the state at that PR |
| [baseline/](baseline/README.md) | the 2026-10-05 audit and remediation log | findings are kept unchanged as the baseline record; later status supersedes them |
| [DSports-AI-learning-guide/](DSports-AI-learning-guide/) and `DSports-AI-learning-guide.zip` | an exported copy of the learning guide | may lag the live `learning-guide/` folder |

## 7. Rules for keeping these documents true
* A command is marked *(verified)* only if it was run successfully; otherwise it is described as untested.
* Generated files (`api/*.md`, `security/AUTHORIZATION-MATRIX.md`) are regenerated, never edited by hand.
* New route or tool: classify it in the route-sweep and tool-matrix tests, regenerate `api/`, and update the owning service guide.
* New architecture decision: add an ADR. New module: add a README or a service guide.
