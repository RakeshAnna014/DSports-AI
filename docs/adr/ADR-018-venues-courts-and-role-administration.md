# ADR-018: Multi-sport venues and courts, catalogue sports, and administrator-managed ground owners

Status: accepted (builds on [ADR-017](ADR-017-ground-booking.md); V50, V51 and V52)

## Context

Phase 1 booked cricket **grounds**: one place, one price list, one sport. Two things were missing. A venue usually has several playing areas that are booked independently and that more than one sport can use (a hall for badminton and table tennis, a turf for football and cricket). And the ground-owner role could only be given with SQL, which is not something an operator should do by hand.

## Decisions

### 1. Venue → Court → Booking
* A **venue** is the place: address, owner, opening hours (a `LocalTime` pair plus a `ZoneId`), photos, facilities.
* A **court** belongs to a venue and has its own name, surface, **sports it offers**, slot length and base / weekend / peak prices. A court's slot grid is the venue's opening hours split by the court's slot length, so courts of one venue can have different slot lengths.
* A **booking** is for one court and **one chosen sport**, with a price snapshot as before.
* Slots stay computed, never stored; bookings and blackouts are the only absolute instants.
* A blackout closes the whole venue (`court_id` null) or one court.

### 2. The overlap constraint moves to the court
`EXCLUDE USING gist (court_id WITH =, tstzrange(start_at, end_at, '[)') WITH &&) WHERE status IN ('HELD','CONFIRMED')`. One court can never be double booked **whatever the sport**; different courts of a venue are independent. Holding takes the per-customer advisory lock and then a **per-venue** lock (so a venue-wide blackout and a hold on any court cannot both win); the constraint remains the backstop, and a deadlock or serialization failure from a true race is still mapped to `409 SLOT_UNAVAILABLE`. Tests: two courts of one venue booked at the same moment both succeed; one court raced for two sports at the same moment gives exactly one winner (both with real concurrency on PostgreSQL).

### 3. Sports come from the catalogue, with a `bookable` flag
The names already exist in the shop (`sports`), so a second list would drift. V50 adds `sports.bookable` (default false), marks the court sports (Cricket, Football, Badminton, Tennis, Basketball, Volleyball, Table Tennis) and adds Pickleball, which the catalogue did not have. An administrator adds a sport and sets the flag in the Sports screen.
* **The shop must not show an empty sport.** The shop's sport list (`GET /api/catalog/sports`) leaves out a sport that is `bookable` **and** has no active product; a bookable sport with products, and every shop-only sport, is listed exactly as before. `GET /api/catalog/sports?bookable=true` lists what can be booked. Tested against the real application.
* A court stores sport ids as `UUID[]` **without a foreign key**: the ground module's migrations run on their own (no catalogue tables), and the application validates every id against the catalogue (only active, bookable sports can be added to a court, and a booking needs the court to offer the sport and the sport to be bookable at that moment). The ground module does not depend on the catalogue module; `SportCatalogPort` is implemented in bootstrap.

### 4. Migration V51 (forward-only, V48 and V49 untouched)
Creates `venues`, `courts`; each existing `grounds` row becomes a venue with the **same id** holding one court "Main" with the **same id** offering Cricket (when the catalogue has it); bookings and blackouts are re-pointed at them (`venue_id`, `court_id`, `sport_id`), the old exclusion constraint is dropped and replaced, and the old `grounds` table is left in place, unused, as a rollback aid for a later migration to retire. Tested on a database that already holds V48 rows and on an empty one.

### 5. When a venue's owner loses the role
* The venue **stops accepting new bookings** and is **hidden from search**; its page says so and every slot shows as unavailable. **Existing bookings stay valid** (they can still be paid, confirmed, cancelled and refunded).
* An administrator can **reassign** the venue to another user who holds the ground-owner role; it is bookable again at once. Reassigning to someone without the role is refused (`409 OWNER_NOT_ELIGIBLE`).
* "Owner holds the role" is read **live from the identity tables** (`OwnerEligibilityPort`), not from a token, so this takes effect immediately and cannot be undone by a stale token.
* **A removed role stays in the person's access token until it expires (up to 15 minutes).** Routes protected only by the token (the owner routes, `/api/v1/owner/**`) keep working for that long; only decisions made from live data react at once. The person can no longer obtain a token with the role. Accepted for now; shortening it would mean checking the role live on every owner route (a database read per request) or revoking tokens.

### 6. Administrators manage ground owners (no SQL)
`/api/v1/admin/ground-owners` (ADMIN only, enforced on the server): list owners, **grant by email** (the user must have registered, else `404 USER_NOT_REGISTERED` and the screen says "Ask them to register first"), **remove**, and read the **audit log**.
* An administrator **cannot grant the role to themselves** (`403 CANNOT_CHANGE_OWN_ROLE`).
* Only this one role can be changed through the screen (an allow-list of one), so it can never be used to hand out ADMIN.
* A request that changes nothing (granting what is already granted) records nothing; every real grant or removal writes a `role_audit_log` row (actor, target, target email at the time, role, action, time) **in the same transaction** as the change. The log has no foreign keys on purpose, so it outlives the people it describes.
* The **first administrator is still created once by SQL** (documented in SETUP-AND-DEMO).

### 7. Administrators can run venues on an owner's behalf
`/api/v1/admin/venues/**` is the same management API as `/api/v1/owner/venues/**` (one implementation, served on two paths; the path decides whether the caller is an administrator). An administrator can create or edit any venue, courts and blackouts, and reassign; an owner can only touch their own venues (checked in every use case as well as by the path rule). Edits are version-checked: a stale edit is a 409, not a silent overwrite.

### 8. API names
Renamed with no aliases (nothing outside this branch used them): `/api/v1/grounds` → `/api/v1/venues`, `/api/v1/owner/grounds` → `/api/v1/owner/venues`. Booking, payment and refund routes are unchanged, as is the payment module: the booking id is still stored in `payments.order_id` (the temporary shortcut of ADR-017).

## Alternatives considered
* **A separate booking-only sport list.** Rejected: it would drift from the shop's names. Cost of the chosen way: "add a sport" in the admin screen also adds it to the catalogue.
* **A join table `court_sports` instead of an array.** Cleaner relationally, but no foreign key is possible across modules anyway, and the array keeps a court's update atomic and its search a single indexed `= ANY` (GIN index).
* **A per-court advisory lock.** Allows more parallelism, but a venue-wide blackout would then need to lock every court; one venue lock is simple and cheap at this scale.
* **Reading the role from the token for "is the owner eligible".** Rejected: it would leave a removed owner's venue bookable until the token expired.
* **Cascading removal of an owner's venues or bookings.** Rejected: customers who paid must not lose their booking because of an administrative change.

## Consequences
* Search is sport-first (`GET /api/v1/venues?sport=&city=&date=`), with the city matched ignoring case and surrounding spaces.
* Owners manage courts and the sports of each court; the booking UI lists only bookable sports.
* The learning-guide documents still describe the Phase 1 ground model.
* No penetration test or certification has been performed; the security tests are the project's own (`SecurityPolicyTest` classifies every new route).
