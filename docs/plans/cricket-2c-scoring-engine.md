# Step 2c: the scoring rules engine (design for review)

Status: **implemented** (approved with two changes, decisions 4 and 7 below). Nothing in this step touches the database or the API. Those are 2d.

## 1. What it is

A pure Java package, `com.dsports.cricket.domain.rules`, that answers one question:

> Given the state of an innings and one command from the scorer, is the command legal, and what is the new state?

```java
Outcome apply(InningsState state, ScoringCommand command)   // Accepted(newState) | Rejected(violation, message)
```

* **Pure.** No Spring, R2DBC, Jackson, clock or randomness. A test reads every source file in the package and fails on any import outside `java.*` and `com.dsports.cricket.domain.*`.
* **Immutable.** A state is a record. A rejected command returns the same state, untouched (tested by equality).
* **Replayable.** The 2d event log stores accepted commands. `fold(events)` applies them from an empty innings and must equal the stored snapshot (a property test; 2d adds the database check).
* **The client sends outcomes, not facts.** Striker, non-striker, bowler, over and ball are derived by the engine and never sent by the client.

### Files

| File | Role |
|---|---|
| `InningsState` | Score, wickets, legal balls, extras, batter and bowler lines, who is at which end, free-hit flag, what is needed next, fall of wickets, status |
| `ScoringCommand` (sealed) | `StartInnings`, `Deliver`, `SelectBatter`, `SelectBowler`, `ChangeBowlerMidOver`, `RetireBatter`, `ReviseTarget`, `EndInnings` |
| `Delivery`, `Dismissal` | One ball's outcome and a dismissal on it |
| `DismissalRules` | The allowed-dismissal table, in one place |
| `RuleViolation`, `Outcome` | One code per rule; `Accepted(state)` or `Rejected(violation, message)` |
| `ScoringEngine` | `apply(state, command)`, `replay(commands)`, `eligibleBowlers(state)` |
| `MatchResultCalculator` | Result from two completed innings |
| `Scorecard` | Read model computed from a state (batters, bowlers, extras, fall of wickets, overs). 2e serves it |

`MatchRules` (already in `domain.model`) supplies balls per over, players per side, bowler limit, penalties, free hit and last man stands.

## 2. A delivery

```text
Delivery(kind, runs, runsType, boundary, dismissal)

kind      NORMAL | WIDE | NO_BALL
runs      0..7 per kind of run (bat, bye, leg bye, wide). Extras add on top: a no-ball with a six is 1 + 6
runsType  BAT | BYE | LEG_BYE        (a WIDE's runs are always wide runs)
boundary  true when the 4 or 6 was a boundary (counted as a four/six, and not run)
dismissal null | (type, batterOut, end)    end is only for RUN_OUT: BATTING_END | BOWLERS_END
```

| | Legal ball (counts towards the over) | Ball faced by the striker | Runs charged to the bowler | Extras |
|---|---|---|---|---|
| NORMAL, BAT | yes | yes | runs | none |
| NORMAL, BYE / LEG_BYE | yes | yes | 0 | b / lb = runs |
| WIDE | **no** | **no** | wide penalty + runs | wd = penalty + runs |
| NO_BALL, BAT | **no** | yes | no-ball penalty + runs | nb = penalty; bat runs to the batter |
| NO_BALL, BYE / LEG_BYE | **no** | yes | no-ball penalty | nb = penalty; b / lb = runs |

A **maiden** is a credited over with no bat runs, no wides and no no-balls (byes and leg byes are allowed).

## 3. Dismissals (as approved)

|  | Normal ball | Wide | No-ball | Free hit (any kind) |
|---|:-:|:-:|:-:|:-:|
| BOWLED | yes | | | |
| CAUGHT | yes | | | |
| LBW | yes | | | |
| STUMPED | yes | yes | | |
| HIT_WICKET | yes | yes | | |
| RUN_OUT | yes | yes | yes | yes |
| OBSTRUCTING_FIELD | yes | yes | yes | yes |
| HIT_BALL_TWICE | yes | | yes | yes |

* A ball is allowed only if both its kind **and** the free-hit state allow it. So on a free hit that is also a wide, only RUN_OUT and OBSTRUCTING_FIELD remain.
* Only the striker can be BOWLED, CAUGHT, LBW, STUMPED, HIT_WICKET or HIT_BALL_TWICE. Either batter can be RUN_OUT or OBSTRUCTING_FIELD.
* BOWLED, CAUGHT, LBW, STUMPED and HIT_WICKET carry no runs (only the wide penalty on a wide). RUN_OUT, OBSTRUCTING_FIELD and HIT_BALL_TWICE keep the runs completed before the dismissal.
* Credited to the bowler: BOWLED, CAUGHT, LBW, STUMPED, HIT_WICKET. Not credited: RUN_OUT, OBSTRUCTING_FIELD, HIT_BALL_TWICE, RETIRED_OUT.
* Not modelled: timed out, handled the ball (now part of obstructing), substitutes, penalty runs.

## 4. Who is where (strike)

The state keeps two creases: the **batting end** (the batter there faces) and the **bowler's end**. The striker is whoever is at the batting end.

1. After the ball, the batters swap ends once per run actually run. A boundary is treated as not run, so a 4 or a 6 never swaps them. Wide runs, no-ball bat runs, byes and leg byes all count when run.
2. After the final legal ball of an over, they swap again (so a single on the last ball leaves the same batter on strike).
3. **Run out:** the engine is told which batter and at which end. After `r` completed runs the batters have swapped `r` times. The dismissed batter leaves that end; the survivor is at the other end; the incoming batter takes the dismissed batter's end. Naming the end is what makes "run out backing up" and "run out at the striker's end" both right. The scorer's screen will default the end from the run parity.
4. **Any other dismissal** or a retirement: the incoming batter takes the dismissed batter's end.
5. With **last man stands** and one batter left, there is no second batter. He faces every ball, and nothing swaps.

## 5. The free hit

* A NO_BALL makes the next delivery a free hit (when the match rules enable it).
* It stays until a legal ball is bowled. A wide does not use it up. A NO_BALL on a free hit makes the next one a free hit again.
* It survives a mid-over change of bowler. An over always ends on a legal ball, and the free hit is the next delivery, so it never needs to cross an over. It does not carry into the next innings.

## 6. Bowlers

* Opening bowler and each over's bowler come from the fielding XI, chosen with `SelectBowler` when `needs = NEW_BOWLER`.
* Not the bowler of the previous over. Not a bowler whose **credited** overs already equal the limit (`maxOversPerBowler`, or when null the larger of `ceil(overs / 5)` and `ceil(overs / (playersPerSide - 1))`: an 11-a-side 20-over match allows 4 overs, a 4-a-side one 7).
* **Mid-over change (injury):** `ChangeBowlerMidOver(newBowler)` is allowed once at least one delivery of the over has been bowled. The new bowler bowls the rest of that over. He must not have bowled the previous over, must not already have bowled in this over, and cannot bowl the next over (Law 17.8). The replaced bowler **may bowl again later in the innings**, including the next over; only the bowler who finished the over is barred from the next one.
* **If nobody is eligible** (everyone left is at the limit), the over limit is waived as a last resort, so an innings with too few bowlers can still be scored. The rule that nobody bowls two overs in a row is waived only when no other bowler is left. Each waiver is **recorded**: it comes back on the command's outcome (so 2d stores it on the event), is kept in the innings state (`waivers`: the 0-based over, the bowler and `OVER_LIMIT` or `CONSECUTIVE_OVER`) and is listed on the scorecard.
* **Who the over counts for:** the bowler who bowled the most **legal** balls in it. A tie goes to the bowler who started it. That bowler gets the credited over (for the limit and for maidens). Every bowler's figures still show the legal balls they actually bowled (for example 0.2 and 0.4).

## 7. Batters

* `SelectBatter` when `needs = NEW_BATTER`. A batter who has not batted, or one who retired hurt, can come in. One who is out or retired out cannot.
* **RETIRED_HURT:** between balls only. Not a wicket, no fall-of-wickets entry. The batter's score stays, and they can return later through `SelectBatter`.
* **RETIRED_OUT:** between balls only. Counts as a wicket, shown in the fall of wickets as "retired out", no bowler credit, cannot return.
* If nobody is left to come in, but a batter retired hurt, that batter must come back or the scorer ends the innings. If nobody is left at all, the innings is complete (all out).

## 8. When an innings ends

| Reason | When |
|---|---|
| ALL_OUT | wickets = XI size - 1 (or = XI size with last man stands) |
| OVERS_COMPLETE | the last legal ball of the last over |
| TARGET_REACHED | second innings, runs >= target. Ends at once, even mid-over |
| DECLARED / NO_BATTERS_LEFT | `EndInnings(reason)` by the scorer |

After an innings is complete every command except `ReviseTarget` is rejected.

## 9. The result

`MatchResultCalculator` works from two completed innings.

* Chasing side reaches the target: **won by N wickets** (wickets in hand, and balls left).
* Otherwise the first side **won by N runs**.
* Equal runs: **TIE**. No super over (out of scope).
* A `ReviseTarget(newTarget)` command sets the target for the chase (manual DLS only; the number is the scorer's, we do not calculate it).
* The result is computed from the events every time and is never stored on its own, so an undo (2d) re-derives it.

## 10. Hand-scored example (please check this)

Rules: 2 overs, 6 balls per over, wide and no-ball penalty 1, free hit on, 11 a side, bowler limit `ceil(2/5) = 1` over.

Lions bat: 1 Arun, 2 Bala, 3 Chetan, 4 Dev, 5 Esha, 6 Farid, 7 Gita, 8 Hari, 9 Ishan, 10 Jai, 11 Kiran. Tigers bowl: Tarun, Uday, Vik and others.

Arun (striker) and Bala open. Tarun bowls over 1.

| # | Ball | Event | Runs | Score | Striker after | Non-striker after | Note |
|--:|:-:|---|:-:|:-:|---|---|---|
| 1 | - | Tarun to Arun: **wide**, no runs run | wd 1 | 1/0 | Arun | Bala | not a legal ball, not faced |
| 2 | 0.1 | Arun runs 1 | 1 | 2/0 | **Bala** | Arun | one run swaps ends |
| 3 | - | Bala: **no-ball**, hits a boundary 4 | nb 1 + 4 | 7/0 | Bala | Arun | a boundary is not run, no swap. Next ball is a **free hit** |
| 4 | - | Free hit: Bala "bowled" | - | 7/0 | Bala | Arun | **rejected** `DISMISSAL_NOT_ALLOWED_ON_FREE_HIT`, state unchanged |
| 5 | 0.2 | Free hit: Bala runs 2 | 2 | 9/0 | Bala | Arun | even runs, no swap. Free hit used up |
| 6 | 0.3 | Bala misses, **bye** 1 | b 1 | 10/0 | **Arun** | Bala | bye is a legal ball and a ball faced |
| 7 | 0.4 | Arun runs 1, then **run out** going back for the second. Arun is out at the batting end | 1 | 11/1 | **Chetan** (new) | Bala | after the 1 run the batters had crossed once, and were crossing again when Arun was run out; Bala ends at the bowler's end, Chetan takes the batting end. Fall of wicket 1-11. Not credited to Tarun |
| 8 | 0.5 | Chetan **bowled** | 0 | 11/2 | **Dev** (new) | Bala | Fall of wicket 2-11, credited to Tarun |
| 9 | 0.6 | Dev, dot ball | 0 | 11/2 | **Bala** | Dev | end of over: swap. Needs a new bowler. Tarun cannot bowl again. Uday selected |
| 10 | 1.1 | Uday to Bala: 1 run | 1 | 12/2 | Dev | Bala | |
| 11 | 1.2 | Dev runs 2 | 2 | 14/2 | Dev | Bala | even, no swap |
| 12 | - | **Dev retires hurt** (2 off 2) | - | 14/2 | **Esha** (new) | Bala | not a wicket, no fall of wicket. Esha takes Dev's end. `needs` cleared by selecting Esha |
| 13 | - | **Uday injured. Vik** takes over the rest of the over | - | 14/2 | Esha | Bala | Uday cannot bowl again. Vik bowls 1.3 to 1.6 |
| 14 | 1.3 | Vik to Esha: dot | 0 | 14/2 | Esha | Bala | |
| 15 | 1.4 | Esha runs 1 | 1 | 15/2 | Bala | Esha | |
| 16 | 1.5 | Bala hits a **six** | 6 | 21/2 | Bala | Esha | boundary, no swap |
| 17 | 1.6 | Bala runs 1 | 1 | 22/2 | **Bala** | Esha | the single swaps to Esha, the end-of-over swap puts Bala back. Innings complete: OVERS_COMPLETE |

### Expected scorecard: Lions 22/2 in 2.0 overs

| Batter | How out | Runs | Balls | 4s | 6s |
|---|---|--:|--:|--:|--:|
| Arun | run out | 2 | 2 | 0 | 0 |
| Bala | not out | 14 | 6 | 1 | 1 |
| Chetan | b Tarun | 0 | 1 | 0 | 0 |
| Dev | retired hurt | 2 | 2 | 0 | 0 |
| Esha | not out | 1 | 2 | 0 | 0 |

Did not bat: Farid, Gita, Hari, Ishan, Jai, Kiran.

Extras 3 (b 1, lb 0, wd 1, nb 1, pen 0). Check: 2 + 14 + 0 + 2 + 1 = 19 off the bat, plus 3 extras = 22.

Fall of wickets: 1-11 (Arun, run out, 0.4), 2-11 (Chetan, 0.5). Retired hurt, not a wicket: Dev at 14/2 (1.2).

| Bowler | Overs | Credited overs | Maidens | Runs | Wickets | Wd | Nb | Economy |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| Tarun | 1.0 | 1 | 0 | 10 | 1 | 1 | 1 | 10.00 |
| Uday | 0.2 | 0 | 0 | 3 | 0 | 0 | 0 | 9.00 |
| Vik | 0.4 | 1 | 0 | 8 | 0 | 0 | 0 | 12.00 |

Check: bowler runs 10 + 3 + 8 = 21, plus 1 bye (not charged to a bowler) = 22. Legal balls 6 + 2 + 4 = 12. Tarun's 10: wide 1, Arun's single 1, no-ball 1 + 4, free hit 2, Arun's run before the run out 1. The bye is not charged.

Over 2 is credited to Vik (4 legal balls to Uday's 2), so Vik has used his one allowed over.

## 11. Tests

* **Table-driven:** the dismissal matrix (8 types x 3 kinds x free hit on/off = 48 cases, each accepted or rejected with the expected code), who can be out, runs allowed with each dismissal.
* **Per rule:** legal balls and ball counts, every extra and who is charged, strike for every run count 0 to 7 with and without a boundary, over-end swap, run out at each end with each parity, free hit lifecycle (no-ball, wide, no-ball on a free hit, over change), bowler limit, consecutive overs, injured bowler cannot return, mid-over credit (3-3 tie goes to the starter, 2-4 goes to the second), maiden with byes, innings end for each reason, last man stands, target reached mid-over, result (won by runs, by wickets, tie, revised target).
* **Retirements:** RETIRED_HURT is not a wicket and can return, RETIRED_OUT is a wicket and cannot, neither is allowed mid-ball, nobody left to come in.
* **Golden:** the example above, asserted ball by ball (score, wickets, striker, non-striker) and as the final scorecard. A second golden is a complete chase.
* **Properties (seeded random sequences of legal commands, each step checked):** total = batter runs + extras; bowler runs + byes + leg byes = total; legal balls = sum of bowler balls; two different batters at the crease; wickets never exceed the limit; a rejected command leaves the state equal; `fold(events)` equals the live state.
* **Architecture:** the no-framework-imports test.

## 12. Decisions I need you to confirm

1. A boundary is not run, so it never swaps ends. (Overthrows are not modelled.)
2. A run out names the batter **and the end**. The screen defaults the end from the run parity.
3. A free hit survives a wide, and a no-ball on a free hit gives another free hit.
4. **(changed)** The replaced (injured) bowler may bowl again later in the innings. Only the replacement who finished the over cannot bowl the next one.
5. The over counts for the bowler of most legal balls (tie: the starter). Figures show the balls actually bowled.
6. A retirement is only accepted between balls.
7. **(changed)** Bat runs (and bye, leg-bye and wide runs) are at most 7 per ball. There is no cap on the total: extras add on top, so a no-ball with a six is 7.
8. A bye on a no-ball is a bye (extras) plus the no-ball penalty. It is not charged to the bowler beyond the penalty.
9. **(added while building)** If every bowler left is at the over limit, the limit is waived so the innings can continue.
