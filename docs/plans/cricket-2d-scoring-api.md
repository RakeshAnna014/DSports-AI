# Step 2d: the ball-by-ball API and the scorer screen (design for review)

Status: **implemented** (approved with two changes, D6 and D8; see section 13). Migration: **V55**. Builds on the 2c engine (`docs/plans/cricket-2c-scoring-engine.md`).

## 1. What changes from the plan, and why

| Plan (`cricket-features.md`) | This design | Why |
|---|---|---|
| `POST /balls`, `/undo`, `/end-innings` | One `POST /scoring/commands` (all command types), plus `/undo`, `/scoring` (state) and an admin `/unlock` | The engine now has 8 commands (select batter, select bowler, retire, change bowler, revise target, end innings, start innings, deliver). One request = one outbox item = one event. |
| Tables `ball_events`, `innings_snapshots` | `ball_events` (append-only) and `match_snapshots` (one row per match) | One match-level snapshot, so a viewer reads one row and the ETag is its `seq`. |
| Event kinds START_INNINGS, BALL, UNDO, END_INNINGS | The engine's commands, plus `END_MATCH`, `UNDO`, `ADMIN_UNLOCK` | The log stores *commands*; the 2c engine replays them. |

## 2. The data (V55)

```text
ball_events                                   (append-only, no UPDATE or DELETE: a trigger refuses both)
  id UUID PK
  match_id UUID NOT NULL  -> matches(id)
  seq INT NOT NULL CHECK (seq >= 1)           1, 2, 3 ... no gaps, per match
  type VARCHAR(20) NOT NULL                   START_INNINGS DELIVER SELECT_BATTER SELECT_BOWLER CHANGE_BOWLER RETIRE_BATTER
                                              REVISE_TARGET END_INNINGS END_MATCH UNDO ADMIN_UNLOCK
  payload JSONB NOT NULL                      the command; for UNDO {"voids":[seq,...]}; for ADMIN_UNLOCK {"reason":"..."}
  schema_version SMALLINT NOT NULL
  request_hash CHAR(64) NOT NULL              hash of the canonical payload, to tell a retry from a reused id
  client_event_id UUID NOT NULL
  actor_id UUID NOT NULL
  waivers JSONB NOT NULL DEFAULT '[]'         the rules the engine set aside on this event (bowler limit, consecutive overs)
  created_at TIMESTAMPTZ NOT NULL
  UNIQUE (match_id, seq)
  UNIQUE (match_id, client_event_id)

match_snapshots                               (the read model; written in the same transaction as the event)
  match_id UUID PK -> matches(id)
  seq INT NOT NULL                            equals the highest seq in ball_events for the match
  match_status VARCHAR(20) NOT NULL
  score JSONB NOT NULL                        the whole match score: the innings states and whether it was ended by hand
  result JSONB NULL
  first_finished_at TIMESTAMPTZ NULL          the FIRST time the match finished (completed, abandoned, no result); never cleared (D6)
  unlocked_until TIMESTAMPTZ NULL             set by an admin unlock: that moment + 24h; the window ends at max(first_finished_at + 24h, unlocked_until)
  updated_at TIMESTAMPTZ NOT NULL
```

* `match_snapshots` **must equal the fold of the events**. A test replays every event through the engine and compares (section 10).
* The domain stays free of Jackson. A codec in `infrastructure.persistence` maps commands and states to JSON, and a round-trip test feeds it the property test's random commands.
* The match's `status` column is updated in the same transaction, by a statement that also bumps the match `version`, so a setup edit made from an old page still conflicts.

## 3. The API

All routes need a sign-in. A stranger gets 404. A member who only has a team in the match gets 403. Scoring needs the creator or a scorer.

| Route | Who | Does |
|---|---|---|
| `GET /api/v1/matches/{id}/scoring` | creator, scorer | The current `ScoringView` |
| `POST /api/v1/matches/{id}/scoring/commands` | creator, scorer | Applies one command |
| `POST /api/v1/matches/{id}/scoring/undo` | creator, scorer | Undoes the latest command(s) |
| `POST /api/v1/admin/matches/{id}/scoring/unlock` | ADMIN | Reopens a locked match for 24 hours (a reason is required) |

```json
POST .../scoring/commands
{ "clientEventId": "uuid", "expectedSeq": 41,
  "command": { "type": "DELIVER",
               "delivery": { "kind": "NORMAL", "runs": 1, "runsType": "BAT", "boundary": false,
                             "dismissal": { "type": "RUN_OUT", "batterOut": "uuid", "end": "BATTING_END" } } } }
```

Other command bodies: `START_INNINGS {striker, nonStriker, bowler}` (the server adds the teams, XIs, rules, overs and target), `SELECT_BATTER {batterId}`, `SELECT_BOWLER {bowlerId}`, `CHANGE_BOWLER {bowlerId}`, `RETIRE_BATTER {batterId, type: HURT|OUT}`, `REVISE_TARGET {target}`, `END_INNINGS {reason: DECLARED|NO_BATTERS_LEFT}`, `END_MATCH {reason: ABANDONED|NO_RESULT}`.

**`ScoringView`** (everything the screen needs, so the client holds **no cricket rules**): `seq`, `matchStatus`, the innings (score, wickets, overs, extras, striker, non-striker, bowler, this over's balls, batters, bowlers, fall of wickets, waivers, target, runs needed and balls left), `needs` (`BATTER`, `BOWLER` or none), `freeHit`, `allowedDismissals` for the next ball, `eligibleBatters`, `eligibleBowlers` (each with its overs used and why it is disabled), `waiverRequired`, `result`, `canUndo {allowed, describe, crossesInnings, reopensResult}`, `editableUntil`, `locked`, and the last 12 events with who scored them.

**Errors**

| Status and code | When |
|---|---|
| 409 `SCORE_CONFLICT` | `expectedSeq` is not the latest. Body: `currentSeq`, the current view and the events since `expectedSeq`. Nothing is written. |
| 409 `CLIENT_EVENT_REUSED` | The same `clientEventId` with different content |
| 422 `SCORING_REJECTED` | The engine refused the command. Body: `violation` (one of the `RuleViolation` codes) and a message. Nothing is written. |
| 409 `MATCH_LOCKED` | More than 24 hours since completion and nobody unlocked it |
| 409 `NOT_LOCKED`, `NOTHING_TO_UNDO`, `MATCH_NOT_READY` | Self-explanatory (`MATCH_NOT_READY`: no toss or no two XIs) |
| 400 | Malformed JSON or an unknown command type. Never a 500 |

A rule violation is not a concurrency problem, so it is 422 and the client can tell "someone else scored" from "that ball is not allowed".

## 4. What one command does (one transaction)

1. **Authorize** (404 / 403 as above).
2. **Lock the match**: `pg_advisory_xact_lock` on the match id. Two requests for one match run one after the other.
3. **Idempotency first.** If `(match, clientEventId)` already exists: the same `request_hash` returns **200** with `replayed: true`, the original `eventSeq` and the *current* view (not a stored copy of the old response); a different hash is `CLIENT_EVENT_REUSED`. This runs before the seq check, so a retry after a lost response never becomes a false conflict.
4. **Check `expectedSeq`** equals the snapshot's `seq`, else `SCORE_CONFLICT`.
5. **Check the lock window** (section 7).
6. **Build the engine command** (for `START_INNINGS`: the match's teams, XIs, rules, overs and, in the second innings, the target from the first) and call `ScoringEngine.apply` on the snapshot's state. A refusal is `SCORING_REJECTED`; no event is written.
7. **Append the event** (`seq + 1`, with the engine's waivers) and **write the snapshot**, the match status and the result, in the same transaction.
8. Return the view.

The advisory lock is the first defence. The unique keys `(match_id, seq)` and `(match_id, client_event_id)` are the second: if the lock were ever bypassed, a stale or duplicate write fails in the database instead of corrupting the score.

## 5. Undo

Undo is an `UNDO` event. It does not delete anything. The **effective log** is the commands that no `UNDO` voids; the state is the engine's fold of the effective log. So undo needs no special code for overs, wickets or innings: a replay with fewer commands gives the right state, including strike, the over credit, the free hit and the result.

* **Which commands:** `UNDO {mode: "COMMAND"}` voids the latest effective command. `UNDO {mode: "BALL"}` voids the latest effective commands back to and including the latest `DELIVER`, as one event, so "the new batter I just chose" and "the wicket" go together. It never goes past a `START_INNINGS`.
* **No redo.** A scorer who undoes too much enters the ball again.
* **Across an over boundary.** After ball 6 of an over, `SELECT_BOWLER` and ball 1 of the next over, `COMMAND` undo takes them off one at a time: the first removes ball 1, the second removes the bowler choice (the screen asks for a bowler again), the third removes ball 6 (the over is open again, the batters are swapped back, the over credit is reversed). `BALL` undo takes the last ball and anything chosen after it in one go.
* **Across an innings end.** The ball that ended innings 1 can be undone only after the second innings has been undone (the stack is the order things happened). `canUndo.crossesInnings` is true when the next undo would reopen an innings or reopen a completed match, and the screen then asks for confirmation and says what will happen. Match status follows the effective log: it goes back from `COMPLETED` to `IN_PROGRESS`, or from `INNINGS_BREAK` to `IN_PROGRESS`, or from `IN_PROGRESS` to `TOSS_DONE` (setup can then be edited again).
* **The result is never stored on its own.** It is recomputed from the effective log, so undoing the winning ball removes the result and undoing a ball in the first innings changes the target and the result.
* **After completion.** Commands and undo are allowed for **24 hours** after the match completes. After that, `MATCH_LOCKED`. An ADMIN can `unlock` with a reason of at least 10 characters; that writes an `ADMIN_UNLOCK` event (who, when, why: the audit trail) and allows 24 hours from then. If a correction completes the match again, the window restarts from that completion.

## 6. The scorer screen (`/matches/:id/score`)

Mobile first, one screen at a time, big targets (at least 48 px), no rules in the client: what is allowed, who is eligible and what is needed comes from the view.

```text
 SETUP page                         S1 START INNINGS
 [Start scoring] when ready  --->   Batting: Lions.  Opening striker [Arun v]  Non-striker [Bala v]
                                    Opening bowler (Tigers) [Tarun v]            [Start innings]

 S2 EACH BALL (the main screen)
 ┌──────────────────────────────────────┐
 │ Lions 22/2   (2.0 ov)    CRR 11.00   │  chase: "Need 12 off 9" · "FREE HIT" banner
 │ Bala* 14 (6)      Esha 1 (2)         │
 │ Vik 0.4-0-8-0     this over: 0 1 4 .  │
 │ [ 0 ][ 1 ][ 2 ]                      │
 │ [ 3 ][ 4 ][ 6 ]     4 and 6 are boundaries; "ran 4" is under More
 │ [ WD ][ NB ][ B ][ LB ]  -> bottom sheet for the extra runs (WD+0..4, NB + bat 0..6 or bye/leg bye)
 │ [  W  ]            [ Undo: 1 run ]   │
 │ ⋮ More: Retire batter · Change bowler (injury) · Declare · End match · Revise target │
 └──────────────────────────────────────┘

 S3 WICKET (W)  How out?  <- only what allowedDismissals says (a wide, a no-ball and a free hit shorten the list)
    Bowled / Caught / LBW / Stumped / Hit wicket / Hit twice / Obstructing / Run out
    Run out ->  Who: (o) Arun (striker)  ( ) Bala      Runs completed: [-] 1 [+]
                Out at: (o) striker's end  ( ) bowler's end     "Arun is out. Next to face: the new batter."   [Confirm]

 S4 NEW BATTER (blocks scoring until chosen)    Farid  Gita  Hari ...   "Dev (retired hurt, 2*) can return"
 S5 END OF OVER: "Over 2: 7 runs"   NEW BOWLER: Tarun (bowled last over)  Uday  Vik  ...
    Anyone at the limit shows "4 of 4 overs" and is disabled. If everyone left is at the limit the screen says so and
    asks "Allow Uday to bowl anyway?" (the waiver is recorded and listed on the scorecard).
 S6 INNINGS BREAK: "Lions 22/2. Tigers need 23 from 12 balls"   [Start second innings] (same as S1, sides swapped)
 S7 SECOND INNINGS: as S2, with the chase line
 S8 RESULT: "Tigers won by 10 wickets (0 balls left)"   [Scorecard] [Correct a mistake (23 h 10 min left)]
    After the window: "Locked. An admin can reopen it."
```

**Conflict.** If a request is answered with `SCORE_CONFLICT`, a sheet opens and nothing is retried silently:

```text
 "Someone else scored while you were away"
 Now: Lions 25/2 (2.3).   Since you last looked: 1.5 Bala 1 (Sam)   1.6 Esha 2 (Sam) ...
 Your unsent ball: "4"
 [Apply my ball on top]  [Discard my ball]
```
"Apply my ball on top" sends it again with the new `expectedSeq`; the engine checks it against the real state and the screen shows any refusal. Nothing is merged for the scorer without a tap.

**Offline.** The decision is below (D2). This design builds a **durable single-slot outbox**: a command is saved on the phone (`localStorage`, keyed by match) with its `clientEventId` before it is sent, and removed only when the server answers. If the network fails the screen shows "Saved on this phone. Sending when you are back online (1 ball waiting)" and keeps the last confirmed score, marked "waiting". A reload or a sleeping phone keeps it. While a command waits, the next ball cannot be entered (a second command would depend on a state the phone cannot know). On reconnect it is sent with the same `clientEventId`: if the first try did reach the server, the answer is `replayed: true` and nothing is counted twice.

**Undo.** The button always says what it will undo ("Undo: 1 run" / "Undo: wicket, Bala out"). One tap undoes the ball (`BALL` mode) and shows "Undone: ..." for 5 seconds. When `crossesInnings` is true it first asks: "This will reopen the second innings and clear the result. Undo?". A locked match shows no Undo, only the lock message.

## 7. Run-out end: default and override

`Dismissal.end` says at which end of the pitch the batter was run out. The screen **defaults it from the runs completed** (`r`) and lets the scorer change it.

| Batter out | r even (0, 2, 4) | r odd (1, 3, 5) |
|---|---|---|
| The striker | out at the bowler's end | out at the striker's end |
| The non-striker | out at the striker's end | out at the bowler's end |

Reason: after `r` completed runs the batters have crossed `r` times, and the one who is out was running to the end he had not yet reached. The golden innings is the odd case: Arun (the striker) after 1 run, out at the striker's end.

**Override.** A two-option control ("Out at: striker's end / bowler's end") sits under the default with a one-line preview ("Arun is out. Bala stays at the bowler's end. The new batter will face."). The scorer changes it for a batter run out backing up before the ball was played, a run out where the batters did not cross, or any case the parity rule does not fit. The preview is only a preview; the server's answer is the truth. The server cannot check the parity (the cases above are legal), so it accepts either end. A unit test covers the table (4 cases x parities 0 to 4) and the preview text.

## 8. Authorization and privacy

* Scoring, undo and the `scoring` state: the creator or a scorer. A person with only a team in the match gets 403; a stranger 404. Unlock: ADMIN only (checked on the server).
* Names in the view come from the match's XIs. There are no phones, emails or other matches in it.
* The public scorecard (2e) reads `match_snapshots` through its own use case and type. It never returns this view.

## 9. Where it lives

| Layer | New |
|---|---|
| domain | `ScoringLog` (the effective-log rule: which commands an `UNDO` voids; pure), `ScoringProjection` (match status, innings, result from the effective log; pure) |
| application | `ScoreMatchUseCase` (commands, undo, unlock), `ScoringQueryUseCase`; ports `ScoringLogRepository` (append under the lock, read events) and `SnapshotRepository` |
| infrastructure | `ScoringJsonCodec`, R2DBC adapters using `DatabaseClient` and the advisory lock, the append-only trigger (in V55) |
| interfaces | `ScoringController`, `ScoringRequests`, `ScoringViewMapper` |
| frontend | `ScorerPage` (screens above), `scoringOutbox.ts` (the durable slot), `runOutEnd.ts`, `scoringApi` |

## 10. Which tests prove two scorers cannot corrupt a match

| Risk | Test | Level |
|---|---|---|
| A stale write applies | Same `expectedSeq` sent by 20 requests at once (different client ids): exactly one 200, the rest `SCORE_CONFLICT`; the log gains exactly one event | HTTP + Testcontainers |
| A stale write applies, lock bypassed | Insert two events with the same `(match_id, seq)` directly: the database refuses | persistence |
| A double tap or a lost response counts twice | Same `clientEventId` 10 times at once: one event, all answers identical; sequentially after other events were added: `replayed: true`, no new event | HTTP + persistence |
| A reused id hides a different ball | Same id, different content: `CLIENT_EVENT_REUSED`, nothing written | use case + HTTP |
| Gaps or duplicates in the log | Two scorers each score 60 balls, retrying on conflict by reading the state again: afterwards seq is exactly 1..N with no gaps, and the score equals the sum of what each scorer was told was accepted | HTTP + Testcontainers |
| The snapshot drifts from the log | After every step of the golden innings scored over HTTP, the stored snapshot equals the engine's fold of the stored events; plus a seeded random run of two simulated scorers against the use case with the same check | HTTP + property |
| The log is edited afterwards | `UPDATE` and `DELETE` on `ball_events` are refused by the trigger | persistence |
| Undo races a ball | An undo and a ball with the same `expectedSeq`: one wins, the other conflicts; two undos with the same `expectedSeq`: one wins | HTTP + Testcontainers |
| Undo breaks the score | Undo across an over boundary, across an innings end and after completion returns exactly the earlier state (the state after undo equals the state before the undone commands, for every prefix of the golden innings) | unit |
| Undo after the window | Allowed at 23 h 59 min, `MATCH_LOCKED` at 24 h 01 min (fixed clock); unlock by a non-admin is 403; unlock writes an audited event and reopens for 24 h; the result is recomputed on undo | use case + HTTP |
| Someone without the right scores | A stranger 404, a team member 403, a scorer 200, the route sweep and `SecurityPolicyTest` classify all four routes | HTTP |
| A rule broken over the wire | Each of the engine's refusals returns 422 `SCORING_REJECTED` with its `violation`, writes nothing, and a malformed body is a 400 never a 500 | HTTP |
| The codec loses something | The property test's random commands and states survive the JSON round trip and replay to the same state | unit |
| The client sends twice or loses the answer | The outbox keeps a command across a reload and resends it with the same `clientEventId`; a `replayed` answer clears it; `SCORE_CONFLICT` opens the sheet and clears nothing | vitest |
| The default run-out end is wrong | The 4-case parity table and the preview text | vitest |

## 11. Decisions I need from you

1. **D1.** One `commands` endpoint (plus `undo`, `scoring`, admin `unlock`) instead of `balls` and `end-innings`.
2. **D2. Offline.** The engine is Java, so the phone cannot work out what comes next (a new batter? a new bowler? a free hit?) while offline. **Recommended for 2d: the single-slot durable outbox above** (safe, simple, nothing can diverge). The alternative is to score many balls offline, which needs a TypeScript copy of the engine kept honest by shared test vectors generated from the Java one. That is a separate step I would do after 2e if grounds with no signal matter.
3. **D3.** Rule violation = 422, stale = 409.
4. **D4.** A replayed id returns 200 with the *current* view and the original `eventSeq` (we do not store old responses).
5. **D5.** Undo in `COMMAND` or `BALL` mode, never silently across an innings, with a confirmation when it reopens one.
6. **D6.** The 24 hours restart if a correction completes the match again; an admin unlock gives 24 hours from the unlock.
7. **D7.** `END_MATCH` (abandoned or no result) is part of 2d, as an undoable command.
8. **D8.** No fielder names or shot positions yet (the engine does not model them).

## 12. Out of scope for 2d

The public scorecard and its ETag (2e), live SSE, commentary, statistics, offline scoring of many balls (D2), push notifications, super over.

## 13. As built: what differs from the design above

* **D6 (changed):** the 24 hours are anchored to the **first** time the match finished and never restart. Undoing and finishing again at hour 23 still locks at hour 24 from the first completion (tested in the domain, in the use case and over HTTP). Only an administrator's unlock grants a fresh 24 hours, from the unlock, as an audited `ADMIN_UNLOCK` event. A correction left half-done at the deadline is locked as it is (an administrator can reopen it).
* **D8 (changed):** a fielder can be named (from the fielding XI) for caught, stumped and run out, and a scorecard reads "c Ravi b Tarun", "st Ravi b Tarun", "run out (Ravi)", "c & b Tarun". Shot positions stay out.
* **The write path folds the log** instead of reading the snapshot, so there is one source of truth; the snapshot is the read model and a test checks it equals the fold after every step.
* **Snapshot columns** are `score`, `first_finished_at` and `unlocked_until` (section 2 above).
* **Errors carry `details`:** the shared error body gained an optional `details` object, used for the conflict (current score and events since) and for the rule that was refused.
* **A resumed ball waits for the state to load** after a reload, so an older state cannot overwrite the newer answer.
* **Ids on a phone:** `crypto.randomUUID` does not exist over plain HTTP on a local network address, so the client falls back to `getRandomValues`.
