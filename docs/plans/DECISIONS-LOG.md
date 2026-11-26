# Decisions log (autonomous run)

Each entry: decision, reason, alternatives. Where a plan already recommended something, the plan wins and is only referenced.

## 2026-11-23 · Treating the pasted "finish everything" brief as the request
The message that started this run was only pasted text, so it is acted on as the user's request. Hard stops in it (push, history rewrite, real-data deletion, production config) are respected: nothing is pushed.

## 2026-11-23 · State objects use the Java JSON shape
Alternative: a different TS-friendly shape plus a projection function. Rejected: a second shape is a second thing to keep equal; using the codec's shape makes the vector's `state` directly comparable and the fingerprint input obvious.

## 2026-11-23 · 2f-1: scope limits of the engine port
* **Super over, DLS:** not in the Java engine, so not in the TypeScript one (a tie is a result type; a revised target is a manual number, as ADR-019 says). Alternative: add a super over to both engines now. Rejected: it changes the rules and the vectors for a feature nobody asked for in 2f.
* **Request parsing:** `parseCommand` accepts only canonical lower-case UUIDs and whole numbers. The server also accepts unusual UUID spellings; the phone only ever sends the ids it was given, so being stricter loses nothing.
* **From-ts generator is not a port of Java's `RandomScorer`:** it leans towards commands the engine accepts so Java is asked to accept thousands of them, and keeps about a quarter junk. Alternative: a faithful port. Rejected: a faithful port would also share Java's bias towards refusals, which is the opposite of what the TypeScript to Java direction needs.
* **`logFrom` / `stepUndo` throw if a stored log no longer folds** (the Java `fold` does the same). 2f-2 must treat that as corrupted storage and park the queue, never drop it.
* **`frontend/tsconfig.tsbuildinfo` is tracked** and rewritten by every `tsc -b`. It is left out of this run's commits (restored with `git checkout`). Suggestion for the owner: `git rm --cached` it and add it to `.gitignore`; not done here because it is a repository-hygiene change nobody asked for.

## 2026-11-23 · 2f-2: queue design choices beyond the plan
* **Confirmed-tail store:** the plan said "after every confirmed answer the phone refreshes the stored state". Rewriting the whole log on every ball is quadratic over a 600-item flush, so a confirmed item appends one entry to a `confirmed` store and the full engine state is written only when the phone syncs with the server. Alternative: rewrite each time (simple, up to ~180 MB of writes in one flush). Rejected.
* **Storage returns the key with each row** (`StoredRow`), and `nextN` comes from keys, so an unreadable row can never be overwritten.
* **A chain that does not fit the server's log is a conflict**, even if nobody else's entries came in (the scorer decides; nothing is auto-dropped).
* **A tap while offline scoring is off is refused** (`OFFLINE_SCORING_OFF`); the screen (2f-4) falls back to one-ball-at-a-time online scoring. "Fix this ball" is therefore: set aside from that ball, then score it online.
* **Another account's balls** are shown as set aside in memory only (storage untouched), so they return to WAITING when their owner signs in again (D15).
* **The engine-state record is per match, not per user.** It holds the server's log of a match this phone was authorised to open; on a shared phone a second user's page load reads it only after the server has authorised them for that match. Alternative: key by user. Rejected: it would hide the first user's queue from the Review screen, which D15 wants shown as set aside.
* **The IndexedDB contract runs by hand / in e2e, not in the GitHub workflow**, because the workflow has no browser step. Alternative: add `playwright install chrome` to CI. Not done: it adds minutes and a dependency install to every push; revisit when 2f-4's browser e2e is added.

## 2026-11-23 · 2f-4: screen design choices
* **One route, two screens.** The previous scorer screen is kept whole as `LegacyScorerPage` and a gate chooses: offline screen when the phone can score offline, otherwise the previous screen. Alternative: edit the one screen to work both ways. Rejected: the previous screen has 90 tests of behaviour that must not change, and a browser without IndexedDB must behave exactly as before.
* **The tab lease is taken once, by the gate,** and passed down (two instances of the lease hook in one tab would contend).
* **An administrator's unlock never asks the scorer to decide.** It is an event with a seq but cannot change the score. Alternative (plan section 4 case A): treat it as a conflict. Changed because the real-stack run showed the scorer being asked about something they could do nothing with.
* **"Theirs" in the conflict sheet is whatever is not in this phone's own queue,** not "another account": the same account on another device is still somebody else's ball as far as this phone is concerned.
* **Names and the correction window are saved beside the engine state** (display only) so a page reloaded with no signal still shows names; they are merged, never replaced.
* **Browser scripts are not in CI** (no browser step in the workflow). Alternative: add a Chrome install step to the `frontend` job. Not done: minutes and a dependency install on every push, for tests that need the whole stack anyway.

## 2026-11-23 · Phase 3a: analytics and commentary
* **No server-sent events.** The plan's Phase 3 names an SSE stream; 2e shipped polling with an ETag (every 4 s, 304 when nothing changed) and the acceptance criterion ("viewers can follow a match live") is met by it. SSE would add a long-lived connection per viewer on WebFlux plus `LISTEN/NOTIFY` for several instances, for a gain of a few seconds. Alternative: build it now. Not done: it is a performance feature, not a requirement, and the rule is to keep behaviour unchanged unless asked. The route and the page do not preclude adding it later.
* **A third public route.** The analytics could have been folded into the scorecard response, but that would send every ball of commentary with every four-second poll. A separate route, asked for only when the seq changes, keeps the poll small. The public surface is now exactly three read-only routes, each pinned in `SecurityPolicyTest`.
* **No wagon wheel.** It needs the direction of each shot (`shot x/y` in the plan), which the scorer screen does not capture and the engine does not model. Inventing positions would be a fabricated number. It needs a scoring-screen change (a tap on a field diagram) and a column, so it is listed in FINAL-REPORT as a product decision, not built.
* **Charts as plain SVG,** not a library: four small charts, no new dependency, easy to describe in words for screen readers.

## 2026-11-23 · Product decisions given by the owner (they override the open questions in FINAL-REPORT)
* **Guest players:** allowed by name only (no verification). An organiser can link a guest to a real account later (stats follow the player id). Decided by the owner.
* **Inviting scorers:** single-use invite link, expires after 48 h, revocable by the organiser; the invited user must sign in; the routes go into SecurityPolicyTest. Decided by the owner.
* **PWA:** deferred; the mobile apps will cover offline start-up (D10 stands). Decided by the owner.
* **Shot direction / wagon wheel:** an optional tap after a scoring ball, off by default, never required; the wagon wheel is built from it when present. Decided by the owner.
* **Looking and feed:** the simplest version the plan describes, behind a setting that is off by default; what a fuller version needs is listed in the report. Decided by the owner.
* **Tournament fees:** tournaments are built without fees; a clear extension point for a fee model is described in the ADR. No payments are built. Decided by the owner. (This replaces the 4c criteria about entry fees.)

## 2026-11-23 · Gates 3b, 3c, 3d: career statistics
* **A projection with one row for each (match, player), not a running total.** The plan's single `player_stats` table of totals would have to be adjusted for every undo and correction. Rows per match are replaced in the same transaction as the event (so an undo that reopens a finished match removes them), the career is the sum of a player's rows computed in code (`CareerStats.total`), and the rebuild job simply rewrites every match's rows from its log. A test proves incremental equals rebuilt. Alternative: counters updated by deltas (cheaper to read, but a correction could drift them). Reads are one indexed query per player, a few hundred rows at most.
* **Only COMPLETED matches count.** An abandoned or no-result match is a partial card that is not a result; counting it would make a record depend on the weather. Alternative: count any match with a ball bowled. Changing it later is a rebuild.
* **Tennis and leather are never added** (the plan recommended it): one line for each ball type.
* **A player id appears on the public scorecard (batters and bowlers only)** so a name can link to the public career page. It opens nothing but that player's names-and-numbers statistics. Alternative: no link from the scorecard (would make the page unreachable by a viewer). The test that no id leaves the scorecard now allows exactly the match id and the players on the card.
* **Career page is public and a missing player and a player with no finished match are the same 404** (nothing can be learned about who exists).
* **3d: guest-to-account linking already exists** as claim then approval by the guest's creator (the owner's wording "an organiser can link a guest to a real account later" is met by it). A direct "link this email" action was not added: it would let an organiser probe which emails have accounts. The link keeps the same player id, so every squad and every statistic follows the account (test added). The organiser's approval is the control.

## 2026-11-23 · Gate 3e: shot direction and the wagon wheel
* **Its own table, not a column and not a log event.** The scorer taps after the ball has been sent, and `ball_events` cannot be edited. A new event type would have to be understood by both engines, the vectors and the offline queue. A separate append-only `ball_shots` (V59) leaves all of that alone. Alternative: delay sending each ball until the scorer has tapped (slows scoring; rejected). Alternative: a mutable column (breaks append-only; rejected).
* **No foreign key to `ball_events`.** With one, PostgreSQL refuses `TRUNCATE ball_events` with its own message before our guard fires, which changed the existing guard tests. An insert trigger checks that the ball exists. The guard tests were not edited.
* **Online only.** A ball queued offline has no server seq until it is sent. Offering the tap offline would need the queue to carry a shot against a seq it does not know. Not built; the offline screen does not show the option.
* **Off by default, never required, and Skip is always there.** The setting is per device (local storage; if blocked it is simply off). A tap outside the circle is pulled onto the rope, never refused.
* **Only bat-run balls** (not wides, byes, leg byes, or balls with no runs) can have a shot; the server enforces it (422) and also refuses a second shot (409) and an out-of-field point (400).
* **Not covered by an automated test:** the scorer-screen wiring (switch, picker after a ball, the API call) is covered at component level (picker, geometry, setting) but there is no full-page flow test or browser script for it. Listed in FINAL-REPORT.
