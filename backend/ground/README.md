# Ground module (`dsports-ground`)

Multi-sport venue booking: owners (or administrators on their behalf) set up venues with courts and the sports each court offers; customers choose a sport, a city, a day, a court and a slot, hold it, pay for it and cancel it. Phase 1 plan: [docs/plans/cricket-features.md](../../docs/plans/cricket-features.md). Decisions and trade-offs: [ADR-017](../../docs/adr/ADR-017-ground-booking.md) (booking, payments, overlap) and [ADR-018](../../docs/adr/ADR-018-venues-courts-and-role-administration.md) (venues and courts, catalogue sports, owner administration).

| | |
|---|---|
| API | `/api/v1/venues` (public, read-only), `/api/v1/bookings` and `/api/v1/me/bookings` (signed-in customer), `/api/v1/owner/venues` and `/api/v1/owner/bookings` (role `GROUND_OWNER`), `/api/v1/admin/venues` (role `ADMIN`). Who is an owner is `/api/v1/admin/ground-owners` in the identity module. |
| Controllers | VenueController, BookingController, VenueManagementController (abstract; served by OwnerVenueController and AdminVenueController) |
| Tables | `venues`, `courts` (V51), `ground_blackouts`, `ground_bookings`, sequence `ground_booking_seq` (V48, reshaped by V51); the old `grounds` table is left in place, unused. Notification types widened in V49 (notification module). |
| Layout | flat, like `returns` and `support`: `domain` (no framework imports: model, policy, exception) / `application` (ports, use cases, results) / `infrastructure/persistence` (R2DBC adapters) / `interfaces` (controllers, requests); wired in `bootstrap/.../config/GroundModuleConfig` |
| Ports to other modules | `SportCatalogPort` (the catalogue's sports) and `OwnerEligibilityPort` (who holds the ground-owner role now) are implemented in bootstrap; this module depends on neither the catalogue nor identity |
| Tests | domain and use-case unit tests with in-memory fakes; Testcontainers tests for the migrations (including V51 on pre-existing V48 data), the read path and holding under concurrency; the cross-module payment / expiry / cancel / role-removal flow is `bootstrap/.../BookingPaymentFlowIntegrationTest`; the administration journey over real HTTP is `bootstrap/.../VenueAdministrationHttpIntegrationTest` |

## How it works

* **Venue → court → booking.** A venue has an owner, an address and opening hours (a wall-clock `LocalTime` pair plus a `ZoneId`). A court belongs to a venue and has a name, a surface, the sports it offers, a slot length and prices. A booking is for one court and one chosen sport.
* **Slots are computed, never stored.** A court's slots for a day are the venue's opening hours split by the court's slot length, minus slots that overlap a HELD or CONFIRMED booking of that court or a blackout (venue-wide or its own), minus slots outside the booking window. Only bookings and blackouts are absolute instants.
* **Price per slot:** the peak price if the slot starts inside the peak window, else the weekend price on Saturday and Sunday (when set), else the base price. A booking is a run of 1 to 4 consecutive slots (configurable) and keeps a price snapshot.
* **No double booking, enforced by the database.** An exclusion constraint on `(court_id, tstzrange(start_at, end_at, '[)'))` for HELD and CONFIRMED bookings makes overlap of one court impossible **whatever the sport**; different courts of a venue are independent. In front of it the use case takes a per-customer advisory lock and then a per-venue one (always in that order), because PostgreSQL may abort the loser of a true race with a deadlock error instead of an overlap error; both are reported as `409 SLOT_UNAVAILABLE`.
* **Sports** are catalogue sports. A court can offer only active sports the catalogue marks `bookable`; a booking needs the court to offer the sport (`422 SPORT_NOT_SUPPORTED`) and the sport to be bookable now (`422 SPORT_NOT_BOOKABLE`). Search takes the sport id and ignores the case of the city.
* **Lifecycle:** `HELD` (slot reserved while payment runs, default 10 minutes) → `CONFIRMED` (payment captured) → `COMPLETED` (24 hours after it ended, if the owner did not mark `NO_SHOW` first). A hold ends as `EXPIRED` (unpaid) or `CANCELLED`.
* **Idempotency:** `POST /bookings` needs an `Idempotency-Key`. The same key and request (court, sport and times) returns the original booking (200); the same key for a different request is `409 IDEMPOTENCY_KEY_REUSED`.
* **At most 2 unpaid holds per customer** (`app.ground.max-held-per-user`); a hold whose window has passed no longer counts.

## When the owner loses the role

The venue stops accepting **new** bookings (`409 VENUE_NOT_ACCEPTING_BOOKINGS`), disappears from search, and its slots show as unavailable; existing bookings stay valid. An administrator reassigns the venue to another user who holds the role (`PUT /api/v1/admin/venues/{id}` with `ownerId`) and it is bookable again. The check reads the role live from the identity tables, not from the token. A removed role stays in the person's access token until it expires (up to 15 minutes), so the owner routes keep answering for that long.

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

* Browsing (`GET /api/v1/venues/**`) is public. Everything else needs a signed-in user.
* `/api/v1/owner/**` requires the `GROUND_OWNER` role and `/api/v1/admin/**` the `ADMIN` role at the path level (`SecurityConfig`); every management use case also checks that the venue belongs to the caller (administrators may act on any venue). Handing a venue to another owner is an administrator action, and the new owner must hold the role.
* The owner's booking list shows bookings, not customers: no customer identity is exposed.
* The caller id always comes from the verified token, never from a request; whether a caller is acting as an administrator comes from the route, never from the body.
* `SecurityPolicyTest` classifies every route of this module.
