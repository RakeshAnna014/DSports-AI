# ROADMAP.md

# DSports-AI Roadmap

> ⚠️ **Planning document, not a status report.** The tables below mix the original plan with later notes and several are out of date. The verified, as-built status is [docs/STATUS.md](docs/STATUS.md); the documentation index is [docs/README.md](docs/README.md); the 2026-10-05 baseline audit is kept in [docs/baseline/](docs/baseline/README.md).

> **Version:** 1.0.0
>
> **Status:** 🚧 In Development
>
> **Architecture:** Modular Monolith + Clean Architecture + DDD
>
> **Backend:** Java 21 + Spring Boot WebFlux
>
> **Frontend:** React + TypeScript + Vite
>
> **Database:** PostgreSQL + R2DBC + Flyway

---

# 🎯 Vision

DSports-AI is an enterprise-grade AI-powered sports commerce platform.

Version 1 focuses on Cricket.

Future versions will support multiple sports, AI-powered shopping, intelligent recommendations, and enterprise-scale commerce.

---

# Current Progress

| Sprint | Status |
|----------|---------|
| Sprint 0 | 🟡 In Progress |
| Sprint 1 | ⏳ Pending |
| Sprint 2 | ⏳ Pending |
| Sprint 3 | ⏳ Pending |
| Sprint 4 | ⏳ Pending |
| AI Phase | ⏳ Pending |

> **Current, evidence-based status of every workstream: [docs/STATUS.md](docs/STATUS.md).** The "Phase 4" of the original sketch further down (Customer Support Agent) is delivered as *Phase 3* below; the separate **Phase 4 here is Engineering AI**.

## Delivery matrix of the AI phases actually built (supersedes the original "AI Phase" sketch below; numbering there is historical)

| Phase | Capability | Status | Evidence / where |
|---|---|---|---|
| 1 | Customer AI: search, advice, orders, returns, RAG (Python service, ADR-009) | ✅ Implemented and validated | `docs/ai/` |
| 2 | Inventory Intelligence (forecast, risk, planning; synthetic POC data, ADMIN only; ADR-010) | ✅ Implemented and validated | `docs/ai/phase2/` |
| 3 | Customer Support and Case Resolution (Java `support` module, confirmed writes; ADR-011) | ✅ Implemented; one held-out miss and no single clean consolidated run are documented | `docs/ai/phase3/` |
| Mgmt feedback | Ticket lifecycle UI/staff tools, profiles + family, budget-aware recommendations (ADR-012) | ✅ Implemented; evidence upload and SLA/overdue not built | `docs/management-feedback/` |
| 4 | **Engineering AI**: log monitoring and incident intelligence (ADR-013) | ✅ Implemented with real backend/AI/Postgres logs and a labelled synthetic incident. **Not connected: frontend logs, a central logging platform, CI/CD deployments (manual), metrics/traces** | `docs/ai/phase4/README.md` |

---

# Sprint 0 — Foundation

## Goal

Build the enterprise foundation.

No business logic.

---

### Story 0.1

Workspace Setup

Status

✅ Completed

Deliverables

- Git Repository
- Monorepo
- Initial Documentation

---

### Story 0.2

Project Skeleton

Status

✅ Completed

Deliverables

- Backend Modules
- Shared Kernel
- Bootstrap Module
- Empty Business Modules

---

### Story 0.3

Infrastructure

Status

🟡 In Progress

Deliverables

- Spring Boot
- WebFlux
- PostgreSQL
- R2DBC
- Flyway
- Profiles
- Docker
- Logging
- OpenAPI
- Actuator

---

### Story 0.4

Frontend Foundation

Status

⏳ Pending

Deliverables

- React
- TypeScript
- Vite
- Material UI
- Routing
- Layout
- Home Page

---

# Sprint 1 — Identity

Goal

Authentication & User Management

Stories

- User Registration
- Login
- JWT Authentication
- Role Management
- User Profile
- Password Encryption
- Refresh Token

---

# Sprint 2 — Product Catalog

Stories

- Categories
- Products
- Brands
- Product Images
- Search
- Pagination
- Filters
- Product Details

---

# Sprint 3 — Shopping

Stories

- Shopping Cart
- Wishlist
- Checkout
- Orders
- Payment Abstraction
- Billing
- Invoice

---

# Sprint 4 — Franchise & Admin

Stories

- Franchise Management
- Inventory
- Admin Dashboard
- Reports
- Notifications
- Returns

---

# AI Phase

## Phase 1

AI Product Search

Examples

> "Show me lightweight cricket bats under ₹4000"

---

## Phase 2

AI Shopping Assistant

Examples

> "Build a cricket kit for a beginner under ₹15000"

---

## Phase 3

Recommendation Engine

Examples

- Frequently Bought Together
- Similar Products
- Personalized Suggestions

---

## Phase 4

Customer Support Agent

Examples

- Product Questions
- Order Status
- Return Assistance

---

## Phase 5

Demand Forecasting

Examples

- Seasonal Stock Prediction
- Franchise Inventory Planning

---

## Phase 6 (done): Sales & Inventory Planning

Store-level weekly forecasts, targets, approvals, plan vs actual, inventory allocation and scenarios on demonstration store data and live online orders. See docs/ai/phase6/README.md. Next: a real store sales feed, stock-out and promotion history, procurement/transfer APIs.

---

# Future Enhancements

- Multi Vendor Marketplace
- Multi Currency
- Multi Language
- Mobile App
- Loyalty Program
- Coupons
- Promotions
- Elasticsearch
- Redis
- Kafka
- Kubernetes
- Event Driven Architecture
- AI Pricing
- AI Fraud Detection

---

# Out of Scope (Version 1)

The following will NOT be implemented in Version 1.

- Kubernetes
- Kafka
- Redis
- CQRS
- Event Sourcing
- Elasticsearch
- AI Agents
- Recommendation Engine
- Marketplace
- Multi Currency
- Multi Language

---

# Architecture Milestones

| Milestone | Status |
|------------|---------|
| Requirements | ✅ |
| Software Architecture | ✅ |
| Project Principles | ✅ |
| AGENTS.md | ✅ |
| ROADMAP.md | ✅ |
| Backend Skeleton | 🟡 |
| Frontend Skeleton | ⏳ |
| Authentication | ⏳ |
| Product Catalog | ⏳ |
| Shopping Cart | ⏳ |
| Orders | ⏳ |
| AI Integration | ⏳ |
| Production Deployment | ⏳ |

---

# Definition of Done

A story is considered complete only if:

- Code Compiles
- Unit Tests Pass
- No Critical Issues
- OpenAPI Updated
- Documentation Updated
- Reviewed
- Approved
- Merged

---

# Engineering Workflow

Business Requirement

↓

Architecture Discussion

↓

Design Review

↓

AI Implementation

↓

Code Review

↓

Testing

↓

Documentation

↓

Git Commit

↓

Merge

---

# Project Principles

- Business First
- Architecture Before Code
- AI Assists, Humans Decide
- Small Iterations
- Production Quality
- Understand Every Line
- Simplicity Over Complexity
- Continuous Learning

---

# Current Focus

**Current Sprint:** Sprint 0

**Current Story:** Story 0.3 - Infrastructure

**Next Story:** Story 0.4 - Frontend Foundation

---

# Notes

This roadmap is a living document.

Every sprint completion should update this file.

Every new feature must first be added to this roadmap before implementation begins.