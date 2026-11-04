# AGENTS.md

# DSports-AI

## Project Overview

DSports-AI is an enterprise-grade AI-powered Sports Commerce Platform.

Version 1 focuses on Cricket equipment and accessories.

Future versions will support:
- Football
- Badminton
- Volleyball
- Basketball
- Fitness
- Other sports

The objective of this project is NOT simply to build an e-commerce application.

The goal is to build a production-ready enterprise application while learning modern software architecture, AI Engineering, React, Spring Boot, and Cloud Native development.

---

# Tech Stack

## Backend

- Java 21
- Spring Boot
- Spring WebFlux
- Spring Security
- Spring Data R2DBC
- PostgreSQL
- Flyway
- Maven
- Docker
- OpenAPI
- Actuator

Future

- Spring AI
- Kafka
- Redis
- Vector Database

---

## Frontend

- React
- TypeScript
- Vite
- Material UI
- React Router
- Axios

---

## AI Service (Customer AI Phase 1 and Inventory Intelligence Phase 2 — Python, implemented in `ai-service/`; see `docs/ai/` and `docs/ai/phase2/`)

- Python 3.13, uv
- FastAPI
- LangGraph (one Customer Agent)
- MCP (official SDK)
- Gemini / Vertex AI (gemini-2.5-flash-lite, gemini-2.5-flash, text-embedding-005)
- pgvector (separate `dsports_ai` database), Apache Tika + Tesseract for documents

Intentional architecture decision — see `docs/adr/ADR-009-python-ai-service.md`:

```text
Java:
Commerce domain
Price
Inventory
Orders
Payments
Refunds
Shipments
Returns
Authentication/authorization

Python:
AI orchestration
Intent understanding
LangGraph
RAG
Embeddings
MCP
LLM interaction
AI-specific state/observability
```

The AI service calls DSports only through its public REST APIs, forwarding the customer's JWT. It never owns or
invents price, stock, order, payment, refund, shipment or return data.

---

## DevOps

- Docker
- GitHub Actions
- GCP Cloud Run

---

# Architecture

Always follow:

- Clean Architecture
- Domain Driven Design (DDD)
- Modular Monolith
- SOLID Principles
- DRY
- KISS
- Reactive Programming

Do NOT redesign architecture unless explicitly requested.

The separate Python AI service (above) is an approved architecture decision (ADR-009), not a redesign. The Java
backend remains Java/Spring.

---

# Coding Standards

Always

- Constructor Injection
- Java Records where appropriate
- Global Exception Handling
- Validation
- DTO Pattern
- Immutable Objects where possible
- SLF4J Logging
- Meaningful Naming

Never

- Field Injection
- System.out.println()
- Hardcoded Credentials
- Business Logic inside Controllers
- Duplicate Code
- Blocking Calls inside Reactive Flows

---

# Configuration Rules

Use

- Spring Profiles
- Environment Variables
- Docker

Never hardcode

- URLs
- Passwords
- API Keys
- Secrets

---

# AI Development Rules

Treat AI as a Senior Software Engineer.

Never generate entire applications.

Always implement one User Story at a time.

For every implementation

1. Explain Design
2. Explain Alternatives
3. Explain Trade-offs
4. Generate Code
5. Wait for Review

---

# Git Rules

Small commits.

One feature per commit.

Every commit must compile.

Meaningful commit messages only.

Never leave commented code.

---

# Testing Rules

Future implementation must include

- Unit Tests
- Integration Tests
- Testcontainers

Never ignore failing tests.

---

# Documentation Rules

Every public REST API

→ OpenAPI

Every architecture decision

→ ADR

Every module

→ README

---

# Current Scope

Version 1

- Authentication
- Product Catalog
- Categories
- Inventory
- Cart
- Orders
- Payments
- Billing
- Franchise
- Notifications

Do NOT implement AI features until explicitly requested. (Phase 1 Customer AI and Phase 2 Inventory Intelligence were explicitly requested; their scope and status are in `docs/ai/` and `docs/ai/phase2/`, architecture in ADR-009 and ADR-010.)

Phase 2 rules (see `docs/ai/phase2/README.md`): forecasting and every inventory number come from deterministic code over persisted model output, never from an LLM; the LLM only understands questions, orchestrates tools and explains; inventory intelligence is internal (ADMIN role enforced in the backend) and decision support only (no price change, purchase order, transfer or promotion is ever executed); all demand history, stock positions, cost, supplier, region and event data in it is labelled POC synthetic / POC reference data and must never be presented as real business data.

Phase 3 rules (Customer Support and Case Resolution; see `docs/ai/phase3/README.md` and ADR-011): cases live in the Java `support` module (the AI service never stores one); a case number, status or escalation state is shown only if the backend returned it; investigation and resolution decisions are deterministic code over verified tool results, never an LLM; creating or changing a case is a confirmed WRITE for the customer's own case only; the assistant never resolves, refunds, cancels, assigns or re-prioritises anything and never shows internal notes or other customers' cases; "escalation requested" and "escalation accepted" are different states; sample policy documents are demo content and no official SLA is claimed.

Phase 4 rules (Engineering AI; see `docs/ai/phase4/README.md` and ADR-013): log ingestion, redaction, filtering, clustering and correlation are deterministic code; an LLM may only re-word already-retrieved, already-redacted evidence and its text must cite retrieved record ids and never state an unhedged root cause; findings are facts, explanations are labelled hypotheses; log text is untrusted data (instruction-like messages are withheld) and no tool takes SQL, a path or a shell command; access is ADMIN-only on the server and every investigation is audited; synthetic records are always labelled and never presented as real; a source is "connected" only if records from it are actually stored.

Phase 5 rules (Product & Supplier Intelligence; see `docs/ai/phase5/README.md` and ADR-014): landed cost, margin, break-even and opportunity scores are deterministic code, never an LLM; a missing input makes a result INCOMPLETE and a foreign-currency figure is converted only with an operator-sourced, dated exchange rate; no supplier, quote, certification, lead time, price or market claim is ever invented, and every quote and opportunity carries its origin and source kind (public listing, entered quotation, buyer-verified, AI estimate, demo); external sources are allow-listed, SSRF-checked, size- and time-capped and their text is untrusted data; the AI service reads the catalogue only through its REST API and never writes commerce data; approval for onboarding is a recorded buyer decision, never authority to purchase, publish or price; approving needs an approver role and every decision is appended to the audit log; demonstration data is always labelled DEMO. Overhaul rules: every record has a provenance and live figures, lists, scores, margins and assistant answers use live provenance only (demo, test and unknown records are shown only on request and never counted); a customer search is a demand gap, never a product, and cannot be scored, quoted or approved; product identity (brand, model, SKU, GTIN, specifications, images) comes only from a source or a buyer and is never generated, and an image is stored as an attributed reference with a match level (exact model, likely, reference only) and never as proof of identity; supplier discovery is not verification (verification is a ladder with recorded evidence); business approval, sourcing-preparation approval and catalogue readiness are three separate things and approval never publishes, orders or prices; mocks prove parsing and safety rules only and are never presented as proof of a live provider integration.

Phase 6 rules (Sales & Inventory Planning; see `docs/ai/phase6/README.md` and ADR-015): forecasts, variances, replenishment, transfers and scenarios are deterministic code, never an LLM; model forecast, manager forecast, target, approved forecast and actual are always separate values and the model forecast is never overwritten; actual sales come only from the Java sales feed or a controlled, idempotent, audited import and one documented convention (net = gross - cancelled - returned) is applied once; every row carries its data source and store-level history, stores and stock are demonstration data labelled as such, never presented as validated or real; accuracy on thin samples is withheld and a missing week is a confirmed zero only for live data; the AI service never writes commerce data and approval, recommendations and pilots never order, move stock, publish or price; plan edits are version-checked, role- and scope-enforced on the server, and every change is audited; scenarios stay separate from plans until adopted as targets by an authorized user. AI-assisted analysis and review (ADR-016): findings are deterministic code over stored records; an LLM may only re-word them and its text is kept only if every number in it is in the findings; facts and hypotheses are always shown apart; a proposal is only ever saved by a person through the ordinary audited edit; plans built on demonstration data cannot be bulk-approved without an explicit server-checked acknowledgement; internal screens show the provenance of every figure and say why a figure is unavailable rather than showing a placeholder.

Security rules (see `docs/SECURITY.md` and `docs/security/`): authorization is enforced on the server for every route, tool and MCP call (the UI never is the control), access tokens must carry the expected issuer and audience, internal AI tools are read-only and role-gated, every model input that came from stored or external text is fenced as untrusted data and every model output is validated before use, external retrieval only goes through the allow-listed, address-checked, connection-pinned fetcher, malformed input must be a 4xx never a 500, secrets come only from the environment and never reach logs, prompts or responses, and a new route or tool is not done until the route-sweep and tool-matrix tests classify it. The security tests are the project's own; no penetration test or certification has been performed.

---

# AI Roadmap

Version 2

- AI Product Search

Version 3

- Shopping Assistant

Version 4

- Recommendation Engine

Version 5

- Customer Support Agent

Version 6

- Demand Forecasting

Version 7

- Inventory Prediction

---

# Current Sprint

Implement ONLY the current Sprint.

Never implement future Sprint functionality.

---

# Response Style

When generating code

Always explain

- Why
- How
- Trade-offs
- Enterprise Best Practice

before generating implementation.