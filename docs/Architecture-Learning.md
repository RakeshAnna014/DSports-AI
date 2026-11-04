# Application Layer

## Purpose

The Application Layer orchestrates business use cases.

It coordinates the interaction between the Domain, external systems, and infrastructure.

It does not contain business rules.

## Responsibilities

- Execute business use cases
- Coordinate Domain objects
- Call repository interfaces (Ports)
- Publish domain events
- Return results to callers

## It should NOT

- Contain business rules
- Know database implementation
- Know HTTP
- Know JWT implementation
- Know Spring framework

## Simple Rule

Domain = "What is allowed?"

Application = "What needs to happen?"

Infrastructure = "How does it happen?"

# Infrastructure Layer

Purpose

The Infrastructure Layer provides concrete implementations of the interfaces (Ports) defined by the Application Layer.

It is the only layer that knows about frameworks such as Spring Boot, R2DBC, BCrypt, SMTP, Kafka, or external APIs.

Responsibilities

- Implement repository interfaces
- Implement password encoding
- Publish domain events
- Send notifications
- Integrate external systems

Rules

Infrastructure depends on Application and Domain.

Application never depends on Infrastructure.

Domain never depends on Infrastructure.

Simple Rule

Domain = Business

Application = Workflow

Infrastructure = Technology