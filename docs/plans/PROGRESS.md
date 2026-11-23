# Cricket build: progress

Read this, `DECISIONS-LOG.md` and `git log` first after any context reset. Never redo a finished gate. Rules: never push, never rewrite commits, new migrations only, no weakened tests.

Branch `feature/ground-booking`. Started this run at `3591c8a`.

## Gate list (ordered)

| Gate | What | State |
|---|---|---|
| 2f-0 | CI + shared engine vectors | done (reviewed) |
| 2f-1 | TypeScript scoring engine, replay of all vectors, TS-made vectors replayed by Java, property tests | **done** (d6166e9, 6181fd2) |
| 2f-2 | Durable IndexedDB queue, journal, pure reducer (no UI) | **done** (see report; to be re-verified in a clean worktree with 2f-3) |
| 2f-3 | Server additions: engine-state route, engineVersion + state fingerprint, `mine`/`clientEventId` on events, `tappedAt` (migration V57) | todo |
| 2f-4 | Scorer screen states, sender, conflict / set-aside sheets, emulated e2e, real-phone script 7c | todo |
| 3a | Live score stream (SSE) over the existing log, commentary templates | todo (design to be written when reached; 2e shipped polling + ETag) |
| 3b | Career stats projection (rebuildable), player stats API | todo |
| 3c | Match analytics (wagon wheel input, Manhattan, worm, partnerships, fall of wickets) | todo |
| 4a | Tournaments: aggregate, teams, groups, fixtures, migration | todo |
| 4b | Points table with NRR, leaderboards, awards | todo |
| 4c | Entry fees through the payment module, tournament screens | todo |
| 5a | AI tools: match summary + report with grounding check | todo |
| 5b | AI tools: player stats, head to head, team insights, ground finder | todo |
| 5c | `book_ground` chat write (proposal + confirmation), Looking, feed | todo |
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

