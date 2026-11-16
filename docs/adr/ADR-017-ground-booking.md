# ADR-017: Ground booking reuses payments and the outbox, and the database prevents double booking

Status: accepted (Phase 1 of the cricket features plan, `docs/plans/cricket-features.md`). Builds on the modular monolith and reactive stack described in `docs/architecture.md`; reuses the payment module, the transactional outbox and the `ExpireUnpaidOrdersUseCase` pattern.

## Context

Customers should be able to book a cricket ground for a time slot and pay for it. A booking looks like an order with a time slot instead of stock, so the existing payment, outbox, idempotency and expiry machinery should be reused rather than duplicated. The plan file was written from documentation; building against the code showed where it differed (money, payments, refunds, idempotency, packaging), and those differences are recorded here.

## Decision

1. **A new module `dsports-ground`** with the flat layout of `returns` and `support`, wired in `bootstrap/config/GroundModuleConfig`. It references other modules by port only (payments and outbox are connected in the composition root).
2. **Slots are computed.** Opening hours are a `LocalTime` pair plus a `ZoneId` on the ground; only bookings and blackouts are stored as UTC instants. Money is `NUMERIC(12,2)` rupees like the rest of DSports (the plan proposed paise `BIGINT`; the payment module compares `BigDecimal` rupee amounts).
3. **Double booking is prevented by the database.** `EXCLUDE USING gist (ground_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&) WHERE status IN ('HELD','CONFIRMED')` (the R2DBC driver has no range codec, so the range is built from two timestamp columns). On top, the hold takes a per-customer advisory lock and then a per-ground advisory lock, always in that order, because PostgreSQL can abort the loser of a true race with a deadlock error rather than an overlap error. The adapter maps both to `409 SLOT_UNAVAILABLE`.
4. **Idempotency copies the support-case pattern** (unique `(user_id, idempotency_key)`, the customer lock, same request returns the original, a different request is `409`). Orders dedupe on the checkout id, not on a header, so the plan's "same as orders" did not apply.
5. **Hold, pay, expire, cancel are ordered by one row lock.** Paying (at claim and at completion), expiring and cancelling all take `SELECT ... FOR UPDATE` on the booking first. The expiry job skips a booking whose payment is being captured; capture completion re-checks that the booking is still HELD and, if not, queues an automatic full refund in the same transaction and fails clearly (`409 PAID_ITEM_NO_LONGER_AVAILABLE`). A hold that lapses while its payment is already being captured is still honoured.
6. **Payment authorisation is checked server-side.** A booking is payable only for its own customer, only while HELD and not past its deadline, and is always charged its own price snapshot.
7. **Cancellation.** A customer cancel of a paid booking files a refund request for the policy share (100% over 24 h, 50% from 6 to 24 h, otherwise none) that an administrator approves, because refunds in this code base are two-step. An owner cancel is always 100% and is paid out automatically by the outbox, the same path as the late-capture refund. Both queue a cancellation notification.
8. **Roles and routes.** A new `GROUND_OWNER` role, granted by SQL like `ADMIN`. Owner routes live under `/api/v1/owner/**` so the path rule in `SecurityConfig` enforces the role (and `SecurityPolicyTest` covers it); use cases also check ownership. Browsing is public.

## Changes to the payment module (deliberate, small, order behaviour unchanged)

* `PaymentCompletionPort.complete(orderId, paymentId)` (default: `completeOrder(orderId)` then `true`) returns whether the paid item could still be completed. `CapturePaymentUseCase` calls it; when it returns `false` the capture is still recorded, no payment-success notification is queued and the call fails with the new `PaymentErrorCode.PAID_ITEM_NO_LONGER_AVAILABLE` (409).
* `CreatePaymentUseCase` charges the amount and currency of the order or booking record. The request still carries an amount, but it is only checked for equality (`AMOUNT_MISMATCH`); it never chooses what is charged.
* `OutboxMessageTypes.PROCESS_REFUND` (the outbox pays out a system-requested refund), `NOTIFY_BOOKING_CONFIRMED` and `NOTIFY_BOOKING_CANCELLED`. `NotificationType` gained `BOOKING_CONFIRMED` and `BOOKING_CANCELLED`; migration V49 widens the notification type constraint.
* Three `CapturePaymentUseCaseTest` tests changed only which port method they stub (the collaboration changed); their assertions are unchanged, the "never completed" checks now use the new method so they are not vacuous, and a test for the new outcome was added.

## Temporary shortcut: the booking id is stored in `payments.order_id`

Bookings are paid through the existing payments API with the booking id in the `orderId` field. This works because `payments.order_id` has no foreign key, the open-payment unique index is on that column, and the refund code only treats it as an opaque id. The cost: the name is misleading, error codes and messages say "order", and the bootstrap ports (`OrderPaymentPort`, `PaymentCompletionPort`) try an order first and fall back to a booking.

**Follow-up (not done here):** add a `payable_type` (and a payable id) to `payments`, make the ports select by type instead of by lookup, and rename the `ORDER_*` codes for non-order payables. Until then, a UUID that is both an order and a booking would resolve to the order (random UUIDs make this a theoretical concern).

## Alternatives considered

* **Store slots as rows:** rejected; millions of empty rows, and the constraint over ranges gives the same guarantee without them.
* **Application-only overlap check:** rejected; it cannot be correct under concurrency, and the database already supports the constraint.
* **A separate payment flow for bookings:** rejected for Phase 1; the payment module already handles authorise, capture, void, refund and retries safely. The shortcut above is the price.
* **Automatic refund for every cancellation:** rejected for customer cancels; refunds here are approved by an administrator. Owner cancels and failed captures are the system's fault, so those pay out automatically.
* **Per-ground cancellation policies:** deferred; thresholds are configuration (`app.ground.cancel.*`).

## Consequences

* The payment gateway is still the demonstration mock; real bookings need a real gateway.
* A HELD slot blocks others until the expiry job releases it (every 15 seconds by default), exactly as the database constraint does.
* Owner cancellation pays out money without a human approval; the customer cancel does not. This is a deliberate asymmetry.
* The bootstrap module now has Testcontainers-based cross-module integration tests (test scope only).
* Phase 1 has no AI tools; chat booking is Phase 5.
