# Cricket module (`dsports-cricket`)

Players (guests and registered), teams, matches and ball-by-ball scoring. Plan: [docs/plans/cricket-features.md](../../docs/plans/cricket-features.md). Decisions: [ADR-019](../../docs/adr/ADR-019-cricket-players-teams-matches-scoring.md).

**Status:** steps 2a (players, teams, claims; V53), 2b (match setup; V54), 2c (the scoring rules engine) and 2d (ball-by-ball API and scorer screen; V55, V56) are implemented. 2f-0 (the shared engine vectors in `testdata/cricket-engine`, written by `EngineVectorExporter` and checked by `EngineVectorsReplayTest`, `EngineVectorsAreCurrentTest` and `EngineVectorCoverageTest`; `MatchScoring.ENGINE_VERSION`; see that folder's README) is built. 2e (the public scorecard and the live-matches list, no migration) is implemented too. 2f-1 (the TypeScript engine in `frontend/src/features/cricket/engine`, replaying every Java-made vector, and `from-ts/` vectors made by it and replayed here by `TsVectorsReplayTest`) is built. The rest of offline scoring (2f-2 to 2f-4) comes next.

| | |
|---|---|
| API | `/api/v1/players` (guests, own profile, claims), `/api/v1/teams` (teams, members, captain), `/api/v1/matches` (create, settings, toss, playing XI, scorers). Every route needs a sign-in; the use cases decide who sees what |
| Tables | `players`, `teams`, `team_members`, `player_claims` (V53); `matches`, `match_players`, `match_scorers` (V54) |
| Layout | `domain` (model, exception; no framework imports) / `application` (ports, use cases, results) / `infrastructure/persistence` (R2DBC adapters) / `interfaces`; wired in `bootstrap/.../config/CricketModuleConfig` |
| Ports to other modules | `CustomerDirectoryPort` (who a claimant or scorer is, find by email) and `BookingLookupPort` (a venue booking), both implemented in bootstrap |
| Tests | domain and use-case unit tests with in-memory fakes; Testcontainers for V53 and the adapters; `bootstrap/.../CricketPlayersAndTeamsHttpIntegrationTest` over real HTTP and auth |

## Rules in force (2a)

* A **guest** player has no account and is created by someone who vouches for them; a registered customer can also create their own linked profile (`POST /players/me`, idempotent). A customer has at most one linked player.
* A **phone number** is optional, normalised, unique per creator, visible only to its creator, and only a search hint. A **claim** links a guest to a customer only when the guest's creator or an administrator approves it. Two profiles cannot be merged yet.
* A player can be added to a team only by someone who may use that player (created it, or it is their own profile).
* A team is edited by its creator or its captain's own account and seen by them and by linked members; everyone else gets 404. At most 30 players and one captain; the captain cannot be removed; an archived team is frozen; names are unique per creator.
* Edits of players and teams are version-checked: a stale edit is a 409.

## Rules in force (2b)

* A match is between two different, active teams the creator can see. Settings and rules are validated (overs 1 to 100; 4 to 10 balls per over; 2 to 11 players per side; penalties 0 to 5).
* The creator changes settings (version-checked) and scorers; the creator or a scorer records the toss and the playing XIs. A match is visible to its creator, its scorers and people with a team in it; everyone else gets 404.
* A playing XI is in batting order, 2 to players-per-side players, all from that team, none on both sides. Setup is locked once the match has started.
* An optional booking must be the creator's own confirmed cricket booking and hosts one match only; its venue is copied onto the match.

## The scoring rules engine (2c)

`com.dsports.cricket.domain.rules` is plain Java with no database, framework or clock. `ScoringEngine.apply(InningsState, ScoringCommand)` returns the new state or the rule that was broken (`RuleViolation`); `replay(commands)` rebuilds an innings from its log, and `MatchResultCalculator` and `Scorecard` read a finished state. See `docs/plans/cricket-2c-scoring-engine.md` for the rules, the dismissal table and the worked example, and ADR-019 for the decisions.

Tests: one class per area under `src/test/.../domain/rules` (dismissal table, counting and strike, bowlers and overs, free hit, retirements, innings end and result), the hand-scored golden innings and chase, seeded property tests over random commands, and a test that the package imports nothing but `java.*` and the cricket domain.

## Scoring (2d)

| | |
|---|---|
| Tables | `ball_events` (append-only log; V56 also refuses TRUNCATE), `match_snapshots` (read model, rebuildable from the log) (V55) |
| API | `GET /api/v1/matches/{id}/scoring`, `POST .../scoring/commands`, `POST .../scoring/undo`, `POST /api/v1/admin/matches/{id}/scoring/unlock` |
| Domain | `domain.scoring`: `MatchScoring` (match-level commands over the 2c engine), `ScoringLog` (the effective log and undo planning), `EditWindow` (the 24 hours from the first finish) |
| Application | `ScoringUseCase` (one transaction and lock per request), `ScoringViewBuilder` (the screen's view) |
| Infrastructure | `ScoringR2dbcStore` (advisory lock, append, snapshot), `ScoringJsonCodec` |

Rules in force: only the creator and scorers score (someone with only a team in the match gets 403, a stranger 404); every change carries a `clientEventId` and the `expectedSeq` the scorer saw; a retry of an accepted command is `replayed`; a stale seq is a `SCORE_CONFLICT`; a rule of cricket is a `422 SCORING_REJECTED`; undo voids and never deletes; the match is locked 24 hours after it first finished until an administrator unlocks it with a reason.
