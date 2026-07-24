# PR #18 — Payment Management

## Architecture

The Payment bounded context follows DDD + Clean Architecture with package structure:

```
com.dsports.payment/
  domain/payment/
    model/          Payment (aggregate), PaymentId, PaymentReference, TransactionId, Money, PaymentStatus, PaymentMethod, PaymentProvider
    event/          PaymentCreatedEvent, PaymentAuthorizedEvent, PaymentSucceededEvent, PaymentFailedEvent, PaymentCancelledEvent, PaymentRefundedEvent
    exception/      PaymentDomainException, PaymentErrorCode
  application/payment/
    command/        CreatePaymentCommand, CapturePaymentCommand, CancelPaymentCommand, RefundPaymentCommand
    query/          GetPaymentQuery, GetPaymentHistoryQuery
    result/         PaymentResult, PaymentSummaryResult, PaymentResultMapper
    port/           PaymentRepository, PaymentGateway, OrderPaymentPort, EventPublisher
    usecase/        CreatePaymentUseCase, CapturePaymentUseCase, CancelPaymentUseCase, RefundPaymentUseCase, GetPaymentUseCase, GetPaymentHistoryUseCase
  infrastructure/payment/
    persistence/entity/     PaymentEntity, PaymentAuditEntity
    persistence/repository/ SpringR2dbcPaymentRepository, SpringR2dbcPaymentAuditRepository, PaymentR2dbcRepositoryAdapter
    persistence/mapper/     PaymentEntityMapper
    gateway/                MockPaymentGateway
    event/                  PaymentSpringEventPublisherAdapter
    config/                 PaymentInfrastructureConfiguration
  interfaces/payment/
    dto/            CreatePaymentRequest, PaymentResponse, PaymentSummaryResponse
                    PublicPaymentController
```

Dependencies: `dsports-shared`, `spring-boot-starter-webflux/data-r2dbc/validation/security`, `swagger-annotations-jakarta`.

---

## Payment Lifecycle

```
CREATED → PENDING → AUTHORIZED → SUCCESS → REFUNDED
                     ↘              ↙
                      → FAILED →
```

Terminal states: `SUCCESS`, `FAILED`, `CANCELLED`, `REFUNDED`.

Valid transitions enforced via `PaymentStatus.canTransitionTo()`.

---

## Gateway Abstraction

The `PaymentGateway` interface defines provider-independent operations:

- `createPayment(CreatePaymentRequest)` → `GatewayResult`
- `capturePayment(gatewayReference)` → `GatewayResult`
- `cancelPayment(gatewayReference)` → `GatewayResult`
- `refundPayment(gatewayReference, Money)` → `GatewayResult`
- `getPaymentStatus(gatewayReference)` → `GatewayStatus`

New providers (Stripe, Razorpay, PayPal) only need to implement this interface and be registered as a `@Component` or switched via configuration.

---

## Mock Gateway

`MockPaymentGateway` simulates all payment operations with configurable behaviour:

| Property | Effect |
|----------|--------|
| `payment.mock.mode=success` | All operations succeed |
| `payment.mock.mode=failure` | Create payment always fails |
| `payment.mock.mode=timeout` | Create payment simulates timeout |
| `payment.mock.mode=cancel` | Create payment simulates cancellation |

Default mode: `success`.

No external dependencies required. All responses are generated with synthetic transaction and gateway reference IDs.

---

## Business Rules Enforced

- User must be authenticated
- Order must exist and belong to user
- Order must not already be paid
- Payment amount must exactly match Order Grand Total
- Each Order can have only one successful payment
- Unique Payment Reference generated (`PAY-YYYYMMDD-XXXX-XXXXXXXX`)
- Full payment audit trail stored in `payment_audit` table
- Refunds only for SUCCESS payments
- Cancelled payments cannot become successful
- Optimistic locking via `@Version` (HTTP 409 on conflict)

---

## REST API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/payments` | Create payment (validates order, initiates via gateway) |
| POST | `/api/v1/payments/{id}/capture` | Capture/confirm payment |
| POST | `/api/v1/payments/{id}/cancel` | Cancel payment |
| POST | `/api/v1/payments/{id}/refund` | Refund successful payment |
| GET | `/api/v1/payments/{id}` | Get payment details |
| GET | `/api/v1/payments/history` | Get user's payment history |

All endpoints tagged with `@Tag(name = "Payments")` and documented with `@Operation` / `@ApiResponses`.

---

## Frontend Integration

| Route | Component | Description |
|-------|-----------|-------------|
| `/payment?orderId=&amount=&currency=&orderNumber=` | PaymentPage | Multi-step payment flow (summary → method → pay) |
| `/payment-success` | PaymentSuccessPage | Success confirmation with reference, amount, order |
| `/payment-failure` | PaymentFailurePage | Failure message with retry option |
| `/payments` | PaymentHistoryPage | Table of all user payments with status chips |

---

## Database Schema (Flyway V22)

**payments** table:
- `id` UUID PK, `payment_reference` VARCHAR(50) UNIQUE, `order_id` UUID, `user_id` UUID, `amount` DECIMAL(12,2), `currency` VARCHAR(3), `payment_method` VARCHAR(20), `payment_provider` VARCHAR(20), `transaction_id` VARCHAR(100), `gateway_reference` VARCHAR(100), `status` VARCHAR(20) with CHECK constraint, `failure_reason` TEXT, `paid_at` TIMESTAMPTZ, `version` INTEGER, `created_at` / `updated_at` TIMESTAMPTZ

**payment_audit** table:
- `id` UUID PK, `payment_id` UUID FK → payments(id), `event_type` VARCHAR(50), `from_status` VARCHAR(20), `to_status` VARCHAR(20), `details` TEXT, `created_at` TIMESTAMPTZ

Indexes on: `payment_reference` (unique), `order_id`, `user_id`, `status`.

---

## Future Stripe Integration

```java
@Component
public class StripePaymentGateway implements PaymentGateway {
    // Implement createPayment -> StripeClient.paymentIntents.create()
    // Implement capturePayment -> paymentIntents.confirm()
    // etc.
}
```

Wire in `PaymentModuleConfig`:
```java
@Bean @Primary
public PaymentGateway paymentGateway() {
    return new StripePaymentGateway(stripeApiKey);
}
```

---

## Future Razorpay Integration

```java
@Component
public class RazorpayPaymentGateway implements PaymentGateway {
    // Implement createPayment -> razorpayClient.orders.create()
    // Implement capturePayment -> razorpayClient.payments.capture()
    // etc.
}
```

---

## Refund Strategy

- Only `SUCCESS` payments can be refunded
- Refund transitions payment to `REFUNDED` status
- Gateway is called for external refund; failure is logged but local state is updated
- Full audit trail stored
