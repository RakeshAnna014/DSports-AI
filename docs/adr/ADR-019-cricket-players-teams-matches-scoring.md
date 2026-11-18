# ADR-019: Cricket players, teams, matches and ball-by-ball scoring

Status: accepted; steps 2a (players and teams, V53), 2b (match setup, V54), 2c (scoring rules engine) and 2d (ball-by-ball API and scorer screen, V55) implemented, 2e and 2f planned. Plan: `docs/plans/cricket-features.md`. Builds on ADR-017 and ADR-018.

## Context

Phase 2 of the cricket plan: players (guests allowed), teams, match setup, scoring and a public scorecard. Where the plan file differed from the code is recorded in "Differences from the plan" below.

## Decisions (whole phase)

* **One module, `backend/cricket`** (`com.dsports.cricket`, layered like `ground` and `support`), not three: players, teams, matches and scoring reference each other too closely to be separate modules. The scoring rules engine will live in `domain.rules`, pure Java with no framework imports (enforced by a test).
* **Event log, state folded from events** (2d): an append-only `ball_events` table, a stored snapshot written in the same transaction, unique `(match_id, seq)` and `(match_id, client_event_id)`, a per-match advisory lock, `expectedSeq` conflicts as `409 SCORE_CONFLICT`. The server derives striker, bowler and over/ball; a client sends only the outcome.
* **No new role.** Ownership is checked in the use cases (creator, captain's account, assigned scorer), as in the ground module.
* **Scorecards are public by link, names only** (2e). Phone numbers are never in a public response.

## Step 2a: players and teams (V53)

* **A player exists without an account** (a guest, created by someone who vouches for them) and may be linked to a registered customer. A customer is linked to at most one player (unique index).
* **A phone number is a search hint, never proof.** `customers.phone` is unverified and there is no OTP, so linking by phone alone would let anyone take over another person's profile. Instead a **claim** is requested by the registered customer and **approved by the person who created the guest** (or an administrator); approving rejects the other pending claims on that guest, in one transaction. The phone search (`GET /players/claimable`) returns only a shortened name ("Arjun K.") and at most 5 results, so it cannot be used to look up names from numbers.
* **Phones are unique per creator, not globally** (`(created_by, phone)`): two captains can each add the same real person. This replaces the plan's "unique phone per player". Phone numbers are normalised (`+91` assumed for 10 digits) and only ever shown to whoever created the profile.
* **Two profiles cannot be merged yet**: a customer who already has a linked player cannot claim a guest (merging would mean rewriting match history once matches exist).
* **Visibility returns 404, not 403**, for strangers (players, teams, claims), so ids cannot be probed. A member whose account is linked can see a team but not change it (403); the creator and the captain's own account can change it.
* **Teams:** unique name per creator (any case), at most 30 players, at most one captain (a partial unique index in the database as well as in the domain), the captain cannot be removed before another is chosen, an archived team is frozen, every change is version-checked through the team row.
* A claimant's name and email are shown only to the person who must decide the claim, through a port (`CustomerDirectoryPort`) implemented in bootstrap; the cricket module does not depend on identity.

## Step 2b: match setup (V54)

* **A match** has two different teams, overs (1 to 100), a ball type (LEATHER or TENNIS) and rules stored as columns (balls per over, players per side, bowler overs limit, wide and no-ball penalties, free hit, last man stands), all defaulting to the standard values and validated against the match length. Umpires are an optional text, not a table.
* **Lifecycle:** this step covers `SCHEDULED` and `TOSS_DONE`. The CHECK already lists the whole lifecycle so it never has to be widened. `start` arrives in 2d with the first scoring event, because the openers only exist once scoring does. Setup (settings, toss, XIs) is locked once the match leaves those two states.
* **Who can do what:** the creator changes settings and scorers; the creator or an assigned scorer records the toss (correctable until the start) and the playing XIs; people with a team in the match (as creator, or through a linked player) can only look. Everyone else gets 404. Scorers are found by registered email (`404 USER_NOT_REGISTERED` otherwise); there are no invite links.
* **Teams:** a creator can only pick teams they can see (created by them, or with a linked player of theirs), so a match against someone else's team needs that team to be visible to the creator for now; invitations are a later feature. An archived team cannot play a new match.
* **Playing XI:** between 2 and the players per side, in batting order, all members of that team, nobody twice, nobody on both sides (primary key and unique batting order in the database too). Scorers who are not on a team get each side's squad in the match view (`squad`, scorers only) so they can pick from it.
* **Venue booking (optional):** must be the creator's own, `CONFIRMED`, for cricket, and not already hosting another match (unique index). The venue name and city are copied onto the match, and the start time too when none was given. The cricket module reads the booking through `BookingLookupPort` (implemented in bootstrap over the ground module's tables); it has no foreign key to it.

## Step 2c: the scoring rules engine

* **Pure functions in `domain.rules`.** `ScoringEngine.apply(state, command)` returns `Accepted(newState)` or `Rejected(violation, message)`; a rejection changes nothing. No framework, clock or randomness (a test reads the package's imports and source). The full design, the dismissal table and a hand-scored innings are in `docs/plans/cricket-2c-scoring-engine.md`; that innings is a golden test.
* **The client sends outcomes only.** Striker, non-striker, bowler and over are derived. The next batter or bowler is a separate command, required when the state says it is needed, and a ball is refused until it is given.
* **Replayable.** The state is a fold of accepted commands (`replay`), which is what the 2d event log will rely on; property tests check this with thousands of random commands.
* **Dismissals** follow the approved table (no separate "handled the ball"), with the free hit restricting it further. A run out names the batter and the end, which decides who is left where and so who faces next.
* **Strike:** one swap per run actually run, none for a boundary, and one at the end of an over. A lone last-man-stands batter never swaps.
* **Bowlers:** none twice in a row, and a limit of credited overs: unless set for the match, the larger of a fifth of the match and the match shared between the bowlers a side can field (players per side minus one), each rounded up, so an 11-a-side 20-over match allows 4 and a 4-a-side one allows 7. An over with a mid-over change counts for the bowler with most legal balls (a tie goes to whoever started it). The injured bowler may bowl again later; only the one who finished the over cannot bowl the next. If nobody is under the limit, the limit is waived as a last resort so the innings can finish (and, if only one bowler is left who bowled the previous over, that rule too); each waiver is returned on the command's outcome so it is stored on its event, kept in the innings state and listed on the scorecard.
* **Bat runs are at most 7 per ball; the wide and no-ball penalties are added on top.**
* **Retired hurt** is not a wicket and the batter may come back; **retired out** is a wicket and he cannot. Both are accepted between balls only.
* **Result** is computed from the two innings every time and never stored on its own, so an undo re-derives it. A revised target is the scorer's number (no DLS calculation).

## Step 2d: ball-by-ball scoring (V55)

* **The log is the truth.** `ball_events` is append-only (a trigger refuses UPDATE and DELETE): one row per accepted command, with a unique `(match_id, seq)` and a unique `(match_id, client_event_id)`. `match_snapshots` is the read model, written in the same transaction. Every request rebuilds the state by folding the effective log with the 2c engine; a test checks that the snapshot equals that fold after every step.
* **Two scorers cannot corrupt a match.** One transaction per request holds a per-match advisory lock. Inside it: a retry of a command already in the log is answered as done (idempotency comes first, so a lost response is never a false conflict); a request made from an old view (`expectedSeq` is not the latest) is `409 SCORE_CONFLICT` carrying the current score and what happened since; a match whose window has closed is `409 MATCH_LOCKED`; then the engine decides, and a refusal is `422 SCORING_REJECTED` with the rule's code. The two unique keys are the second line of defence if the lock were ever bypassed.
* **Undo is an event.** An `UNDO` voids earlier commands (it deletes nothing); the state is the fold of what is left, so overs, wickets, the free hit, the over credit and the result need no special case. `BALL` mode takes off the last ball and what was chosen after it (the new batter, the new bowler); `COMMAND` mode takes off one command. It never crosses the start of an innings, and the screen asks first when it would reopen an innings or a finished match. There is no redo.
* **The correction window is anchored to the first finish.** For 24 hours after the match first finishes (completed, abandoned or no result) commands and undo are allowed. Undoing and finishing again does **not** restart the clock (`first_finished_at` is never cleared). Only an administrator's unlock, with a reason, grants a fresh 24 hours from the unlock; it is an `ADMIN_UNLOCK` event in the log (who, when, why). A correction left half-done at the deadline is locked as it is, and an administrator can reopen it.
* **An optional fielder** (from the fielding XI) can be named for caught, stumped and run out, and a scorecard reads "c Ravi b Tarun", "st Ravi b Tarun", "run out (Ravi)". Shot positions are not recorded.
* **Waivers are on the record.** When every bowler left is at the over limit, the limit is set aside as a last resort; the waiver is stored on the event, kept in the innings state and listed on the scorecard.
* **The client holds no cricket rules.** The server's view says what is needed next, which dismissals the next ball allows and who may bat or bowl. A change goes through a durable one-slot outbox on the phone (saved with its client event id, retried, cleared only when the server answers), so a ball is neither lost nor counted twice. Many balls offline would need a copy of the engine on the phone, which is step 2f. The run-out end defaults from the runs completed (striker out: bowler's end after an even number of runs, striker's end after an odd number; the non-striker the other way) and the scorer can change it.
* **What the log protects against, and what it does not.** The row-level trigger refuses UPDATE and DELETE. The two foreign keys into `matches` have no cascade, so deleting a match (or a team a match uses) that has a log is refused by the database (no code path deletes matches, teams or players today). **`TRUNCATE ball_events` is not stopped** (PostgreSQL does not fire row-level triggers for it) and a database superuser can disable the trigger; both are outside what the application can do and are an open hardening item (a statement-level TRUNCATE guard would be a new migration). `match_snapshots` is a read model and can be rebuilt from the log, except the correction-window anchor (`first_finished_at`, `unlocked_until`), which lives only there.
* **Routes:** `GET /api/v1/matches/{id}/scoring`, `POST .../scoring/commands`, `POST .../scoring/undo`, and `POST /api/v1/admin/matches/{id}/scoring/unlock` (ADMIN). Errors carry an optional `details` object (a new field of the shared error body).

## Differences from the plan

| Plan | Code |
|---|---|
| migrations V49 to V53 | head was V52, so Phase 2 uses V53 onward |
| packages `.team`, `.match`, `.scoring` | one module `cricket` with layered packages |
| unique phone per player | unique per creator; claims need approval |
| PWA with an offline queue | no PWA support exists in the repo; a localStorage retry queue in 2d, installable PWA later |
| `match_officials`, shot x/y, `innings_snapshots` | umpires as optional names, no shot map, one snapshot row per match (2b/2d) |

## Consequences

* Claiming a profile needs the guest's creator to act; an unresponsive creator blocks it (an administrator can decide any claim).
* No penetration test or certification has been performed; the security tests are the project's own (`SecurityPolicyTest` classifies the routes; `CricketPlayersAndTeamsHttpIntegrationTest` covers visibility over real auth).
