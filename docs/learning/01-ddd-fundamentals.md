# Architecture Learning Notes

> These are my personal notes while building **DSports-AI**.
>
> Goal:
> - Understand the concepts deeply.
> - Revise before interviews.
> - Remember why a design decision was taken.
> - Learn Architecture by building.

---

# Chapter 1 - Entity

## Definition

An **Entity** is an object that has a unique identity.

Even if its properties change, it is still considered the same object.

**Identity is more important than its values.**

---

## Real World Example

Think about yourself.

```
Name  : Rakesh
Age   : 35
Email : abc@gmail.com
Phone : 9876543210
```

After one year,

```
Age = 36

Phone changes

Email changes
```

Are you still the same person?

✅ Yes.

Because your identity never changed.

---

## Software Example

```
User

UserId = U1001
```

Today

```
Name = Rakesh
```

Tomorrow

```
Name = Rakesh Kumar
```

Next Year

```
Email changes
```

Still

```
UserId = U1001
```

Same User.

Therefore,

**User is an Entity.**

---

## DSports-AI Examples

- User
- Product
- Brand
- Category
- Cart
- Order
- Shipment

---

## Characteristics

✔ Has Identity

✔ Mutable

✔ Long Lifecycle

✔ Equality is based on Identity

---

## Rule

If identity matters more than values,

it is an **Entity**.

---

# Chapter 2 - Value Object

## Definition

A **Value Object** has no identity.

Only its value matters.

If its value changes,

it becomes a different object.

---

## Example

Money

```
₹500
```

One ₹500 note is equal to another ₹500 note.

Nobody cares which ₹500 note it is.

Only the value matters.

---

Another example

Email

```
abc@gmail.com
```

There is no

```
EmailId = 100
```

Email itself is the value.

---

## DSports Examples

- Email
- Money
- CustomerName
- PhoneNumber
- UserId
- CartItem
- OrderItem

---

## Characteristics

✔ Immutable

✔ No Identity

✔ Equality is based on values

---

## Why Immutable?

Bad

```java
email.setValue("xyz@gmail.com");
```

Good

```java
Email email = Email.of("xyz@gmail.com");
```

Always create a new Value Object.

Never modify the existing one.

---

## Rule

If value matters more than identity,

it is a **Value Object**.

---

# Chapter 3 - Aggregate

## Definition

An **Aggregate** is a group of related objects that should always remain consistent.

Think of it as a **Business Boundary**.

---

## Example

Order contains

```
Order

OrderItems

Discount

Taxes

Price
```

All belong together.

Changing one may affect others.

---

## Why Aggregate?

Without Aggregate,

developers can update child objects directly,

breaking business rules.

Aggregate protects consistency.

---

## Characteristics

✔ Business Boundary

✔ Contains Entities and Value Objects

✔ Maintains Consistency

---

## Rule

Everything inside an Aggregate must remain valid together.

---

# Chapter 4 - Aggregate Root

## Definition

Aggregate Root is the single entry point to an Aggregate.

Every change must go through it.

---

Example

```
Order

├── OrderItem

├── OrderItem

└── OrderItem
```

Never update

```
OrderItem
```

directly.

Always

```
Order

↓

addItem()

↓

removeItem()

↓

changeQuantity()
```

---

## Why?

Because Order validates business rules.

Example

```
Maximum 100 items

Coupon rules

Price calculation

Tax calculation
```

OrderItem knows none of these.

Order knows everything.

---

## DSports Examples

Aggregate Roots

- User
- Order
- Shipment
- Payment
- Cart

---

## Rule

Never expose repositories for child objects.

❌ Bad

```
OrderItemRepository
```

✅ Good

```
OrderRepository
```

---

# Chapter 5 - Domain Event

## Definition

A **Domain Event** represents something important that has already happened.

Always use Past Tense.

---

Examples

✅ UserRegistered

✅ OrderPlaced

✅ PaymentCompleted

✅ ShipmentDelivered

---

Not

```
RegisterUser
```

because

that is a Command.

---

## Flow

```
Register User

↓

User Aggregate

↓

UserRegisteredEvent

↓

Email Service

↓

Notification

↓

Analytics
```

The User Aggregate does not know who is listening.

It only announces

"I was registered."

---

## Why Domain Events?

Without Events

```
User

↓

Email Service

↓

Analytics

↓

Reward Service

↓

SMS
```

User depends on everything.

With Events

```
User

↓

UserRegisteredEvent

↓

Email Service

Reward Service

Analytics

SMS
```

Now everything is loosely coupled.

---

## Characteristics

✔ Immutable

✔ Past Tense

✔ Represents Business Facts

✔ No Business Logic

---

# Summary Table

| Concept | Identity | Mutable | Example |
|----------|----------|----------|---------|
| Entity | ✅ Yes | ✅ Yes | User, Product |
| Value Object | ❌ No | ❌ No | Email, Money |
| Aggregate | Business Boundary | Yes | Order |
| Aggregate Root | Entry Point | Yes | Order, User |
| Domain Event | Business Fact | ❌ No | OrderPlacedEvent |

---

# DSports-AI Mapping

| Object | Type | Reason |
|---------|------|--------|
| User | Entity + Aggregate Root | Identity and business rules |
| Email | Value Object | Immutable value |
| Money | Value Object | Compared by value |
| Product | Entity | Has its own identity |
| Brand | Entity | Independent identity |
| Category | Entity | Independent identity |
| Cart | Aggregate Root | Controls CartItems |
| CartItem | Value Object | Exists only inside Cart |
| Order | Aggregate Root | Controls OrderItems |
| OrderItem | Value Object | Exists only inside Order |
| Shipment | Aggregate Root | Independent lifecycle |
| Payment | Aggregate Root | Independent lifecycle |

---

# Golden Rules

## Entity

Identity matters.

---

## Value Object

Value matters.

---

## Aggregate

Protect business consistency.

---

## Aggregate Root

Everything enters through me.

---

## Domain Event

I announce something important happened.

---

# Interview Questions

Try answering these without looking at the notes.

1. What is an Entity?
2. Why is User an Entity?
3. What is a Value Object?
4. Why is Email a Value Object?
5. Why should Value Objects be immutable?
6. What is an Aggregate?
7. What is an Aggregate Root?
8. Why shouldn't OrderItemRepository exist?
9. What is a Domain Event?
10. Why are Domain Events named in Past Tense?
11. What is the difference between a Command and a Domain Event?
12. Why is Shipment a separate Aggregate in DSports-AI?

---

# Things to Remember

> **Entity → Identity matters**

> **Value Object → Value matters**

> **Aggregate → Protects consistency**

> **Aggregate Root → Single entry point**

> **Domain Event → Announces business facts**

---

## Learning Status

- [x] Entity
- [x] Value Object
- [x] Aggregate
- [x] Aggregate Root
- [x] Domain Event
- [ ] Ubiquitous Language
- [ ] Bounded Context
- [ ] Domain Service
- [ ] Repository Pattern
- [ ] Factory Pattern
- [ ] Specification Pattern
- [ ] Anti-Corruption Layer
- [ ] Context Mapping

## Business Rule vs Technical Rule

A software architect should never hardcode business assumptions.

Example:

❌ Franchise owners always pay cost price.

Better:

✔ Pricing is determined by a Pricing Policy.

The Pricing Policy decides:

- Customer Price
- Franchise Price
- Employee Price
- Promotional Price
- Bulk Price

The Order system should ask the Pricing Policy instead of deciding the price itself.

## Business Policies should be configurable

Avoid hardcoding business rules.

Bad:

if (price > 10000) {
    throw Exception();
}

Good:

PricingPolicy

- Minimum Selling Price
- Maximum Selling Price
- Effective Date
- Region
- Currency

The software reads business policies instead of embedding them in code.

Benefits:

- No code changes for pricing updates.
- Business users can change policies.
- Supports future promotions and regional pricing.

## Brand Value is a Business Asset

Inventory should not automatically influence selling price.

Reasons:

- Customers compare prices.
- Frequent price changes reduce trust.
- Stable pricing builds a premium brand.

Inventory should influence:

- Reorder decisions
- Procurement
- Manufacturing
- Stock allocation

NOT customer-facing prices.

Dynamic pricing is suitable for:

- Airlines
- Hotels
- Ride-sharing
- Marketplaces

Stable pricing is suitable for:

- Sports brands
- Luxury brands
- Electronics brands
- Consumer brands

Choose the pricing strategy based on the business model, not the technology.

## Products can have different business models

Not every product follows the same sales process.

Examples:

Normal Product
- Fixed Price
- Unlimited Stock
- Standard Checkout

Limited Edition Product
- Fixed Premium Price or Auction
- Limited Quantity
- Certificate of Authenticity
- Serial Number

Collectible Products should be treated as a separate business capability rather than extending the normal product model with many special-case rules.

## Identity vs Authentication

A User represents an identity.

Authentication is simply a way to prove ownership of that identity.

One User can have multiple authentication methods.

Examples:

- Email + Password
- Google OAuth
- Mobile OTP
- Apple Login
- Facebook Login

Authentication methods should not create duplicate users.

Instead, they should be linked to a single User identity.

# Domain Service

## Definition

A Domain Service contains business logic that does not naturally belong to a single Entity or Aggregate.

## When to use

Use a Domain Service when the business rule involves multiple Aggregates.

Examples

- Money Transfer
- Discount Calculation
- Shipping Cost
- Inventory Allocation
- Franchise Selection
- Product Recommendation

## Rule

Entity:
Business logic about itself.

Domain Service:
Business logic involving multiple business objects.

## Bad Example

Order.calculateShipping()

(Order should not know courier APIs.)

## Good Example

ShippingService.calculate(order, address, courierRules)

(Order provides data.
ShippingService owns the shipping algorithm.)

Remember:

If a method needs data from multiple Aggregates, it is a strong signal that it belongs in a Domain Service rather than inside an Entity.

## Soft Validation vs Final Validation

Soft Validation

- Performed during browsing or cart operations.
- Provides immediate feedback to the customer.
- Results may become stale.

Examples:
- Product appears in stock.
- Coupon looks valid.
- Estimated shipping cost.
- Estimated GST.

Final Validation

- Performed immediately before payment or order creation.
- Guarantees business correctness.
- Uses the latest system state.

Examples:
- Reserve inventory.
- Validate coupon usage limit.
- Recalculate taxes.
- Verify shipping availability.

Rule:

Never rely only on cart-time validation for business-critical operations.
Always perform a final validation before completing the transaction.

