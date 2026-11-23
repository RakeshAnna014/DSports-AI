# Cricket build: progress

Read this, `DECISIONS-LOG.md` and `git log` first after any context reset. Never redo a finished gate. Rules: never push, never rewrite commits, new migrations only, no weakened tests.

Branch `feature/ground-booking`. Started this run at `3591c8a`.

## Gate list (ordered)

| Gate | What | State |
|---|---|---|
| 2f-0 | CI + shared engine vectors | done (reviewed) |
| 2f-1 | TypeScript scoring engine, replay of all vectors, TS-made vectors replayed by Java, property tests | **done** (d6166e9, 6181fd2) |
| 2f-2 | Durable IndexedDB queue, journal, pure reducer (no UI) | **done** (see report; to be re-verified in a clean worktree with 2f-3) |
| 2f-3 | Server additions: engine-state route, engineVersion + state fingerprint, `mine`/`clientEventId` on events, `tappedAt` (migration V57) | **done** (d3c25ef, cc7d122, 938c8b0) |
| 2f-4 | Sender, local view, scorer screen states, conflict / set-aside sheets, emulated e2e, real-phone script 7c | **done** (a3e96df, 7fda349, 1b6e48e, 854209d + docs/7c commit) |
| 3a | Match analytics and commentary from the log (public route `GET /api/v1/matches/{id}/analytics`), charts on the public scorecard (SSE not built: see DECISIONS-LOG) | **done** (f60fa55; frontend verified in a clean worktree 2026-11-23, backend verified with 3b) |
| 3b | Career stats projection `player_match_stats` (V58, rebuildable), `GET /api/v1/players/{id}/stats` public, admin rebuild | **done** (e2ad4e4, 7be7e29) |
| 3c | Career stats screen (player page), linked from the public scorecard (adds `playerId` to the public scorecard) | todo |
| 3d | Guest players linked to a real account by the organiser (owner decision) | todo |
| 3e | Optional shot direction after a scoring ball (off by default) and the wagon wheel (owner decision; migration V59) | todo |
| 4a | Tournaments (no fees): aggregate, teams, groups, fixtures, migration | todo |
| 4b | Points table with NRR, leaderboards, awards | todo |
| 4c | Tournament screens (organiser console, public page); fee extension point written in the ADR, no payments | todo |
| 4d | Scorer invite link: single use, 48 h, revocable, sign-in required (owner decision) | todo |
| 5a | AI tools: match summary + report with grounding check | todo |
| 5b | AI tools: player stats, head to head, team insights, ground finder | todo |
| 5c | `book_ground` chat write (proposal + confirmation) | todo |
| 5d | "Looking" and feed, simplest version behind a setting that is off by default | todo |
| final | Fresh-clone full suite x3, whole-branch review, FINAL-REPORT.md | todo |

Gate acceptance criteria and edge-case lists are written under each gate's heading below before coding starts.

---

## Gate 2f-1: TypeScript scoring engine

**Acceptance criteria**
1. `frontend/src/features/cricket/engine/` holds a pure engine (no React, no network, no `Date.now`, no randomness except an injected seeded generator in test code): `ENGINE_VERSION` = 1, same rules, commands, refusals, fingerprints as Java.
2. Every step of every golden and random vector replays to exactly the recorded answer (result, violation, seq, voids, waivers, fp, status, next, end, resultType, and the whole state where recorded). No vector is edited to make TS pass.
3. A seeded TS generator writes `testdata/cricket-engine/from-ts/seed-NNNN.json` with the TS engine's answers; a Java test replays them through the Java engine and fails on any difference. A TS test checks `from-ts` is current (regenerate and compare bytes).
4. Property tests: undo restores the exact previous fingerprint; fold from scratch equals step by step; refused commands never change state; invariants after every accepted command.
5. The existing scorer screen is untouched.

**Edge cases:** every dismissal type and end; wide/no-ball with runs, byes, leg byes; free hit; last ball of over/innings/match; target reached mid-over; all out with fewer players; last man stands; 2-a-side; undo across over and innings boundary; undo of a wicket that forced a new batter; repeated undo to the start; refusals at every wrong moment; waivers; retired hurt batter returning; mid-over bowler change; abandoned match.

**Design:** state objects have exactly the JSON shape the Java codec writes (so the state *is* its own fingerprint input), immutable updates by spread, ids are strings, nulls are `null`. One module per Java class group: `types`, `rules` (match rules, dismissal table), `innings` (the 650-line engine), `match` (MatchScoring + MatchScore + result), `log` (effective, planUndo), `wire` (the request body parser, mirrors `CommandBody.toCommand`), `session` (log + undo runner used by tests and later by the queue), `rng` (seeded generator, test/tool use only).

**Report (done).** Commits `d6166e9` (engine, vectors, tests) and `6181fd2` (Java replay of the TypeScript vectors, docs). Built and checked in a clean worktree with a real `pnpm install`: `tsc -b` clean, lint clean, vitest 48 files / 724 tests (441 before, 283 new), `vite build` ok; backend `mvn test` 1,186 tests, 0 failures.
* **Engine files** (`frontend/src/features/cricket/engine/`): `types`, `rules` (match rules, dismissal table), `innings` (port of `ScoringEngine`), `match` (port of `MatchScoring`, status, result, next action), `log` (effective log, `planUndo`, step runner), `wire` (request body parser/writer), `vectorExpect`, `tsVectors` (seeded generator, mulberry32), `index` (public face).
* **Replay:** TypeScript replays 6 golden + 120 Java random files (about 4,860 steps) with **zero** differences on the first run, no vector edited. Java replays 100 TypeScript-made files (3,219 steps, 76% accepted, every command, dismissal and end reason accepted at least once) with zero differences.
* **Hard to match:** nothing needed a second attempt. The risks I watched were Java's `computeIfPresent` on a null id (lone batter), `LinkedHashMap` insertion order (a JS `Map` keeps it), integer division (`Math.trunc`), and that a wide's runs type is dropped (the wire parser normalises it as the Java `Delivery` constructor does).
* **Tests added:** 138 vector replays, 5 generator/from-ts, 9 properties (invariants over every accepted step, refusal returns the same log, earlier states never edited, undo restores the fingerprint for 2,500+ commands and 1,500+ balls, undo to the empty match, replay equals step by step), 27 rule scenarios, 13 purity checks; Java `TsVectorsReplayTest` (2).
* **Known limits:** no super over or DLS (not in Java either). CI has still not run on GitHub (never pushed).
* **Edge cases covered:** all listed in the gate's list; "caught/hit-wicket combination" is covered only as separate dismissal kinds (the engine has no combined dismissal).

---

## Gate 2f-2: durable queue, journal and reducer (no UI)

**Acceptance criteria**
1. `frontend/src/features/cricket/offline/` holds a pure **reducer** (`queueReducer`) over a `QueueState` and a **storage interface** with two adapters: an in-memory one (tests) and a hand-written **IndexedDB** one (`dsports-scoring` database; stores `queue`, `journal`, `engineState`, `meta`; no library).
2. A ball is accepted only after it is committed: `enqueue` returns the new state AND the storage mutations; the caller commits them in **one transaction** and only then shows the ball. A failed commit leaves the state unchanged and reports `STORAGE_FAILED`; nothing is half saved.
3. Cap 600 items per match (D4): the 601st tap is **refused** (`QUEUE_FULL`), never dropped; warning level at 150.
4. Each item gets `clientEventId` (injected id function, works over plain http: no `crypto.randomUUID`/`subtle`) and `expectedSeq = confirmedSeq + position`, fixed at enqueue (D5/D6). Undo of a queued ball appends an UNDO item whose voids come from the local `planUndo` (D6).
5. The queue predicts with the 2f-1 engine: enqueue validates the command against the local log (confirmed + queued items). A command the local engine refuses is **not** queued (the scorer is told the rule), so the queue never contains something the engine already knows is illegal.
6. Journal (append-only): `enqueued, sending, confirmed, replayed, conflict, rejected, parked, rebased, discarded` with caller-supplied time. **Conservation:** `enqueued = confirmed + parked + discarded + waiting` after every step of a random sequence of tap / send ok / network failure / lost answer / conflict / rejection / lock / reload / discard; order preserved; ids unique; expectedSeq contiguous; an item leaves the queue only by a 200 or a confirmed discard.
7. Reload: `loadQueue(storage, matchId, userId)` rebuilds exactly the state (including in-flight items, which are retried with the same id). A corrupted row is **parked with a reason**, never dropped (a log that no longer folds parks the queue). Queue belongs to a user id (D15): another user sees it as set aside and cannot send it.
8. Self-check: `selfCheck(state, serverView)` compares the engine fingerprint at the server's seq with the server's `stateFingerprint` and says `ENGINE_VERSION_UNSUPPORTED` / `FINGERPRINT_MISMATCH` → offline scoring off; **every queued item stays** (sendable, reviewable).
9. Export: `exportText(state)` gives readable text + JSON of unsent items ("Copy unsent balls").
10. Tests: reducer unit tests; conservation property test; storage failure, cap, quota, corrupted row; the **storage contract suite** runs against memory in vitest and against real IndexedDB in Chrome through the emulated e2e (no `fake-indexeddb`, D12).

**Edge cases:** 1 / 10 / 600 / 601 items; reload with in-flight item; same item acknowledged twice; out-of-order acknowledgement (rejected as a bug, parked); network failure mid-flush; server already has some items (replayed); conflict then rebase keeps ids; discard needs the exact item; two users on one phone; IndexedDB missing / blocked / full / throws on open; clock skew (time is data, never read); non-ASCII labels (names are labels only, never in the fingerprint).

**Design:** item statuses `WAITING | IN_FLIGHT | SET_ASIDE(reason)`; `CONFIRMED`/`DISCARDED` leave the queue store and live only in the journal (conservation counts them from the journal). Events (all pure): `tap(command|undo, now, newId)`, `sendStarted`, `sendSucceeded(seq, replayed)`, `sendFailedNetwork`, `conflict(view)`, `rejected(violation)`, `locked(until)`, `forbidden`, `rebase(accept)`, `setAside(reason)`, `sendAgain`, `discard(index)`, `serverState(view)`. Each returns `{ state, mutations[] }`; the shell (2f-4 sender) applies mutations atomically via `storage.commit`. This keeps the logic testable without a browser.

**Report (done).** Code in `frontend/src/features/cricket/offline/`, README there. Tests: 32 + 7 (queue unit 29, conservation 1, contract 2, purity/labels 7). Real Chrome: `node e2e/idb-contract.mjs` meets the storage contract against real IndexedDB over plain http (page was not a secure context).
* **Bugs the tests found in my own first version (all fixed with tests):** (1) `expectedSeq` is the *last seq seen*, so an item's `expectedSeq` is its own seq minus one (the fake server, which copies the real server's check, refused every first item); (2) after a reload the self-check compared the saved fingerprint with the log *plus* the confirmed tail, so it turned offline scoring off by itself; (3) a sync while a conflict was open silently unblocked the chain and left items that no longer applied (now it stays blocked, and a chain that does not fit the server's log is itself a conflict); (4) the next item number came from row contents, so an unreadable row could have been overwritten by a new item (now from storage keys).
* **Property run:** 30 seeded sequences x 120 operations (3,600 steps, about 3.4 s) of tap, undo, send with network failure / lost answer, other scorer scoring, screen open, rebase / keep theirs, lock, scorer removed, send again, discard, reload. After every step: conservation, journal counts, storage mirrors state, chain contiguous, unique ordered ids, no id twice on the server, nothing lost; deep fold check every sixth step. The same sequences at 120 x 140 (16,800 steps) also passed before they were shrunk for speed.
* **Known limits / left for 2f-4:** the sender loop (backoff, 401 refresh, `online` event, lease check), the screen states, and the fallback to online one-at-a-time scoring when offline scoring is off (the queue only refuses taps with `OFFLINE_SCORING_OFF`). The conflict/`REJECTED`/`LOCKED` block is not persisted: after a reload the first send meets the same answer again. The IndexedDB contract is not in the GitHub workflow (no browser step there).


---

## Gate 2f-3: server additions (V57)

**Report (done).** Commits `d3c25ef` (backend main, migration, unit tests, vector samples), `cc7d122` (HTTP and persistence integration tests, security rows), `938c8b0` (frontend parser, types, docs).
* **Built:** `GET .../scoring/engine-state`; `engineVersion` + `stateFingerprint` on `ScoringView`; `mine` / `clientEventId` / `tappedAt` on events (also in a 409 conflict answer); `tappedAt` request field; V57 `ADD COLUMN tapped_at` + CHECK `tapped_at <= created_at`; `StateFingerprint` (reflection, cached); `CommandWire` (the one writer of a command body, shared with the vectors); `EngineState`.
* **Proofs:** the server's fingerprint equals the recorded one at all 5,000+ accepted steps of golden, Java-made and TypeScript-made vectors; the Java-written sample route answers (`testdata/cricket-engine/server/`) are read by the TypeScript parser, folded by the TypeScript engine and give the same fingerprint (own-events and foreign-events samples, the second with undos); view fingerprints equal the golden file's at every step through the real use case.
* **Integration (Docker):** 60 queued items in order (log, snapshot = fold, fingerprints agree); two tabs sending the same batch at once (every item applied by exactly one tab, replayed to the other, same seq to both); 20 lost answers then resend from the start; second scorer midway then rebase with same ids; deadline mid-flush then admin unlock then the original ids go in; scorer removed mid-flush; engine-state access matrix; `tappedAt` clamped / dropped / bad value is a 4xx. V57: round trip, database refuses a later tapped time, insert with and without, UPDATE / DELETE / TRUNCATE still refused, upgrade of a database that already holds V56 events.
* **Found by self-review and fixed with tests:** an absurdly old `tappedAt` (year -999999999) parsed fine but overflowed PostgreSQL's timestamp range (a 500): now dropped when older than 30 days before receipt; `StateFingerprint` reflection made the fingerprint test 5 s: cached per class (1 s).
* **Not done / for later:** the rate limiter is deliberately not extended to scoring routes (only auth and tickets are limited today), so a 300-item flush is not limited; no separate test was added for that beyond the 60-item batches. The local dev backend on :8080 was restarted on the new jar (the local `dsports` database is now at V57, forward only).
* **Verification:** see the 2f-3 clean-worktree line in the final section of this file once it has run.

---

## Gate 2f-4: sender, local view, screen states, e2e, real-phone script

**Finding that shapes this gate:** the scorer pad takes every choice from the server's `ScoringView` (what is needed next, allowed dismissals, eligible batters and bowlers, the innings tables, what an undo would do). Offline the server cannot answer, so the phone needs the same view built locally from its own log. So 2f-4 starts with a **local view builder** (a TypeScript port of `ScoringViewBuilder`), proved equal to the Java view on the golden match.

**Acceptance criteria**
1. `sender.ts`: sends the chain one at a time, in order, ~5/s; backoff 2, 4, 8 … 30 s; wakes on the `online` event and on the tab becoming visible; `retryNow`; classifies every answer exactly as section 3.2 of the plan (network/5xx, 401 refresh once then sign-in, 403/404, 400/`CLIENT_EVENT_REUSED`, 409 conflict, 409 locked, 422, 429 with `Retry-After`, 200/replayed); only the tab-lease holder sends; checked with a fake API and fake clock.
2. `localView.ts`: builds a `ScoringView` from the phone's log and a name map (kept from the last server view); on the golden match (and the undo match) it equals the Java view after every step (a Java-written sample of views is committed beside the engine-state samples).
3. The scorer screen shows the section 7.3 states (online, offline, waiting n, sending i of n, retrying, needs sign-in, conflict, rejected, locked, set aside n, offline scoring off, read only) with the pad off exactly where D14 says; unconfirmed balls dashed; "Keep this page open while scoring offline."; warning at 150; full at 600; "Copy / Download unsent balls".
4. Conflict sheet: the other scorer's balls (from `eventsSince`/engine-state), a preview (`previewRebase`), the two choices + "not now"; set-aside list with reasons, Send again, Copy, Download, Discard (named, confirmed) and the admin-unlock instruction (D8).
5. Fallback: if IndexedDB is unavailable or the self-check fails, the screen behaves exactly as today (one change at a time, online) with a banner; nothing queued is dropped.
6. The existing scorer screen tests keep passing unchanged where the behaviour is unchanged.
7. `e2e/cricket-offline.mjs` (emulated phone: Chrome context offline, 40 balls incl. wicket, wide, no-ball + free hit, over change, undo; close and reopen the tab; reconnect; flush in order; laptop context shows the final score; DB row count exact) with the local-database guard; conflict scenario (second API client scores 2 balls); lock scenario.
8. `docs/SETUP-AND-DEMO.md` gets script 7c (real phone, airplane mode) with the exact balls and the expected totals, and an automated test checks the scripted numbers.

**Edge cases:** reload while sending; killed tab; token expiry while offline; two tabs; IndexedDB full; engine version mismatch after a deploy; 320 px phone; very long and non-ASCII names; slow 3G; every server error status (403/404/409/422/429/5xx).

**Report (done).** Commits `a3e96df` (sender), `7fda349` (local view + Java view samples), `1b6e48e` (offline screen), `854209d` (emulated e2e), and the docs / 7c commit.
* **Built:** `sender.ts` (drain loop: one at a time, in order, ~5/s, lease checked before each send, every answer one queue step), `sendErrors.ts`, `httpSendApi.ts`, `engineStateApi.ts`, `localView.ts` (port of `ScoringViewBuilder` + `Scorecard`), `useOfflineScorer.ts`, `UnsentSheets.tsx`, `OfflineScorerPage.tsx`, `LegacyScorerPage.tsx` (the previous screen, one line changed: it takes the tab lease from its parent) and a thin `ScorerPage` gate (offline screen when IndexedDB + ready + self-check, otherwise the previous screen; balls waiting always get the offline screen).
* **Proofs:** local view equals the Java view after each of the 36 golden commands (innings tables, choices, needs, undo wording, result, fingerprint); 21 sender tests incl. 25 chaos seeds; 16 screen tests (every state of plan 7.3, the pad off exactly where D14 says, names in any script and very long names); the 7c numbers are checked against the doc by a test; **emulated browser run `e2e/cricket-offline.mjs` passes against the real backend and database** (13 offline balls, killed tab, flush once each in order with no gap and no duplicate id, laptop and phone agree; conflict with another scorer then rebase; window closing then admin unlock); `cricket-ui.mjs` (updated for the new banner) also passes.
* **Found while building and fixed:** the names were missing after a reload offline (the server's pre-innings view had overwritten the setup's names: now merged); the conflict sheet listed none of the other scorer's balls when the other scorer was the same account on another device (now theirs = not in this phone's own queue); an administrator's unlock caused a "someone else scored" decision about an event that cannot change the score (now ignored for conflicts; tested); after picking a batter the chooser is replaced at once by the bowler chooser (a script assumption, not a screen bug).
* **Known limits:** real-phone script 7c is for the user (see the final report); CI does not run the browser scripts (no browser step in the workflow); the screen's `REJECTED` / `CONFLICT` choice is not persisted across a reload (the first send meets it again); a phone that has never opened the match online cannot score it offline (the first open needs the server's log); no service worker, so a closed browser cannot reopen the page with no network (D10).

---

## Gate 3a: match analytics and commentary (public)

**Acceptance criteria**
1. `MatchAnalytics.of(setup, log, playerName, teamName)` is a pure domain function over the commands in force: runs and wickets in each over (Manhattan), the running total after each over (worm, run rate), the partnership for each wicket (and the unbroken last one) and one commentary line per ball; undo changes the answer because the log is replayed; no id leaves it (names and numbers only).
2. `GET /api/v1/matches/{id}/analytics` is public (path only, like the scorecard), read-only, 404 for a match that has not started (the same answer as for one that does not exist), ETag `"<seq>-<digest>"` with 304; every other path under it stays behind a sign-in; `SecurityPolicyTest` pins it.
3. The public scorecard page shows commentary, runs per over, the worm and the partnerships (plain SVG, each chart also described in words for screen readers), asking again only when the scorecard's seq changes.
4. Checked on the hand-scored golden match: 11 + 11 runs in the two overs, 2 wickets in over 1, partnerships 11 (4 balls), 0 (1), unbroken 11 (7), 14 commentary lines for the first innings, the two wicket lines with the score.

**Edge cases:** nothing scored; innings just started (no over yet); an over in progress (partial bar, partial point); wides and no-balls (they belong to the over being bowled, not counted as balls); maiden only when six legal balls and no runs; retired-out batter closes a partnership; an undo that reopens an innings; the other-innings partnership split; names in any script; 30+ commentary lines (newest first, show all).

**Design:** one function in the domain (no Spring, no clock), one use-case method that only reads the log, one controller method. The charts are SVG, not a library (no new dependency). The wagon wheel is **not** built: it needs where each shot went and the scorer does not record that (recorded in DECISIONS-LOG, not estimated).

**Report.** Domain `MatchAnalyticsTest` (7), `PublicMatchesUseCaseTest` (+3, 19 in all), `SecurityPolicyTest` (114 rows, +6 for the route and its neighbours), frontend `AnalyticsPanel.test.tsx` (10) and the existing public page tests (26 pass); HTTP integration test added to `CricketScoringHttpIntegrationTest` (public 404 before the start, 200 after, names only, ETag and 304, the tag moves with a ball, no other path opened).

---

## Remaining gates: acceptance criteria and edge cases (written before coding; none of these is built yet)

Each follows the gate loop: design note, tests first, small commits, clean-worktree verification (frontend, backend unit, Docker integration, the browser script that applies), adversarial self-review, report. Existing rules apply to every one of them: append-only log, server is the authority, public data is names and numbers only, new migrations only (next free number V58), every new route in `SecurityPolicyTest`.

### 3b Career stats projection
**Acceptance:** `player_stats` (V58) is a **rebuildable projection** of the ball log, never a source: per player and ball type (tennis / leather kept apart, recommended in the plan): matches, innings, runs, balls faced, not-outs, highest score, 50s and 100s, fours, sixes, balls bowled, runs conceded, wickets, best bowling, maidens, catches, stumpings, run-outs. Computed by a pure domain function over the same replay as `MatchAnalytics`, for matches that have finished (completed, or abandoned after a ball: decision to record). Updated when a match finishes and by a rebuild job that recomputes every player from the logs and gives the same numbers (a test proves incremental equals rebuild). `GET /api/v1/players/{id}/stats` (public: names and numbers, nothing for a player with no finished match). An undo after finishing (inside the 24 hours) or an admin unlock recomputes that match's contribution. **Edge cases:** guest vs claimed player (stats follow the player id, a claim keeps them); the same player in both teams of one match (refused earlier); retired hurt (not out); run out of the non-striker (balls faced not charged to him); a wicket on a no-ball (none); wides and no-balls not counted as balls faced or bowled; byes and leg byes not charged to the bowler; a 2-player side; two matches finishing at once (the rebuild is idempotent); a deleted team.

### 3c Stats screens
**Acceptance:** a player page (career table, per ball type), linked from the public scorecard; mobile layout at 320 px; the figures equal the API's; empty state for a player with no finished match. **Edge cases:** very long and non-ASCII names; a player with only bowling; a player who never batted.

### 4a Tournaments: aggregate, teams, groups, fixtures
**Acceptance:** `Tournament` (name, organiser, city, dates, format league / knockout / groups then knockout, points rules: win 2, tie or no result 1 by default as configuration, entry fee in rupees as `NUMERIC(12,2)` like the booking module) with teams, groups and fixtures; each fixture is an ordinary `Match` with a `tournament_id`; only the organiser edits; a team can be in a tournament once; fixtures generated for a league (round robin, each pair once, deterministic) and for a knockout (seeded, byes for odd counts); migrations V58 onwards, forward only, with the same append-only care as the log (a fixture that has a log is never deleted). **Edge cases:** 2 teams, 3 teams (byes), 16 teams; a team withdrawing after fixtures exist; a match abandoned (counts as no result); a fixture changed after it started (refused); two organisers editing at once (version check).

### 4b Points table, net run rate, leaderboards, awards
**Acceptance:** points table (played, won, lost, tied, no result, points, NRR) as a **pure function** over finished fixtures; NRR = runs scored / overs faced - runs conceded / overs bowled, with a side bowled out counted as having faced its full quota (as in the plan); ties broken by points, then wins, then NRR, then head-to-head (written down); leaderboards (most runs, most wickets, best strike rate and economy with a minimum-balls qualifier, most sixes) from the stats projection; awards (player of the match by a points formula recorded in the ADR). **Edge cases:** NRR with an abandoned match, a rain-shortened chase (revised target), 0 balls faced, a team that has not played, equal NRR; the table recomputed after an undo.

### 4c Entry fees and tournament screens
**Acceptance:** an entry fee is paid through the existing payment module with the mock gateway (the doc says so on the page), idempotent, refundable by the organiser before the first fixture; the organiser console (create, add teams, generate fixtures), a public tournament page (table, fixtures, leaderboards). **Edge cases:** paying twice, paying after the tournament starts, a refund of a partial payment, the organiser removing a team that has paid.

### 5a AI: match report and match summary tool
**Acceptance:** Java gives the facts (top scorers, turning-point over, partnerships) from `MatchAnalytics` and the stats; the AI service tool `get_match_summary` reads them over the public REST route; the model may only re-word them and every number in its text must be in the facts (the existing grounding check); a report is labelled as written by an AI. **Edge cases:** a match with no facts, a tie, an abandoned match, the model inventing a number (rejected), prompt injection in a player name (treated as data).

### 5b AI: stats and insight tools, ground finder
**Acceptance:** read-only tools `get_player_stats`, `get_head_to_head`, `get_team_insights`, `search_grounds`, `get_ground_slots` over the Java REST API with the caller's token; role-gated; the tool matrix and route sweep classify each. **Edge cases:** a player the caller may not see, an unknown id, a very large result (capped), the model asking for a route it has no tool for.

### 5c AI: `book_ground`, Looking, feed
**Acceptance:** `book_ground` is a confirmed WRITE (proposal then a confirmation token, exactly like the return and reorder writes); "Looking" (find players, umpires, opponents) and the feed are **product decisions** the plan leaves open (the open questions are unanswered): they are listed in FINAL-REPORT for the owner and not built.
