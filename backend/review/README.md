# Reviews Module

## Overview

The Reviews module enables customers to submit, update, and manage product reviews and ratings. It supports moderation workflows, helpfulness voting, and aggregated rating summaries.

## Features

- **Create Review**: Submit a review with rating (1-5), title, and optional comment after a verified purchase.
- **Update Review**: Edit your own review.
- **Get Review**: Retrieve a specific review by ID.
- **Product Reviews**: Paginated list of reviews for a product (public).
- **Rating Summary**: Aggregate rating distribution and average for a product (public).
- **Moderate Review**: Admin approval/rejection of pending reviews.
- **Helpful Vote**: Users can vote reviews as helpful (one vote per user per review).

## Architecture

Follows Clean Architecture with DDD within the modular monolith:

```
interfaces/       → REST controllers, request/response DTOs
application/      → Use cases, ports (repository interfaces), commands/queries, results
domain/           → Aggregates (Review, HelpfulVote), value objects (Rating, ReviewComment, ReviewId), events
infrastructure/   → R2DBC repositories, Flyway migrations, event publishing
```

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/reviews` | User | Create a review |
| PUT | `/api/v1/reviews/{id}` | Owner | Update a review |
| GET | `/api/v1/reviews/{id}` | User/Admin | Get review by ID |
| GET | `/api/v1/reviews/product/{productId}` | Public | Get product reviews (paginated) |
| GET | `/api/v1/reviews/product/{productId}/summary` | Public | Get rating summary |
| POST | `/api/v1/reviews/admin/{id}/moderate` | Admin | Approve/reject review |
| POST | `/api/v1/reviews/{id}/helpful` | User | Mark review as helpful |

## Domain Events

- `ReviewCreatedEvent` — Published when a review is created
- `ReviewUpdatedEvent` — Published when a review is updated
- `ReviewApprovedEvent` — Published when a review is moderated

## Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `REVIEW_NOT_FOUND` | 404 | Review does not exist |
| `REVIEW_NOT_OWNED_BY_USER` | 403 | User does not own the review |
| `INVALID_STATUS_TRANSITION` | 409 | Invalid moderation status change |
| `INVALID_RATING` | 400 | Rating out of range |
| `INVALID_COMMENT` | 400 | Comment validation failed |
| `DUPLICATE_REVIEW` | 409 | User already reviewed this product |
| `PRODUCT_NOT_PURCHASED` | 400 | User has not purchased the product |
| `ORDER_NOT_DELIVERED` | 400 | Order has not been delivered |
| `VALIDATION_ERROR` | 400 | Input validation failed |
| `OPTIMISTIC_LOCKING_CONFLICT` | 409 | Concurrent modification detected |

## Database Schema

- `reviews` — Review entries with rating, status, and metadata
- `review_helpful_votes` — Helpful vote records (unique per user per review)

## Dependencies

- `dsports-shared` — Shared kernel and domain event infrastructure
- `dsports-order` — Order delivery verification for verified purchase check
- `dsports-catalog` — Product existence validation
