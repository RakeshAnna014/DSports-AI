# Cricket build: progress

Read this, `DECISIONS-LOG.md` and `git log` first after any context reset. Never redo a finished gate. Rules: never push, never rewrite commits, new migrations only, no weakened tests.

Branch `feature/ground-booking`. Started this run at `3591c8a`.

## Gate list (ordered)

| Gate | What | State |
|---|---|---|
| 2f-0 | CI + shared engine vectors | done (reviewed) |
| 2f-1 | TypeScript scoring engine, replay of all vectors, TS-made vectors replayed by Java, property tests | **in progress** |
| 2f-2 | Durable IndexedDB queue, journal, pure reducer (no UI) | todo |
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

(Report appended when the gate is finished.)
