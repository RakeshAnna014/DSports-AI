# ENGINEERING_HANDBOOK.md

# DSports-AI Engineering Handbook

> This handbook captures every important engineering concept learned while building DSports-AI.
>
> The goal is not to memorize definitions, but to understand:
>
> - Why the concept exists
> - When to use it
> - When NOT to use it
> - How it applies to DSports-AI
> - Common interview questions
> - Common mistakes

---

# Table of Contents

## 1. Software Architecture

- Clean Architecture
- Domain Driven Design (DDD)
- Modular Monolith
- Microservices
- SOLID Principles
- Dependency Inversion
- Layered Architecture
- Hexagonal Architecture

---

## 2. Spring Boot

- WebFlux
- R2DBC
- Flyway
- Spring Security
- Profiles
- Configuration
- OpenAPI
- Actuator

---

## 3. Database

- PostgreSQL
- Transactions
- Indexes
- Optimistic Locking
- Connection Pooling

---

## 4. Reactive Programming

- Mono
- Flux
- Backpressure
- Scheduler
- Blocking vs Non-blocking

---

## 5. AI Engineering

- LLM
- Prompt Engineering
- Tokens
- Embeddings
- RAG
- AI Agents
- MCP
- Vector Database

---

## 6. React

- Components
- Hooks
- Routing
- State Management

---

## 7. DevOps

- Docker
- GitHub Actions
- GCP
- Monitoring

---

# Learning Log

Every time a new concept is introduced, document it here.

# 📘 Lesson 1 – Entity, Value Object & Aggregate

## What is an Entity?

### Definition

An Entity is a business object that has a unique identity.

Its attributes may change over time, but its identity remains the same.

### Key Question

> Does this object have its own identity?

If YES → It is likely an Entity.

### Characteristics

- Has a unique ID
- State changes over time
- Identity never changes
- Can be updated

### DSports-AI Examples

- User
- Product
- Brand
- Category
- Coupon
- Inventory
- Cart Item
- Order Item

### Real World Example

A person can change:

- Name
- Phone Number
- Address

But remains the same person because identity does not change.

---

## What is a Value Object?

### Definition

A Value Object has no identity.

It is identified only by its values.

If two Value Objects contain the same values, they are considered equal.

### Key Question

> Does this object only represent data?

If YES → It is likely a Value Object.

### Characteristics

- No ID
- Immutable (preferably)
- Compared by value
- No lifecycle

### DSports-AI Examples

- Email
- Money
- Address (Version 1)
- Phone Number
- Customer Name

### Real World Example

₹500 is always ₹500.

It doesn't matter which note it is.

Only the value matters.

---

## What is an Aggregate?

### Definition

An Aggregate is a group of related business objects that are treated as a single unit for maintaining business consistency.

Every Aggregate has one Aggregate Root.

External systems communicate only with the Aggregate Root.

### Why do Aggregates exist?

To protect business rules.

To ensure data consistency.

To prevent different modules from modifying internal objects directly.

### Example

Order

├── Order Items

├── Shipping Address

├── Status

Only Order can modify its Order Items.

Other modules cannot directly modify Order Items.

---

# 📘 Lesson 2 – How to Identify an Entity or Value Object

Ask these questions in order.

## Question 1

Does it have its own identity?

No

↓

Value Object

Yes

↓

Go to Question 2

---

## Question 2

Can it exist independently?

No

↓

Entity inside another Aggregate

Yes

↓

Go to Question 3

---

## Question 3

Does it own business rules for other objects?

Yes

↓

Aggregate Root

No

↓

Entity

---

# 📘 Lesson 3 – Business Lifecycle Determines Aggregate Boundaries

## Principle

If a business concept has its own lifecycle, business rules, and can evolve independently, it is a strong candidate to become its own Aggregate.

### Examples

User

Registration

↓

Activation

↓

Lock

↓

Delete

Own lifecycle

Aggregate

---

Order

Created

↓

Confirmed

↓

Paid

↓

Completed

Own lifecycle

Aggregate

---

Payment

Initiated

↓

Pending

↓

Success

↓

Failed

↓

Refunded

Own lifecycle

Aggregate

---

Shipment

Created

↓

Packed

↓

Dispatched

↓

Delivered

↓

Returned

Own lifecycle

Aggregate

---

Inventory

Stock Added

↓

Reserved

↓

Sold

↓

Returned

↓

Damaged

↓

Transferred

Own lifecycle

Aggregate

---

Email

No lifecycle

↓

Value Object

---

Money

No lifecycle

↓

Value Object

---

# 📘 Lesson 4 – Business Capability Determines Aggregate Boundaries

Do NOT ask

"Which Java class owns this?"

Instead ask

"Which business capability owns this?"

| Business Capability | Aggregate |
|---------------------|-----------|
| Identity | User |
| Shopping | Cart |
| Ordering | Order |
| Payment | Payment |
| Logistics | Shipment |
| Inventory | Inventory |
| Returns | Return |

---

# 📘 Lesson 5 – Related ≠ Owned

One Aggregate can reference another Aggregate.

That does NOT mean they belong together.

Example

Order

↓

references

↓

Payment

Payment is NOT inside Order.

Shipment

↓

references

↓

Order

Shipment is NOT inside Order.

Return

↓

references

↓

Shipment

Return is NOT inside Shipment.

Inventory

↓

references

↓

Product

Inventory is NOT inside Product.

### Principle

Related does NOT mean Owned.

---

# 📘 Lesson 6 – How Architects Think

Junior Developer

"Where should this class go?"

Senior Architect

"What business problem does this solve?"

Junior Developer

"How do I implement this?"

Senior Architect

"What business capability owns this?"

Junior Developer

"This object uses another object."

Senior Architect

"Does this object have its own lifecycle?"

---

# Interview Takeaways

## Difference between Entity and Value Object

Entity

- Has Identity
- Mutable
- Lifecycle

Value Object

- No Identity
- Compared by Value
- Usually Immutable

---

## Difference between Entity and Aggregate

Entity

Business object with identity.

Aggregate

Cluster of related entities managed through a single Aggregate Root.

---

## Golden Rules

✅ Identity matters → Entity

✅ Value matters → Value Object

✅ Business consistency matters → Aggregate

✅ Business lifecycle determines Aggregate boundaries.

✅ Business capability determines Aggregate boundaries.

✅ Related does NOT mean Owned.

