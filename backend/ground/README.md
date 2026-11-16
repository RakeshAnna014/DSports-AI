# Ground module (`dsports-ground`)

Cricket ground booking (Phase 1 of [docs/plans/cricket-features.md](../../docs/plans/cricket-features.md)): owners list grounds, customers hold a slot, pay for it and cancel it. Decisions and trade-offs: [ADR-017](../../docs/adr/ADR-017-ground-booking.md).

| | |
|---|---|
| API | `/api/v1/grounds` (public, read-only), `/api/v1/bookings` and `/api/v1/me/bookings` (signed-in customer), `/api/v1/owner` (role `GROUND_OWNER`) |
| Controllers | GroundController, BookingController, OwnerGroundController |
| Tables | `grounds`, `ground_blackouts`, `ground_bookings`, sequence `ground_booking_seq` (V48); notification types widened in V49 (notification module) |
| Layout | flat, like `returns` and `support`: `domain` (no framework imports: model, policy, exception) / `application` (ports, use cases, results) / `infrastructure/persistence` (R2DBC adapters) / `interfaces` (controllers, requests); wired in `bootstrap/.../config/GroundModuleConfig` |
| Tests | domain and use-case unit tests with in-memory fakes; Testcontainers tests for the migration, the read path and holding under concurrency; the cross-module payment/expiry/cancel flow is `bootstrap/.../BookingPaymentFlowIntegrationTest` |

## How it works

* **Slots are computed, never stored.** A ground has opening hours as a wall-clock `LocalTime` pair plus a `ZoneId`, a slot length and prices. The slots of a day are the grid between opening and closing time, minus slots that overlap a HELD or CONFIRMED booking or a blackout, minus slots outside the booking window. Only bookings and blackouts are absolute instants.
* **Price per slot:** the peak price if the slot starts inside the peak window, else the weekend price on Saturday and Sunday (when set), else the base price. A booking is a run of 1 to 4 consecutive slots (configurable) and keeps a price snapshot.
* **No double booking, enforced by the database.** An exclusion constraint on `(ground_id, tstzrange(start_at, end_at, '[)'))` for HELD and CONFIRMED bookings makes overlap impossible whatever the application does. In front of it the use case takes per-customer and per-ground advisory locks (always customer first, then ground), because PostgreSQL may abort the loser of a true race with a deadlock error instead of an overlap error; both are reported as `409 SLOT_UNAVAILABLE`.
* **Lifecycle:** `HELD` (slot reserved while payment runs, default 10 minutes) → `CONFIRMED` (payment captured) → `COMPLETED` (24 hours after it ended, if the owner did not mark `NO_SHOW` first). A hold ends as `EXPIRED` (unpaid) or `CANCELLED`.
* **Idempotency:** `POST /bookings` needs an `Idempotency-Key`. The same key and request returns the original booking (200); the same key for a different request is `409 IDEMPOTENCY_KEY_REUSED`.
* **At most 2 unpaid holds per customer** (`app.ground.max-held-per-user`); a hold whose window has passed no longer counts.

## Paying

A booking is paid through the existing payments API with the **booking id as the payment's `orderId`** (a temporary shortcut, see ADR-017). The booking module answers two questions for payments, both under the booking's row lock:

* *payable*: the booking must be the caller's, HELD and not past its deadline; the charged amount is the booking's own price snapshot, never the request's.
* *confirm*: after the capture the booking is re-checked. If it is no longer HELD (it expired or was cancelled while the money moved) the payment is **refunded in full automatically**, the customer is notified and the capture call fails with `409 PAID_ITEM_NO_LONGER_AVAILABLE`.

The expiry job takes the same row lock and skips a booking whose payment is being captured (`PAYMENT_IN_PROGRESS`), so an expiry can never overtake a payment.

## Cancelling

Possible until the booking starts. The customer is always sent a notification.

| Who | Booking | Result |
|---|---|---|
| customer | HELD | slot released, unpaid payment attempts voided |
| customer | CONFIRMED | refund **request** for 100% (more than 24 h ahead), 50% (6 to 24 h) or 0%; paid out only after an administrator approves it |
| owner | HELD | slot released |
| owner | CONFIRMED | always 100%, **paid out automatically** by the outbox |

The thresholds are `app.ground.cancel.*`; the amounts are the booking price times the percentage, rounded to 2 decimals.

## Configuration (`app.ground.*`)

`min-lead` (PT1H), `max-ahead` (P30D), `max-slots` (4), `hold-duration` (PT10M), `max-held-per-user` (2), `expiry-check-interval` (15000 ms), `expiry-initial-delay`, `complete-after` (PT24H), `cancel.full-refund-before` (PT24H), `cancel.partial-refund-before` (PT6H), `cancel.partial-percent` (50).

## Security

* Browsing (`GET /api/v1/grounds/**`) is public. Everything else needs a signed-in user.
* `/api/v1/owner/**` requires the `GROUND_OWNER` role at the path level (`SecurityConfig`), and every owner use case also checks that the ground belongs to the caller. The role is granted by SQL, like `ADMIN`.
* The owner's booking list shows bookings, not customers: no customer identity is exposed.
* The caller id always comes from the verified token, never from a request.
