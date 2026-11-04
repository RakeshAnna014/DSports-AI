# Phase 3: AI Customer Support and Case Resolution

Phase 1 helps customers shop. Phase 2 helps the business stock. Phase 3 helps customers **get problems resolved**: the assistant investigates a complaint
from the customer's own records, cites policy, says what it verified and what it could not, recommends the next step, and (after confirmation) creates a
**real, persisted support case** that staff work in a protected console. Java stays the source of truth for every transactional fact and every case.

> **Read this first.** No case ID, investigation, evidence item or status in this system is invented. A case number appears only after the Java backend
> returned it. Sample policies are labelled demo documents; no official DSports SLA exists or is claimed. The assistant cannot resolve, close, assign,
> refund, cancel, approve or reverse anything, cannot see images, cannot contact a carrier or warehouse, and cannot connect a customer to a live agent.

| # | Document | Contents |
|---|---|---|
| 01 | [Architecture](01-support-architecture.md) | components, request flow, what is where and why |
| 02 | [Case data model](02-case-data-model.md) | tables, fields, evidence provenance, API shapes |
| 03 | [Case lifecycle](03-case-lifecycle.md) | statuses, transitions, escalation states, idempotency, concurrency |
| 04 | [Investigation workflows](04-investigation-workflows.md) | complaint types, tools per type, facts / inferences / missing |
| 05 | [Policy and knowledge RAG](05-policy-rag.md) | documents, citations, internal separation, honesty when nothing is found |
| 06 | [Tool registry and MCP](06-tool-registry-and-mcp.md) | the 4 READ and 4 WRITE tools, schemas, safety, MCP |
| 07 | [Resolution and escalation](07-resolution-and-escalation.md) | the decision rules, priority rules, when a person is asked |
| 08 | [Security and authorization](08-security-and-authorization.md) | roles, ownership, guard, tokens, tests |
| 09 | [Audit and observability](09-audit-and-observability.md) | audit trails, metrics, analytics definitions |
| 10 | [Evaluation](10-evaluation.md) | datasets, first-run held-out results, metrics, gates |
| 11 | [Testing and runbook](11-testing-and-runbook.md) | commands, test counts, validation, troubleshooting |
| 12 | [Demo scenarios](12-demo-scenarios.md) | the management demonstration, step by step |
| 13 | [Known limitations](13-known-limitations.md) | what is not done, with evidence |
| 14 | [Final report](14-final-report.md) | status, results, regression, blockers, recommendation |

Decision record: [ADR-011](../../adr/ADR-011-customer-support-resolution.md). Phase 1: [`docs/ai/`](..). Phase 2: [`docs/ai/phase2/`](../phase2).
