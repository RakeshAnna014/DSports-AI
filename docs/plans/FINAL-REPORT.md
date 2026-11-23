# Cricket build: report of the autonomous run (INTERIM: the run stopped before the end of the plan)

Branch `feature/ground-booking`, 14 commits on top of `3591c8a`, **nothing pushed**. Working list and per-gate reports: [PROGRESS.md](PROGRESS.md). Every decision with its alternatives: [DECISIONS-LOG.md](DECISIONS-LOG.md).

## What is finished, and what is not (updated 2026-11-23 after the second part of the run)

| Gate | State |
|---|---|
| 2f-1 to 2f-4 offline scoring | done, verified in clean worktrees; the real-phone run (script 7c) is yours |
| 3a analytics and commentary | done; frontend verified in a clean worktree, backend in the working tree |
| 3b career stats (V58 projection, public route, admin rebuild) | done; verified in the working tree with Docker (HTTP 30/30, persistence 24/24); not in a clean worktree |
| 3c player page `/p/:id`, scorecard names link to it | done; frontend tests green in the working tree |
| 3d guest to account link | met by the existing claim and approval flow (id kept, test) |
| 3e shot direction (V59) and wagon wheel | done, online scorer only; no full-page test of the scorer-screen wiring |
| 4a tournaments | **only the pure fixture generator is built** (tested); aggregate, tables, routes not built |
| 4b points table | **only the pure points table with net run rate is built** (tested); route, leaderboards, awards not built |
| 4c, 4d, 5a, 5b, 5c, 5d | **not built** |
| Final: fresh clone x3, whole-branch review | **not done** |

The owner's product decisions of this session are in DECISIONS-LOG.md. PROGRESS.md ends with the exact resume point and the next steps in order.

## Test counts

* **Clean worktree, real `pnpm install`, at `dc40758` (everything through 2f-4):** frontend `tsc -b` and lint clean, **819 tests**, `vite build` ok; backend `mvn test` **1,208 tests, 0 failures** (1 skipped: the opt-in vector writer); the e2e guard 5/5.
* **Clean worktree at `938c8b0` (through 2f-3), including Docker:** backend integration **198 tests, 0 failures**.
* **After that, in the working tree:** `CricketScoringHttpIntegrationTest` 28/28 against a real database (includes the 3a route); cricket unit tests incl. `MatchAnalyticsTest` (7), `PublicMatchesUseCaseTest` (19), `SecurityPolicyTest` (114 rows); frontend public folder 36/36 (`AnalyticsPanel.test.tsx` 10).
* **Real stack, emulated phone:** `node e2e/cricket-offline.mjs` passes (13 balls offline, tab killed, flush once each in order, conflict then rebase, window closing then unlock) and the older `node e2e/cricket-ui.mjs` passes.
* New this run, by area: TypeScript engine (vectors 243, properties 9, rules 27, purity 13), offline queue and sender (about 100), local view parity (36 steps equal to the Java view), offline screen states (16), 7c numbers (6), Java: fingerprint equals the recorded one at 5,000+ steps, engine-state, V57 migration (round trip, upgrade of a populated V56 database, append-only guards), 60-item batch, two tabs at once, lock mid-flush.

## What the offline work proves

* The TypeScript engine gives the Java engine's answer at every step of 126 Java-made files (about 4,860 steps, no vector edited), and the Java engine accepts every step of 100 TypeScript-made files (3,219 steps).
* The phone's view of the match equals the server's view after each of the 36 golden commands; the fingerprint the server sends equals the phone's.
* Against the real server and database: balls scored with no signal are saved, survive the tab being killed, arrive once each and in order, with no gap and no duplicate id; another scorer's balls are never merged silently; a closed correction window sets the rest aside with the reason and an administrator's unlock lets them in with their original ids.

## Bugs found by the tests along the way (all fixed with a test)

`expectedSeq` is the last seq seen (my first queue used the item's own seq); after a reload the self-check compared the saved fingerprint with a longer log; a sync while a conflict was open silently unblocked a chain that no longer applied; an unreadable stored row could have been overwritten by a new item; an absurdly old `tappedAt` overflowed PostgreSQL (a 500); names missing after an offline reload; the conflict sheet listed none of the other scorer's balls when it was the same account on another device; an administrator's unlock raised a conflict about an event that cannot change the score.

## Known limits

No super over or DLS (not in the Java engine). No wagon wheel (the scorer does not record shot direction). No server-sent events (polling with an ETag meets the plan's criterion). No service worker: a closed browser cannot reopen the page with no network (D10); a phone must open a match online once before it can score it offline. The browser scripts are not in CI (the workflow has no browser step). The GitHub workflow has **never run** (never pushed). `frontend/tsconfig.tsbuildinfo` is tracked and changes on every build (left out of commits).

## What only you can do

1. **Real-phone test, script 7c** in [docs/SETUP-AND-DEMO.md](../SETUP-AND-DEMO.md) (about 25 minutes, exact balls and the numbers to expect: Lions 19/2, 19 rows in the log, 13 changes in airplane mode; plus the conflict drill). The step that only a real phone can prove is that the phone's own browser keeps the saved balls when closed and sends them by itself. Report the phone model, the browser and whether *Copy or download unsent balls* worked. Also worth knowing: Safari may evict script-written storage after about 7 days of no use.
2. **Push, open the PR and watch CI** (nothing is pushed; this is the first time the workflow will run):
   `git push -u origin feature/ground-booking`, `gh pr create --base main --head feature/ground-booking --title "Cricket: scoring, offline scoring, vectors, CI" --body "See docs/plans/FINAL-REPORT.md"`, `gh run watch`, and if something fails `gh run view --log-failed`.
3. **Run the Docker integration job by hand:** `gh workflow run ci.yml --ref feature/ground-booking`, then `gh run list --workflow=ci --limit 3`.
4. **Product decisions the plan leaves open** (Phase 1/2 open questions still unanswered): how a guest player proves a profile is theirs (OTP to the phone); whether scorers can be invited by link; tennis and leather stats kept apart (recommended); box cricket rules; PWA vs native (a PWA needs HTTPS, so it belongs with the deployed site); whether to add shot direction to the scorer screen (needed for a wagon wheel); the Looking and feed features; the entry-fee model for tournaments and a real payment gateway (the mock is still the only one).
5. **Production and repository hygiene:** the default local database password `dsports123` in `docker-compose.yml`, the author email on the commits (a work address, see the earlier secret scan), and whether to stop tracking `tsconfig.tsbuildinfo`.

## How to continue

Read PROGRESS.md (gate list and criteria), DECISIONS-LOG.md and `git log`, then start at gate 3b. Do not redo finished gates. The local backend on :8080 is running the 3a jar and the local `dsports` database is at V57.
