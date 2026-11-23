# Cricket step 2f: offline scoring in TypeScript (plan only, no code yet)

Status: **approved with decisions (see the end).** Gates 2f-0 (CI and vectors) and 2f-1 (the TypeScript engine, both vector directions) are built; the rest is still plan. Progress is tracked in [PROGRESS.md](PROGRESS.md). It extends [ADR-019](../adr/ADR-019-cricket-players-teams-matches-scoring.md) and the 2d contract in [cricket-2d-scoring-api.md](cricket-2d-scoring-api.md). Section 9 lists the decisions that need an answer before any code is written.

## 0. What exists today, and what 2f changes

| Today (2d) | 2f |
|---|---|
| The server owns the engine. The phone sends one change at a time and waits for the answer, because only the Java engine knows what comes next (a new batter, a new bowler, a free hit). | The phone has the same engine in TypeScript, so it can work out the next state itself and keep taking balls with no signal. |
| One-slot outbox in `localStorage` (`scoringOutbox.ts`); a failed `localStorage` write is ignored. | A durable, ordered queue of many items in IndexedDB. A ball that cannot be saved is refused, never dropped. |
| Conflict sheet: "someone else scored", the scorer picks one change to retry or drop. | Same rule (the scorer decides, nothing is merged silently) but for a queue: the sheet shows the other scorer's balls and a preview of the scorer's own balls on top of them. |
| Server stays the authority. | **Unchanged.** The phone's engine only predicts. The server re-checks every queued item with the Java engine, one by one. A ball is "confirmed" only when the server has answered 200. |

Facts from the current code that the design relies on:

* The command and undo wire format, `clientEventId` and `expectedSeq` already exist. Every accepted command **and** every undo appends exactly one event, so the seq an item will get is known in advance: `confirmedSeq + position in the queue`.
* The idempotency fingerprint is made from the command only, **not** from `expectedSeq` (`ScoringUseCase.fingerprint`), and the lookup by `clientEventId` happens **before** the seq check. So a retry after a lost answer is a harmless replay, and a queued item can be re-sent with a new `expectedSeq` after a rebase and still be recognised as the same ball.
* Setup is frozen once the match starts (`MATCH_STARTED`), so the phone's copy of the rules, overs and playing XIs cannot go stale mid-match.
* An undo is an event (`UNDO`), never a deletion, and `planUndo` decides what it voids.
* `at` on an event is the server's clock when it receives the item (`clock.instant()`), and the 24-hour window is anchored to the first `at` after which the match was finished.
* Only auth and ticket routes are rate-limited today (`RateLimitWebFilter`); scoring is not.
* **There is no CI workflow in the repository** (`git ls-files` finds no `.github/`). AGENTS.md names GitHub Actions as the intended pipeline. Section 1.5 says what "CI fails on" would mean and asks for a decision (D2).

## 1. Keeping the TypeScript engine identical to the Java engine

### 1.1 What is ported

The TypeScript engine is a port of exactly these Java pieces and nothing else (no ids generated, no clocks, no I/O):

* `domain/rules`: `ScoringEngine`, `InningsState`, `DismissalRules`, `Delivery`, `Outcome`, `RuleViolation`, `MatchResultCalculator`, `Scorecard`.
* `domain/scoring`: `MatchScoring` (apply and fold), `ScoringLog` (`effective`, `planUndo`, the undo mode), `MatchScore`, `MatchSetup`.
* Not ported: the 24-hour `EditWindow` (display only, the server enforces it), names, the public scorecard, persistence.

It lives in `frontend/src/features/cricket/engine/` with no React and no browser APIs, so it can run in Node (vitest), in the browser and later in a worker. Java stays the oracle: the rule is "the TypeScript engine must give the Java engine's answer", never the other way round.

### 1.2 Where the vectors live

A new folder at the repository root, owned by neither module:

```text
testdata/cricket-engine/
  manifest.json            vectorVersion, engineVersion, a SHA-256 per file, the coverage list (see 1.5)
  golden/*.json            the existing golden innings plus one hand-written file per rule family
                           (free hit, retirements, bowler limits scaling, run-outs, revised target,
                           innings end reasons, result calculation, undo modes)
  random/seed-0001.json …  seeded random games produced by the Java RandomScorer (built in 2f-0: 120 seeds, about 4,900 steps in all
                           with the golden files, many of them deliberately illegal commands; more seeds can be added by changing one constant)
  from-ts/seed-0001.json … seeded random games produced by the TypeScript generator (about 100 seeds), see 1.4; arrives with 2f-1
```

A vector file is plain JSON, integers and strings only (no floats, no dates, no map-ordering dependence; lists in a fixed order; ids are the small fixed ids the golden match already uses):

```json
{ "name": "…", "setup": { "overs": 2, "rules": {…}, "teamA": "…", "teamB": "…", "tossWinner": "…", "xi": {…} },
  "steps": [
    { "op": "command", "command": { "type": "DELIVER", … },
      "expect": { "result": "accepted", "waivers": [], "state": { …projection… } } },
    { "op": "command", "command": { … },
      "expect": { "result": "rejected", "violation": "NO_BALL_FREE_HIT_DISMISSAL", "state": null } },
    { "op": "undo", "mode": "BALL", "expect": { "voids": [7, 6], "state": { … } } }
  ] }
```

The **state projection** is the canonical, fully specified shape both sides compare: status, per-innings runs, wickets, legal balls, extras by kind, striker, non-striker, bowler, each batter's line and how out, each bowler's line, free-hit flag, target, end reason, result, waivers, and the "what the engine needs next" field (new batter, new bowler, nothing). It is defined once in `testdata/cricket-engine/README.md` and implemented twice. A short **fingerprint** (a SHA-256 of the canonical projection, first 8 bytes) is also stored per step; section 7 uses the same fingerprint at run time as a safety net.

### 1.3 How each side runs them

* **Java** (`mvn test`, unit phase, no Docker):
  1. `EngineVectorsReplayTest` replays every file against the Java engine and compares each step.
  2. `EngineVectorsAreCurrentTest` regenerates the golden and `random/` files in memory and asserts they are byte-for-byte what is committed. A change to the Java engine that alters any outcome therefore fails here until someone runs the exporter (`mvn test -Dvectors.write=true -Dtest=EngineVectorExporter`) and commits the diff, which a reviewer then reads as "the rules changed".
  3. `TsVectorsReplayTest` replays `from-ts/` against the Java engine (see 1.4).
  4. `EngineVectorCoverageTest` (1.5).
* **TypeScript** (`vitest run`):
  1. `engine.vectors.test.ts` replays golden, `random/` and `from-ts/` against the TypeScript engine, comparing every projection and every refusal code.
  2. The manifest check: the SHA-256 of every file must match the manifest, and the manifest's `engineVersion` must be one this build declares it supports.
  3. Own property tests (below) and the unit tests ported from the Java rule tests (same test names, so a missing port is visible).

### 1.4 Both directions, and property tests without new dependencies

* Java to TypeScript: the Java vectors above.
* **TypeScript to Java:** the interesting failure for offline use is "the phone accepts a ball the server rejects" (the queue would stall). A script `pnpm run vectors:ts` runs a seeded TypeScript random-game generator (a port of `RandomScorer`, using a small seeded generator such as mulberry32, so **no new dependency**), records the TypeScript engine's answer to each step, and writes `from-ts/`. The Java suite replays those files. Any disagreement, in either direction, fails a Java or a TypeScript test.
* The TypeScript invariants test is a port of `EngineInvariantsTest` (the same checks after every accepted command: balls add up, runs add up, wickets at most the limit, a finished innings accepts nothing, replaying the accepted commands rebuilds the state, applying the same command to the same state twice gives the same answer). Failures name the seed.

### 1.5 What fails, and where

If a CI workflow is added (D2), one job, path-filtered to `backend/cricket/**`, `testdata/cricket-engine/**` and `frontend/src/features/cricket/engine/**`, runs `mvn -pl cricket test` and `vitest run src/features/cricket/engine`, and **fails on any of**:

1. the Java engine's output differs from a committed vector (the regenerate-and-compare test);
2. the TypeScript engine's output differs from a committed vector;
3. the Java engine rejects what the TypeScript vectors say it accepted, or the reverse;
4. a vector file's hash differs from `manifest.json`, or `manifest.json` names an `engineVersion` the TypeScript build does not declare;
5. **coverage:** a Java test enumerates every `ScoringCommand` type, every `RuleViolation` code, every dismissal kind, every innings end reason and both undo modes, and fails if the corpus has no step for one of them, so a new rule cannot ship without a vector. The manifest records the covered set and the TypeScript side checks it against its own enums.

Until a workflow exists the same checks are the existing unit suites, run by hand and by `e2e/validate-all.sh` (which would gain one step). That is weaker, and is the reason D2 asks for a minimal workflow now.

`engineVersion` (an integer in the manifest, in the Java engine and in the view the server returns) is bumped by hand whenever rules change. The phone refuses to use its engine against a server with a different version (section 7), so a deployed rules change never meets stale JavaScript silently.

## 2. The offline queue

### 2.1 How many balls can wait

Whole innings can be played in places with no signal, so the limit is generous but finite and never silent:

* **Cap: 600 queued items per match** (commands and undos; a limited-overs innings is about 150 to 200 items including batter and bowler choices, so this is more than two innings).
* **Warn at 150** ("N changes are waiting on this phone; get signal soon").
* **At the cap (600), or when a write fails** (storage full, quota, blocked), the next tap is **refused and nothing is half-saved**. The pad is disabled, and a blocking banner says in plain words: "This phone is holding 600 unsent changes, the most it will keep. Get a signal so they can be sent, then carry on. Nothing already scored is lost." with the buttons *Retry sending now* and *Copy unsent balls*. The pad re-enables by itself once a send frees space. A failed write shows a different message ("This phone could not save that ball, so it was not counted. Nothing else was lost.") and the ball is not shown on screen. The rule is "a ball is accepted on screen only after it is committed to storage", which is also a change from today, where a failed `localStorage` write is ignored.

### 2.2 Where it is stored (works over plain HTTP)

The app is opened over `http://<laptop address>:3000` on a phone, so nothing that needs a secure context is allowed: no service worker, no Background Sync, no Cache Storage, no `navigator.storage.persist()`, no Web Locks (this is already why the tab lease uses `localStorage`).

* **IndexedDB** is available over plain HTTP and is the store: a database `dsports-scoring` with stores `queue` (key `[matchId, index]`), `journal` (append-only), `engineState` (one row per match, section 2.4) and `meta`. Each tap is one small write transaction, committed before the screen accepts the ball. A hand-written wrapper (a few dozen lines), no library.
* `localStorage` is rejected for the queue: it is synchronous on the main thread, would rewrite the whole queue on every tap (quadratic bytes), has a 5 MB shared cap, and cannot do an atomic append. It stays for the tab lease.
* A permanent hint under the scorer's header whenever offline scoring is available (D10): **"Keep this page open while scoring offline."** (Balls are saved if the page is closed, but the page cannot be re-opened without a network.)
* If IndexedDB is missing or fails to open (some private modes), the screen **falls back to today's behaviour**: online, one-slot outbox, no offline scoring, with a banner saying so. It never queues into memory only.

### 2.3 What survives

| Event | Survives? |
|---|---|
| Page reload, the tab being discarded by the browser, the browser being killed, a phone restart, a crash mid-tap | **Yes**: committed items are in IndexedDB; an in-flight send is retried with the same `clientEventId`. A tap that was not yet committed is not on screen either. |
| Signing out | The queue stays, tagged with the user id; a different user signing in on the same phone cannot send it (the server would refuse) and sees it as "set aside, belongs to another account". |
| Access token expiry while offline | The sender refreshes the token with the refresh token (7 days). If that has expired too, the sign-in dialog opens and the queue waits. |
| The user clearing site data, or the browser evicting storage (Safari evicts script-written storage after about 7 days without use, and plain HTTP cannot ask for persistence) | **No.** Mitigation: whenever the queue is not empty, an always-visible "Copy unsent balls" / "Download unsent balls" action exports the queue as readable text plus JSON, and the warning copy says so. This limit is stated in the user-facing help. |
| **Re-opening the app while offline** | **No page load is possible** without a service worker: the JavaScript itself must come from the network. Offline scoring works in a page that is already open. If that tab is killed while offline, the balls are safe in IndexedDB and the page re-opens, and resumes the queue, when the network returns. A real PWA with a service worker needs HTTPS (production on Cloud Run has it, the LAN demo does not) and is a separate later step (D10). |

### 2.4 What the phone needs in order to score offline

At the moment the scorer screen opens online it stores, in `engineState`: the match setup (rules, overs, both XIs, names for display), the full effective log (commands with their seqs and the undo voids), and the server's `engineVersion` and state fingerprint. The server needs a new read for this (section 3.3). After every confirmed answer the phone refreshes the stored state. The phone can score offline only if this record exists and the self-check passed (section 7).

### 2.5 The journal

`journal` is an append-only record of every transition (`enqueued`, `sending`, `confirmed`, `replayed`, `conflict`, `rejected`, `parked`, `rebased`, `discarded`, with time and the item's label). It feeds the "unsent balls history" screen and the export, and it is what the conservation test checks (section 8): **enqueued = confirmed + parked + discarded + still waiting, always**.

## 3. Sending the queue

### 3.1 Order, one by one, ids

* Strictly in order, **one request at a time**, using the existing `POST …/scoring/commands` and `…/undo` routes. Item *i* carries its own `clientEventId` (made when it was tapped, kept for life) and `expectedSeq = confirmedSeq + i`, assigned when it was enqueued. The queue is append-only until acknowledged, so those numbers never need renumbering.
* After a 200 the sender checks that the event seq the server reports equals the predicted one. A mismatch is treated as an engine disagreement (section 4, case D).
* A `replayed: true` answer (the item was applied earlier and the answer was lost) is a success.
* No batching in 2f (D5). Reasons: it needs no new server surface to design, secure and test; each item's outcome is individually clear; "stop at the first failure" falls out for free; a flush of 150 items at about 100 ms each is under 20 seconds on a normal link. Another scorer slipping in between two items is exactly the conflict case the design already handles. If measurement later shows it matters, a batch route that applies a prefix inside one exclusive session can be added without changing the queue format.
* Pace: one in flight, and no more than about 5 per second; on `429` honour `Retry-After`.

### 3.2 Retry and failure handling

| Answer | The sender |
|---|---|
| network error, timeout, `502/503/504` | keep the item, back off 2, 4, 8 … 30 s, retry at once on the browser `online` event and when the tab becomes visible; show the next attempt time and "Retry now" |
| `401` | refresh the token once, else open sign-in; queue kept |
| `403`, `404` (no longer a scorer, match gone) | stop, park everything with the reason, keep it |
| `400`, `CLIENT_EVENT_REUSED` | a bug or corrupted storage: stop, park, keep, show a "report this" code |
| `409 SCORE_CONFLICT` | section 4, case A |
| `422 SCORING_REJECTED` | section 4, case D |
| `409 MATCH_LOCKED` | section 5 |
| `200` | mark confirmed, advance, update the stored engine state |

### 3.3 What the server checks (unchanged) and the small additions

Unchanged, and re-checked for every item: sign-in, the caller may score this match, the match is ready, idempotency by `clientEventId` (the same id with different content is refused), `expectedSeq` equals the latest seq, the match is not locked, then the Java engine decides. All of it runs inside `store.exclusive`, so concurrent items for one match are serialised.

Small server additions (all read-only or additive, each with an OpenAPI entry, route-sweep and `SecurityPolicyTest` rows, README and tests):

1. `GET /api/v1/matches/{id}/scoring/engine-state`: setup, the effective log with seqs and undo voids, `engineVersion`, state fingerprint, seq. Same authorisation as the existing scoring state route.
2. `ScoringView` gains `engineVersion` and `stateFingerprint`.
3. Each event in `eventsSince` (and `recentEvents`) gains `mine` (was it this caller's) and, for the caller's own events, its `clientEventId`, so the phone can tell "the server already has my ball" from "someone else scored".
4. (D9, approved) The request may carry `tappedAt`; it is stored on the event for the timeline only, clamped to not be later than receipt, and **never used by any rule: not the 24-hour window, not ordering (the seq is), not validation of the command**. One nullable column in a new migration (never an edit of an old one). The migration is proved with the append-only guard in place (an INSERT with and without the column works, an UPDATE or DELETE is still refused, the TRUNCATE guard still holds), and a test proves that changing `tappedAt` to any value cannot change what the engine, the lock or the order does.

## 4. Conflicts: the match changed while the phone was offline

The server never merges. When the first queued item's `expectedSeq` is no longer the latest, it answers `409 SCORE_CONFLICT` with `currentSeq`, the current view and the events since. Nothing is written. The phone stops sending (the rest of the queue is "blocked", still safe) and opens a sheet.

**Case A, other scorer's balls (or an admin's event) landed.** Who can cause it: another scorer or the organiser scoring from another device, or an administrator's unlock (an `ADMIN_UNLOCK` event is an ordinary event with a seq).

1. The phone first removes its own events from the picture: if `eventsSince` contains events whose `clientEventId` matches queued items (an item applied while the answer was lost), those items are marked confirmed; only **foreign** events are shown.
2. The scorer sees: "While you were offline, **Ravi** scored 7 balls (list: over, ball, text) and the match is now Lions 41/2. You have **N unsent balls** (list)."
3. The phone computes a **preview** by folding the server's effective log, then applying the queued items in order with the TypeScript engine: either "all N can be added after theirs: Lions 41/2 becomes 58/3", or "balls 1 to 3 can be added; ball 4 (`W bowled`) cannot because <rule message>".
4. The scorer chooses one of:
   * **Add my balls after theirs** (offered for the longest prefix that applies). The items keep their `clientEventId`s and get new `expectedSeq`s; the journal records `rebased`. **The phone's preview is only a preview.** Every rebased item is sent to the server like any other and **validated ball by ball by the Java engine** (seq, idempotency, lock, then the rules); the phone's result is never trusted as it is. If the server refuses one (`422`), the queue stops there with the case D handling, and the preview shown to the scorer is replaced by what the server actually accepted. Items past the first one that cannot apply stay waiting in "set aside".
   * **Keep their version and set mine aside**: every queued item moves to **set aside**. Nothing is deleted.
   * **Not now** (read only; the queue stays blocked).
5. We never try to guess that both scorers entered the same real ball. The preview shows both, and the scorer decides; double counting is prevented by the scorer's choice, not by a heuristic.

**Case B, an administrator changed the match.** An unlock is an event (case A). If the match was finished by someone else (`END_MATCH`) or reached its end, the preview shows the rule refusal for the first ball that no longer applies; those balls are set aside.

**Case C, no longer allowed** (the scorer was removed, the match was deleted): `403/404`, everything is set aside with the reason.

**Case D, the server rejects an item with `422` even though the seq matched.** That means the TypeScript engine and the Java engine disagreed (a parity bug) or the phone's copy of the log was wrong. The phone stops, shows the server's rule message, marks the match "offline scoring off" until the page is reloaded and the self-check passes, logs an `engineMismatch` record in the journal, and offers: **fix this ball** (re-enter it with the pad, applied to the state after the confirmed items) or **set aside this and the rest**. This is the case the vectors exist to make impossible, and it must be loud, never silent.

**Set aside** is a visible list, explained in plain words on the screen (D8): *"These balls are saved on this phone but are **not** in the match. They were set aside because <reason: someone else scored first / the correction window closed at 18:40 / you are no longer a scorer>. They will not be lost. If the match is locked, ask an administrator to unlock it (a reason of at least 10 characters), then press Send again."* Each entry shows: each item with its label and the reason, buttons *Send again* (when the reason has gone, for example after an unlock), *Copy text*, *Download*, and *Discard* (with a confirmation naming the ball). Items stay until sent or discarded by the scorer; there is no expiry and no automatic deletion.

**Nothing is lost silently, as testable rules:** an item leaves the queue only by (a) a 200 from the server or (b) an explicit confirmed discard; every move between waiting, blocked and set aside is a journal entry; the conservation property in 2.5 is a test; and there is no code path that clears a queue without one of those two.

## 5. The 24-hour lock and the queue

The window is anchored to the first time the **server** saw the match finished, using the server's clock at receipt (`at = clock.instant()`), and an administrator's unlock gives 24 hours from the unlock. Consequences, and the proposal (D8: keep this rule, do not trust the phone's clock):

* A match finished offline: the final ball is still waiting, so the window has not started. When it is finally received the window starts then. A phone that was offline for three days can still deliver the last ball and the window then opens normally. (This is the intended "first finish" rule, now written down.)
* A correction (an undo or a ball) queued while the match was finished and online, then the deadline passes before it reaches the server: `409 MATCH_LOCKED` for that item. The queue stops there; that item and the rest are **set aside as "locked"**, with the closing time shown. Items before it that were already accepted stay accepted; the sheet says exactly how many went in.
* The deadline passing in the middle of a flush: items up to the deadline are in, the next gets `MATCH_LOCKED`, the rest wait in set aside. No gaps in the log (each item is its own request).
* A local warning: if the queue is not empty and the last known `editableUntil` is within 30 minutes (with the offset between the phone's and the server's clock taken from the `Date` header of the last answer), a banner says so.
* Recovery: an administrator unlocks (existing route, reason of at least 10 characters). *Send again* on the set-aside items then sends them with the original `clientEventId`s; since they were never applied there is nothing to replay. The scorer is told to ask an administrator; the app does not unlock anything itself.
* The alternative (accept items tapped before the deadline, using `tappedAt`, within some grace) is listed as the other option in D8. It makes the deadline depend on a client clock, so it is not recommended.

## 6. Undo while offline

Undo stays an event, so the queue stays append-only and the seq chain is never renumbered (D6).

* **Undo of a ball that is still only in the queue:** the phone runs `planUndo` on its local effective log (confirmed events plus queued items), shows the same "Undo: 4 runs, over 3.2" label as today, and **appends an UNDO item** to the queue. When sent, the server first records the ball and then the undo, exactly as if the scorer had been online. The server recomputes what the undo voids from its own log, which at that point equals the phone's, and the vectors include undo steps (both modes) so the two `planUndo` implementations are proven equal. Cost: the mistaken ball appears in the log and in the history. Benefit: one rule, no renumbering, an honest audit trail. (The alternative, cancelling an unsent ball without ever sending it, needs renumbering of every later `expectedSeq` and an exception to "append-only"; not proposed.)
* **Undo of a ball the server already has:** the same UNDO item; it will be refused with `MATCH_LOCKED` if the window has closed, or conflict if someone else scored, handled as above.
* An undo never reaches back further than `planUndo` allows today (it cannot cross the start of an innings).
* While an item is in flight the pad is still usable (the queue is the source of order); an undo that would target an in-flight item is allowed and simply queues behind it.
* Undo is counted against the cap like any item.

## 7. Two-tab rule and the scorer screen states

### 7.1 Two tabs and two devices

* The existing tab lease (`localStorage`, heartbeat, `storage` event, works over HTTP) now also guards the queue: only the lease holder may enqueue **or send**. A second tab of the same match is read-only, shows the waiting count read-only, and cannot flush. The lease is re-checked immediately before every send (as today).
* Two **devices** with the same account and both with queues are not detectable locally; the server handles them (case A). Idempotency makes a duplicated send harmless.

### 7.2 Self-check and the version handshake (why the phone can trust its engine)

* On load, and after each confirmation, the phone folds its stored log with the TypeScript engine and compares the **fingerprint** with the server's `stateFingerprint` for the same seq. A mismatch turns offline scoring off for that match (back to one-at-a-time online scoring), writes a journal record and shows a banner. **Turning offline scoring off never drops anything:** every queued item stays in storage, in order, still sendable one by one (the server validates each as always) and still reviewable in the unsent list; only the phone's own prediction of new balls stops. If the queue is not empty when this happens, the banner says how many balls are kept and offers *Send now*, *Review* and *Copy unsent balls*; new balls are not accepted until the queue is empty (they would depend on a state the phone no longer trusts).
* If the server's `engineVersion` is not one the loaded JavaScript supports (a deploy happened while the page sat open), offline scoring is off until reload, with the same rule: the queue is kept, sendable and reviewable.
* Combined with the vectors, this makes a disagreement a build failure first and a visible, contained event second.

### 7.3 Screen states

A pure reducer (testable without a browser) drives one state at a time:

| State | Shown | Pad |
|---|---|---|
| **Online** | normal screen | on |
| **Offline** (nothing waiting) | chip "Offline: scoring continues, saved on this phone" | on |
| **Waiting (n)** | chip "n unsent"; the balls appear in the scoreboard in an **unconfirmed** style (dashed, "not on the server yet") and a note that spectators see the score up to the last confirmed ball | on |
| **Sending (i of n)** | progress, balls turn solid as they are confirmed | on (new balls join the queue) |
| **Retrying** | next attempt time, *Retry now*, last error in plain words | on |
| **Needs sign-in** | sign-in dialog, queue kept | on (queue continues) |
| **Conflict** | the blocking sheet of section 4 | **off** until the scorer chooses |
| **Rejected / engine disagreement** | sheet with the server's message and the choices of case D | **off** |
| **Locked** | banner with the closing time, set-aside list, how to ask for an unlock | off |
| **Set aside (n)** | persistent entry point to the set-aside list, with a count | unaffected |
| **Offline scoring off** (IndexedDB missing, self-check failed, version mismatch) | banner explaining; behaves like today (one change at a time, online only) | per today |
| **Read only (second tab)** | banner | off |

## 8. Test plan

**TypeScript unit and property**
* Engine: ports of the Java rule tests (same names), the vectors replay, the invariants port, undo planning, a "what's needed next" test per command.
* The queue as a pure reducer plus a storage interface with two adapters: an in-memory one for fast tests and the real IndexedDB one for contract tests in a real Chrome (no new dependency such as `fake-indexeddb`, D12).
* **Conservation property test:** random sequences of tap, send success, network failure, lost answer, conflict, rejection, lock, reload, discard; after every step: `enqueued = confirmed + parked + discarded + waiting`, order preserved, ids unique, `expectedSeq` contiguous, no item ever removed except by confirm or confirmed discard.
* Storage failures: write refused, cap reached, quota error, database blocked, corrupted row (parked, never dropped).
* Sender: ordering, one in flight, backoff, `429`, token refresh, lease lost mid-flush, version mismatch.

**Java**
* Vector replay, regenerate-and-compare, TypeScript-origin replay, coverage (section 1).
* `engine-state` route: authorisation matrix, a lost snapshot, 404s, plus `SecurityPolicyTest` and route-sweep rows; `mine` and `clientEventId` on events; `engineVersion` and fingerprint equal to the Java engine's own on every step of the golden match.

**HTTP concurrency (Testcontainers), a batch of offline balls**
* 60 queued items sent in order: log is exactly the expected events, snapshot equals the fold.
* The same batch sent twice at once (two tabs or two retries): one set of events, all answers consistent, `replayed` on the second.
* Lost answers: the server applied items 1 to 20, the client resends from item 1: 20 replays and then new items accepted.
* A second scorer scores 3 balls midway through the flush: exactly the conflict answer at the next item, nothing written for it; after a rebase with the same ids and new `expectedSeq` the log has no gaps and no duplicates.
* Deadline passes mid-flush (fixed clock): prefix accepted, next `MATCH_LOCKED`, the rest unwritten, no gap; after an unlock the rest is accepted with its original ids.
* Scorer removed mid-flush: `403/404`, log unchanged from that point.
* A 300-item flush at the intended pace is not refused by any limiter; if a general limiter is added later its `429` is honoured (a test with a stub that answers `429` and `Retry-After`).
* Parity check over the wire: for the golden match and for 30 random vectors, the phone-side predicted fingerprints equal the server's `stateFingerprint` at every seq.

**Browser end to end, emulated (Playwright, extends `e2e/cricket-ui.mjs` style; `e2e/cricket-offline.mjs`)**
* Phone context offline: score 40 balls including a wicket, a wide, a no-ball with a free hit, an over change, an undo; close the tab and open a new one when back online (the "killed tab" case); assert the queue survived in IndexedDB, flushed in order, and the signed-out laptop context shows the same final score; the database log has exactly the expected number of rows.
* Conflict: while the phone is offline a second API client scores 2 balls; reconnect; assert the sheet lists those 2, shows the preview, and "Add my balls after theirs" ends with the right totals.
* Lock: move the finish time back in the local test database (with the same guard as `cricket-ui.mjs`), assert the set-aside list and the unlock recovery.
* Labelled "emulated, not a real phone", with the same local-database guard.

**Real phone, extending SETUP-AND-DEMO 7b as 7c** (about 25 minutes, the same Lions v Tigers 2-over match, phone over `http://10.3.34.177:3000`, laptop signed out on `/m/<id>`):
1. Start online, score over 1 (the same balls as 7b through the first wicket).
2. Turn airplane mode on. Score the rest of over 1 and all of over 2 with **at least 8 balls**: include a wide, a wicket with a new batter, an over change with a new bowler, one mistaken ball and its undo.
3. Check the chip says how many are unsent, the balls look unconfirmed, the laptop has not changed.
4. From the phone's app switcher, close the browser. Turn airplane mode off. Re-open the app; the queue count is the same; it sends by itself; the laptop follows ball by ball; final score matches the expected total (to be written down with the exact balls when the engine exists).
5. Conflict drill: airplane mode on, score 3 balls; on the laptop sign in as the organiser in a second window and score 2 balls on the same match; airplane mode off; the phone shows the sheet with the 2 foreign balls and the preview; choose "Add my balls after theirs"; check the totals.
6. Log check: `select seq, type from ball_events where match_id='<id>' order by seq` has exactly the count the script gives, with the undo and the rebased balls each present once.
7. Report the iOS or Android model, the browser, and whether the "Copy unsent balls" export worked.

## 9. Decisions needed, each with my recommendation

| # | Decision | Recommendation |
|---|---|---|
| D1 | Vectors in `testdata/cricket-engine/`, Java as oracle, both directions (Java-made and TypeScript-made) | **Yes.** |
| D2 | Add a minimal GitHub Actions workflow now (none exists), path-filtered, running the Java unit tests and the engine vitest folder and failing on the five conditions in 1.5 | **Yes**, as the first commit of 2f. Otherwise the parity guarantee depends on someone remembering to run two suites. |
| D3 | Queue store | **IndexedDB**, with a graceful fall-back to today's online-only one-slot behaviour when it is unavailable. Not `localStorage`. |
| D4 | Queue cap | **600 items, warn at 150**, refuse (never drop) at the cap or on a failed write. |
| D5 | Send one by one on existing routes, or add a batch route | **One by one** for 2f; revisit only if measured slow. |
| D6 | Undo of an unsent ball | **Append an UNDO item** (append-only queue, honest log). |
| D7 | Conflict policy | **Scorer-confirmed rebase** with a preview (keep ids, new `expectedSeq`), or set aside; never automatic, never silent discard. |
| D8 | 24-hour window while offline | **Server receipt time decides**; items are set aside as "locked" and recovered by an administrator unlock. Not a client-clock grace. |
| D9 | Store `tappedAt` (display only, never used by rules) | **Yes**, one nullable column in a new migration, so offline commentary times are the real ones. If you would rather keep 2f migration-free, say so and offline balls show the time they reached the server. |
| D10 | Re-opening the app with no network (service worker, PWA) | **Out of 2f.** 2f works in a page that is already open; the queue survives the page being killed. A PWA needs HTTPS and is a later step (2g) for the deployed site. |
| D11 | Server additions: `engine-state` route, `engineVersion` and fingerprint in the view, `mine` and `clientEventId` on events | **Yes**, all additive and read-only. |
| D12 | New dev dependencies (`fast-check`, `fake-indexeddb`) | **None**: a seeded generator, an in-memory adapter and real-Chrome IndexedDB tests. |
| D13 | Shape of delivery | Five approval gates, each its own commit(s), tests and clean-worktree check: **2f-0** CI workflow and vector folder with the Java exporter and Java tests; **2f-1** TypeScript engine and its vector, property and unit tests (no UI); **2f-2** durable queue, journal and reducer (no UI); **2f-3** server additions (engine-state, events, version, optional `tappedAt`); **2f-4** scorer screen states, sender, conflict and set-aside sheets, then the emulated e2e and the 7c real-phone script. |
| D14 | Keep scoring on screen while a conflict sheet is open | **No**: the pad is off until the scorer chooses, because the base state is unsettled. |
| D15 | Queue belongs to a user id | **Yes**; another account on the same phone sees it as set aside and cannot send it. |

Open risks I would verify early rather than assume: whether any browser the scorers use blocks IndexedDB over plain HTTP in its private mode (the fall-back covers it); how many items real 2-over and 20-over matches produce (the cap); and whether the 7-day Safari eviction matters to the first users (the export button is the mitigation).

## Approved decisions (recorded when the plan was approved)

* **D2, CI: yes.** `git remote -v` shows GitHub (`RakeshAnna014/DSports-AI`), so the pipeline is `.github/workflows/`. On every push and pull request it runs the Java unit tests (including the vector tests), the frontend typecheck, lint and unit tests (including the engine vectors). The Docker integration tests (Testcontainers) run on pushes to `main` and on manual dispatch only, because they take about 15 minutes and need Docker-in-runner; the unit tier is what protects the engine parity on every push.
* **D8: server receipt time decides**, and the screen explains set-aside balls and points to the administrator unlock (section 4).
* **D9: yes**, `tappedAt`, display only, proved against the append-only trigger (section 3.3).
* **D10: no PWA in 2f**, with the hint "Keep this page open while scoring offline." (section 2.2).
* **All other decisions take the recommendations above.** Each is listed in the commit message of the gate where it first matters: D1 and D2 in 2f-0, D12 in 2f-1, D3, D4 and D6 in 2f-2, D9, D11 in 2f-3, D5, D7, D14, D15 in 2f-4.

## What I will not do without your approval

Write any code, change a migration, add a dependency, add a workflow, or start 2f-0. This file and the pointer in `cricket-features.md` are the only changes proposed, and neither is committed.
