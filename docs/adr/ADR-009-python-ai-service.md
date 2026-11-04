# ADR-009: Python AI Service for the Customer Assistant

## Status

Accepted (2026-10-07). Implementation starts in Phase B; nothing of the service exists yet.

## Context

Phase 1 of the AI roadmap is a customer assistant: product discovery, product knowledge (RAG), order tracking,
returns help. `AGENTS.md` lists a Java-only backend and `docs/architecture.md` assumed Spring AI (V3) inside the
modular monolith, with "extract AI as a standalone service" as the documented escape hatch.

The agent workflow (LangGraph), the Model Context Protocol SDK, document extraction/evaluation tooling and the model
provider SDKs are materially more mature in Python. AI traffic is also latency-heavy and failure-prone compared with
checkout and payments.

## Decision

Build the AI layer as a **separate Python service** (FastAPI, LangGraph, official MCP SDK) that talks to the DSports
backend only through its public REST APIs, forwarding the customer's own JWT. The Java backend keeps all commerce
logic and remains the source of truth. The service owns its own database (`dsports_ai`, pgvector).

```text
Java:   Commerce domain, Price, Inventory, Orders, Payments, Refunds, Shipments, Returns, Authentication/authorization
Python: AI orchestration, Intent understanding, LangGraph, RAG, Embeddings, MCP, LLM interaction,
        AI-specific state/observability
```

## Consequences

- (+) Failure isolation: an AI outage or runaway cost cannot take down checkout; commerce works without AI.
- (+) Authorization stays in one place (Spring). The AI is never more privileged than the customer.
- (+) Access to the Python AI ecosystem; independent scaling and deployment.
- (−) A second runtime to build, test, deploy and secure, and extra docs/CI.
- (−) Deviates from the Java-only stack in `AGENTS.md` and from the Spring AI assumption in `architecture.md`
  (both updated). The empty `com.dsports.ai` module stays unused.
- (−) An additional network hop and a second JWT validation path; the HS256 shared secret must be provisioned to it.

## Alternatives considered

- **Spring AI inside the monolith** — one runtime, but weaker agent/MCP/evaluation tooling, and AI failures share a
  process with commerce.
- **Python service with direct database access** — rejected: it would bypass Spring's ownership checks and duplicate
  commerce rules.
- **Separate Python service with its own service credential** — rejected for Phase 1: forwarding the customer's token
  needs no new trust relationship.
