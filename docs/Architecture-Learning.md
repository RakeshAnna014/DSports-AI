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