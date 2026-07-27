# PR #18 - Payment Management

## Architecture

```
presentation (PublicPaymentController)
    ↓
application (CreatePaymentUseCase, CapturePaymentUseCase, CancelPaymentUseCase, RefundPaymentUseCase, GetPaymentUseCase, PaymentHistoryUseCase)
    ↓
domain (Payment aggregate, PaymentStatus, PaymentMethod, PaymentProvider, value objects, domain events)
    ↓
infrastructure (MockPaymentGateway, PaymentR2dbcRepositoryAdapter, PaymentSpringEventPublisherAdapter)
```

Clean Architecture is strictly followed. The `bootstrap` module wires all dependencies via `PaymentModuleConfig`.

## Payment Lifecycle

```
CREATED → PENDING → PROCESSING → AUTHORIZED → SUCCESS → REFUNDED
                                    \-> FAILED
                        \-> FAILED
            \-> CANCELLED
```

### Status Descriptions

| Status | Description |
|--------|-------------|
| CREATED | Payment record created, not yet initiated |
| PENDING | Payment initiated with method/provider selected |
| PROCESSING | Payment being processed by gateway |
| AUTHORIZED | Payment authorized by gateway (funds held) |
| SUCCESS | Payment successfully captured |
| FAILED | Payment failed |
| CANCELLED | Payment cancelled before completion |
| REFUNDED | Successful payment refunded |

## Gateway Abstraction

`PaymentGateway` interface provides provider-independence:

```java
public interface PaymentGateway {
    Mono<GatewayResult> initiatePayment(InitiatePaymentRequest request);
    Mono<GatewayResult> capturePayment(String gatewayReference);
    Mono<GatewayResult> cancelPayment(String gatewayReference);
    Mono<GatewayResult> refundPayment(String gatewayReference, Money amount);
    Mono<GatewayStatus> getPaymentStatus(String gatewayReference);
}
```

### Mock Gateway

`MockPaymentGateway` supports configurable simulation modes via `payment.mock.mode`:

| Mode | Behavior |
|------|----------|
| `success` (default) | All operations succeed with mock transaction IDs |
| `failure` | `initiatePayment` returns failure |
| `timeout` | `initiatePayment` returns timeout failure |

Other operations (capture, cancel, refund) always succeed in mock mode.

## Business Rules

- User must be authenticated
- Order must exist and belong to the user
- Order must not already be paid
- Payment amount must equal order grand total
- Payment currency must match order currency
- One successful payment per order
- Unique payment reference generated per payment
- Payment audit history stored
- Refund allowed only for SUCCESS payments
- Cancelled payments cannot become successful

## Integration Points

### Order Integration (`OrderPaymentPort`)

- `getOrderData()` - Validates order ownership, returns grand total, currency, paid status
- `markOrderAsPaid()` - Transitions order from PENDING_PAYMENT to CONFIRMED after successful payment

### Event-Driven Integration (`PaymentEventListener`)

Listens for `PaymentSucceededEvent` and:
1. Marks order as paid (CONFIRMED)
2. Deducts inventory via `InventoryRepository.stockOut()`
3. Clears the user's cart via `CartRepository.clear()`

### Order Flow Update

`PlaceOrderUseCase` now marks orders as `PENDING_PAYMENT` after placing (instead of leaving them as `CREATED`).

## Files Created

### Backend
- `backend/payment/src/main/java/com/dsports/payment/interfaces/payment/dto/CapturePaymentRequest.java`
- `backend/payment/src/main/resources/db/migration/V23__add_processing_payment_status.sql`
- `backend/bootstrap/src/main/java/com/dsports/event/PaymentEventListener.java`
- `backend/payment/src/test/java/com/dsports/payment/domain/payment/model/PaymentTest.java`
- `backend/payment/src/test/java/com/dsports/payment/application/payment/usecase/CreatePaymentUseCaseTest.java`
- `backend/payment/src/test/java/com/dsports/payment/infrastructure/payment/gateway/MockPaymentGatewayTest.java`
- `backend/payment/src/test/java/com/dsports/payment/interfaces/payment/PublicPaymentControllerTest.java`
- `docs/pr18-summary.md`

## Files Modified

### Backend
- `backend/payment/src/main/java/com/dsports/payment/domain/payment/model/PaymentStatus.java` - Added PROCESSING
- `backend/payment/src/main/java/com/dsports/payment/domain/payment/model/Payment.java` - Added markProcessing(), updated authorize/success transitions
- `backend/payment/src/main/java/com/dsports/payment/application/payment/port/PaymentGateway.java` - Renamed createPayment -> initiatePayment
- `backend/payment/src/main/java/com/dsports/payment/infrastructure/payment/gateway/MockPaymentGateway.java` - Updated to initiatePayment
- `backend/payment/src/main/java/com/dsports/payment/application/payment/usecase/CreatePaymentUseCase.java` - Added PROCESSING step, updated gateway call
- `backend/payment/src/main/java/com/dsports/payment/application/payment/usecase/CapturePaymentUseCase.java` - Added gateway capture, error handling
- `backend/payment/src/main/java/com/dsports/payment/interfaces/payment/PublicPaymentController.java` - Added capture endpoint
- `backend/bootstrap/src/main/java/com/dsports/config/PaymentModuleConfig.java` - Implemented markOrderAsPaid(), added CapturePaymentUseCase
- `backend/bootstrap/src/main/java/com/dsports/config/OpenApiConfig.java` - Added Payments API group
- `backend/order/src/main/java/com/dsports/order/domain/order/model/Order.java` - Added markPendingPayment()
- `backend/order/src/main/java/com/dsports/order/application/order/usecase/PlaceOrderUseCase.java` - Orders now marked PENDING_PAYMENT

### Frontend
- `frontend/src/types/payments.ts` - Added PROCESSING to PaymentStatus
- `frontend/src/features/payments/PaymentHistoryPage.tsx` - Added PROCESSING status color

## Database Changes

### V23 - Add PROCESSING payment status

- Drops existing CHECK constraint on `payments.status`
- Adds new CHECK constraint including PROCESSING

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/payments` | Create and initiate a payment |
| POST | `/api/v1/payments/{paymentId}/capture` | Capture an authorized payment |
| GET | `/api/v1/payments/{paymentId}` | Get payment details |
| GET | `/api/v1/payments/history` | Get user's payment history |
| POST | `/api/v1/payments/{paymentId}/cancel` | Cancel a payment |
| POST | `/api/v1/payments/{paymentId}/refund` | Refund a successful payment |

## Frontend Summary

Frontend components already existed and were updated:
- `PaymentPage.tsx` - 3-step stepper: Order Summary → Payment Method → Confirm & Pay
- `PaymentSuccessPage.tsx` - Shows success with payment reference, links to order
- `PaymentFailurePage.tsx` - Shows failure reason with retry option
- `PaymentHistoryPage.tsx` - Table listing with status chips (PROCESSING added)

## Testing Summary

| Test File | Type | Tests |
|-----------|------|-------|
| `PaymentTest.java` | Domain | Creation, lifecycle, transitions, validation (15+ tests) |
| `CreatePaymentUseCaseTest.java` | Use Case | Success, already paid, amount mismatch, currency mismatch, gateway failure, gateway error, invalid method, invalid provider (9 tests) |
| `MockPaymentGatewayTest.java` | Infrastructure | Initiate success, failure, timeout, capture, cancel, refund, get status (7 tests) |
| `PublicPaymentControllerTest.java` | Controller | Create, get, history, cancel, refund, capture (6 tests) |

## Production Readiness

- Optimistic locking via `@Version` on PaymentEntity (returns HTTP 409 on conflict)
- Global exception handling with proper HTTP status codes
- SLF4J logging throughout
- OpenAPI/Swagger documentation for all payment endpoints
- Input validation on all request DTOs
- All reactive (no blocking calls)
- Flyway migration for schema changes
- Event-driven integration via Spring ApplicationEventPublisher
