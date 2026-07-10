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

Do NOT implement AI features until explicitly requested.

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