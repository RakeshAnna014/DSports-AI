# DSports-AI: setup, testing and demo runbook

Single entry point for running, testing and demonstrating the project on one machine. Deeper material is linked, not repeated.
Status of every requirement: [STATUS.md](STATUS.md). Architecture: [ARCHITECTURE-OVERVIEW.md](ARCHITECTURE-OVERVIEW.md).

## 1. Prerequisites

Docker (Postgres with pgvector, Tika), Java 21 + Maven, Node 22 + **pnpm** (the frontend lockfile is `pnpm-lock.yaml`; do not use `npm install`, it cannot read the pnpm layout), Python 3.13 + [uv](https://docs.astral.sh/uv/), Chrome (browser E2E), and for live AI a Google service-account key with Vertex AI access.

## 2. Ports, databases and health checks

| Component | Port | Health |
|---|---|---|
| Frontend (Vite, proxies `/api`→8080 and `/ai-api`→8090) | 3000 | `GET http://localhost:3000/` |
| Java backend | 8080 | `GET /actuator/health` |
| AI service | 8090 | `GET /health` (liveness only), `GET /health/dependencies` (DB, Tika, LLM breaker state; never calls the model) |
| PostgreSQL (`pgvector/pgvector:pg16`) | 5432 | `docker exec dsports-postgres pg_isready -U dsports` |
| Tika + Tesseract | 9998 | `GET /tika` |

Databases in the one Postgres: `dsports` (commerce, owned by Java/Flyway), `dsports_ai` (AI service: vectors, conversations, usage, Inventory Intelligence `inv_*`, Engineering AI `eng_*`), `dsports_ai_test` (throwaway, for integration tests: its tables are dropped by tests).

## 3. Configuration

Never commit secrets: `ai-service/.env` is git-ignored. Copy `ai-service/.env.example` and fill:

| Variable | Notes |
|---|---|
| `JWT_SECRET` | ≥ 32 bytes; **must be identical** for the backend and the AI service, otherwise every AI call returns 401 |
| `JWT_ISSUER`, `JWT_AUDIENCE` | default `dsports` and `dsports-api`; tokens must carry both and both services must agree |
| `AI_ENV` | `local` (default), `test`, `dev` keep `/metrics` and the API docs open; any other value (for example `prod`) closes the docs and protects `/metrics` (`AI_METRICS_TOKEN` or an administrator token) |
| `PLANNING_*`, `SOURCING_*` | planning and sourcing behaviour (week start, horizon, allow-listed hosts, research providers, exchange rates); names and comments in `ai-service/.env.example` |
| `AI_CUSTOMER_HASH_KEY` | ≥ 16 bytes |
| `AI_DATABASE_URL` | DSN of `dsports_ai` |
| `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT` | Vertex AI access (live AI only) |
| `DB_NAME`, `DB_PASSWORD` (backend) | defaults in `application-local.yml` are local-development defaults only; `application-prod.yml` has none |
| `RATE_LIMIT_ENABLED`, `RATE_LIMIT_AUTH_PER_MINUTE` (default 20), `RATE_LIMIT_TICKETS_PER_MINUTE` (default 10) | Java rate limiter (in-memory, per instance; see [SECURITY.md](SECURITY.md)). The `local` profile raises both limits to 100000 so test harnesses are not blocked; set them low to demonstrate a 429 |
| `AI_RECO_*` | recommendation budget rules (see [management-feedback](management-feedback/README.md)); `ENG_BACKEND_LOG`, `ENG_AI_LOG` for Engineering AI log paths |

Full AI variable table: [ai/12-local-development.md](ai/12-local-development.md).

## 4. Start (order matters) and stop

```bash
docker compose up -d postgres && docker compose --profile ai up -d tika
bash e2e/ai/start-backend.sh      # stops only THIS project's backend, starts the jar detached, waits for health; Flyway applies new migrations forward
bash e2e/ai/start-ai.sh           # same for the AI service (creates/updates its tables idempotently)
cd frontend && pnpm install && pnpm exec vite     # or: pnpm dev
```

Build the jar first with `cd backend && mvn -B -o clean install -DskipTests` (stop the backend before rebuilding: Windows locks the running jar).
On Windows run the `.sh` scripts with Git Bash (`& 'C:\Program Files\Git\bin\bash.exe' e2e/ai/start-ai.sh`). Stop: `bash e2e/ai/free-port.sh 8080` (8090, 3000): it only stops processes whose command line belongs to this project and refuses to touch another project's process.

**Migrations**: Flyway is forward-only and runs at backend start (current head V56, confirmed against `flyway_schema_history`). Never drop `dsports`; for destructive experiments use a throwaway database (the consolidated validation creates `dsports_e2e_val`).

## 5. Seed data

```bash
cd ai-service
uv run python -m app.cli index-products        # product embeddings
uv run python -m app.cli ingest-corpus         # sample knowledge base (SYNTHETIC demo documents, labelled as such)
uv run python -m app.support.cli ingest-corpus # support policy/procedure documents (SYNTHETIC demo content, not an official SLA)
uv run python -m app.inventory.cli seed && uv run python -m app.inventory.cli train   # POC SYNTHETIC history + forecast
uv run python -m app.engineering.cli ingest --service backend     # real backend log -> Engineering AI
uv run python -m app.engineering.cli synthetic                    # the labelled synthetic incident
```

Planning and sourcing (as an `ADMIN` token; both are idempotent and demonstration rows are labelled):

```text
POST /v1/planning/ingest                 # live online orders from the Java sales feed
POST /v1/planning/demo/seed              # labelled SYNTHETIC store network and history (optional)
POST /v1/planning/forecast/runs          # about 20 s
POST /v1/sourcing/runs                   # research run through the allow-listed provider
POST /v1/sourcing/demo/seed              # labelled DEMO records (optional)
```
Roles for these screens (`SALES_PLANNER`, `CATEGORY_MANAGER`, `REGIONAL_MANAGER`, `STORE_MANAGER`, `PROCUREMENT_BUYER`) are granted by SQL like the others; scoped managers also need a row in `pln_scope` (`POST /v1/planning/scope`).

Privileged roles are **not** assignable through the general API (only a domain method exists). Grant them by SQL on purpose:
`INSERT INTO customer_roles (customer_id, role) SELECT id,'ADMIN' FROM customers WHERE email='…'` (roles used: `ADMIN`, `SUPPORT_EXECUTIVE`). **The first administrator is created this way, once.** The one exception is `GROUND_OWNER`: a signed-in administrator gives and removes it in the app (*Admin → Ground owners*, `/api/v1/admin/ground-owners`), by email, for someone who has already registered; every change is audited, and an administrator cannot grant it to themselves. No SQL is needed for it. The V48 migration needs the PostgreSQL `btree_gist` extension: it is available in the project's Docker image; a managed database may need it enabled first.

## 6. Tests: exact commands

| Layer | Command | Needs |
|---|---|---|
| Java unit | `cd backend && mvn -B -o package` | nothing external |
| Java integration (Testcontainers) | `cd backend && mvn -B -o test -Pintegration-test -Dapi.version=1.44` with `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` on Windows | Docker |
| Python unit | `cd ai-service && uv run pytest -q` (integration and live tests are deselected by default) | nothing external (fake DSports, fake LLM) |
| Python integration | `AI_TEST_DATABASE_URL=…/dsports_ai_test SOURCING_TEST_DATABASE_URL=…/dsports_ai_src_test PLANNING_TEST_DATABASE_URL=…/dsports_ai_pln_test uv run pytest -m integration` (each database must exist and is **throwaway**) | Postgres |
| Python live (real Vertex) | `AI_RUN_LIVE_TESTS=1 AI_TEST_DATABASE_URL=… uv run pytest -m live` | stack up + Gemini quota |
| Frontend | `cd frontend && pnpm exec tsc -b && pnpm lint && pnpm exec vitest run && pnpm build` | nothing external (API mocked per test) |
| Real-stack API/browser E2E | `bash e2e/api-e2e.sh`, `uv run --project ai-service python e2e/ai/mgmt_feedback_e2e.py`, `uv run --project ai-service python e2e/ai/engineering_e2e.py`, `cd e2e && node mgmt-feedback-ui.mjs ../scratch_shots` (also `ui-flow.mjs`, `support-ui.mjs`, `inventory-ui.mjs`; planning, sourcing and security: `python e2e/ai/planning_e2e.py`, `planning_audit.py`, `sourcing_e2e.py`, `security_e2e.py`, and `node e2e/planning-ui.mjs` / `sourcing-ui.mjs <world.json> scratch_shots` after `planning_data.py` / `sourcing_data.py > world.json`) | full stack |
| Everything, with honest PASS/FAIL/BLOCKED/SKIP | `bash e2e/validate-all.sh [--skip-build] [--only <prefix>]` → `e2e/validation-results/<timestamp>/SUMMARY.md` (~1 hour) | full stack + Docker + Gemini |

**Evaluation integrity**: `ai-service/eval/` separates development sets, regression runs and held-out sets. The first run of a held-out set is preserved in `eval/results/` and labelled; later runs are regression. Never edit a held-out expectation to change a score (see [ai/phase3/10-evaluation.md](ai/phase3/10-evaluation.md)). Mocks/synthetic data: Python unit tests use a fake DSports and a scripted LLM; integration tests use a real Postgres but fake LLM; live tests and E2E use real services; Inventory data is POC SYNTHETIC; the Engineering AI incident is SYNTHETIC and labelled.

## 7. Demo runbook

URLs are the local defaults above. Customers register at `/register`; roles are granted as in §5.

1. **Customer shopping and recommendations** (customer): open `http://localhost:3000/products/00000000-0000-0000-0000-000000001070` (Head Tennis Racquet, ≈₹10,499). The "Recommended with…" block shows *Goes with this*: only in-stock tennis items at or below 20 % of the price (₹2,099.80) with the rule stated. Use *Adjust budget* (₹500 is applied as given and never widened) and *Show premium alternatives* (on a bat product). Add a recommended item to the cart.
2. **Customer AI**: header button "Ask DSports AI": "show lightweight cricket bats under ₹8000", then "what goes with this bat" after choosing a product. Account questions require sign-in.
3. **Support and returns**: `/support` → *Raise a ticket* (review step, then submit). In the assistant: "My order has not arrived" → investigation → a proposal that creates a ticket **only after you confirm**. Staff (`SUPPORT_EXECUTIVE` or `ADMIN`): `/support-cases` → search by ticket number, take it, change priority (reason required), reply, internal note; customer view never shows the note.
4. **Profiles and budget controls**: `/profile` → pick Tennis and a price range, add a family member (age band only), switch the active profile; the home page "Picked for you" follows the active profile and disappears when nothing relevant exists.
5. **Inventory Intelligence** (`ADMIN`): `/inventory-ai`. All data is labelled POC SYNTHETIC; ask the copilot "which products will stock out in 14 days?" and open the evidence behind each answer.
6. **Engineering AI** (`ADMIN`): `python -m app.engineering.cli synthetic`, then `/engineering-ai`, choose "Synthetic only", ask "Why did checkout fail?": clusters, a deployment-timing hypothesis (marked not confirmed), evidence records L<id> with correlation ids, synthetic labels, and a withheld injected log line. Choose "Real only" and ask "Show recurring errors in the backend in the last 3 days" for the real recurring warning.

7a. **Court booking** (customer, venue owner and administrator, all payments use the demonstration gateway). Needs one administrator, created once by SQL (see section 5).

   1. Register three accounts at `/register`: an owner, a customer and (once) an administrator; give the administrator the `ADMIN` role by the SQL in section 5 and sign in as them.
   2. **Admin → Ground owners**: enter an email nobody registered with to see "Ask them to register first"; then enter the owner's email and press *Make ground owner*. The audit log lists it. Try your own email: it is refused. (No SQL for this step.)
   3. The owner signs in again (the role is read from the token) and opens **Owner console**. *New venue*: for example Chennai, 06:00 to 22:00; then *Add court* twice: "Court 1" offering Pickleball and Cricket (60 minute slots, base 800), and "Hall" offering Badminton.
   4. As the customer open **Courts**: choose a sport (only bookable sports are offered), type the city in any case, pick a day at least a day ahead, open the venue, choose a court and the sport, select one or two consecutive slots and press *Hold and pay*. The same time on the other court stays free.
   5. On the payment page watch the 10 minute countdown, pay, and see the booking confirmed in `/bookings` (venue, court and sport shown) and the notification bell.
   6. Cancel it from `/bookings`: the page shows the refund share and that a refund was **requested**; approve it as an administrator (`POST /api/v1/admin/refunds/{id}/approve`).
   7. Book another slot, then as the owner cancel it from the bookings list: the full refund is paid out **automatically** and the customer is notified.
   8. Let a hold run out without paying: after the window the slot becomes free again (the expiry job runs every 15 seconds).
   9. As the owner add a blackout for the whole venue or for one court; adding one over a booking is refused.
   10. **Owner removal:** as the administrator remove the owner's role (Ground owners → *Remove role*). The venue disappears from the customer's search and its page says it takes no new bookings; an existing booking is still valid. Open **Admin → Venues**: the venue is flagged "owner removed"; *Reassign* it to a second owner (grant that role first) and it is bookable again. The removed owner's current sign-in keeps the role for up to 15 minutes (ADR-018).
   11. **Admin → Sports**: add a sport and leave *Bookable* on: it appears in the booking list but not in the shop until it has products.
   12. The same journey runs in a real browser with `node e2e/venues-ui.mjs [shotDir]` (backend on 8080, Vite on 3000); it removes what it created.

7b. **Scoring a match on a real phone** (about 15 minutes; proves the scorer screen over a local network, a wicket, an undo and a ball sent from airplane mode). Needs the stack running (section 4) and a phone on the same Wi-Fi as the computer.

   **Get the phone to the app**
   1. Start Vite so the phone can reach it: `cd frontend && pnpm exec vite --host` (it prints a *Network* address such as `http://192.168.1.20:3000`). The phone talks only to Vite; Vite forwards `/api` to the backend, so no other port needs opening.
   2. Allow it through the firewall once (Windows asks the first time: choose *Private networks*; or run as administrator: `New-NetFirewallRule -DisplayName "Vite dev" -Direction Inbound -Protocol TCP -LocalPort 3000 -Action Allow -Profile Private`).
   3. Open the Network address in the phone's browser. The shop home page must load. (The page is plain `http`, so the phone has no `crypto.randomUUID`; the scorer screen has a fallback, and this test is what proves it.)

   **Set up a small match** (on the computer or the phone)
   4. Register and sign in as the organiser. **My matches → New match**. Use the *New team (names only)* button twice: "Lions" with `Arun`, `Bala`, `Chetan`, `Dev` and "Tigers" with `Tarun`, `Uday`, `Vik`, `Wasim`. Overs per side 2, tennis ball, *Players per side* 4. Create it.
   5. On the setup page record the toss (Lions won, chose to bat), pick all four of each side as the playing XI in the order given, and save both. The *Start scoring* button appears.

   **Score two overs on the phone** (sign in as the organiser on the phone, **My matches →** the match **→ Start scoring**)
   6. *Start innings*: openers Arun (striker) and Bala, bowler Tarun.
   7. **Over 1.** Tap `1`, `4`, `0`. Then `W` → *Bowled* → *Confirm*. The "Who bats next?" sheet blocks everything else: choose Chetan (he is on strike). Tap `3` by mistake, then **Undo** (the button reads "Undo: 3 runs"): the score goes back to 5/1 and the 3 disappears. Tap `2`, then `1`. The over is complete: "Who bowls the next over?" shows Tarun greyed ("bowled the last over"); choose Uday.
   8. **Over 2.** Tap `1`, `0`. Now turn on **airplane mode**. Tap `1`: the screen says "Offline: scoring continues, saved on this phone", shows "1 unsent" and a banner "1 change is waiting on this phone and not on the server yet"; the buttons keep working and the run is already in the phone's score (dashed). Turn airplane mode **off**. Within a few seconds the banner disappears by itself (or press *Retry now*) and the run is on the server. Then tap `6`, `2`, `0`: the innings ends at **Lions 18/1 (2.0 ov)** and the screen shows the innings break and "Tigers need 19 to win".

   **Follow it on the laptop, signed out** (do this alongside steps 6 to 8)
   A. Copy the match id from the scorer page's address (`/matches/<id>/score`). In a private window of the laptop's browser (so you are not signed in) open `http://<laptop address>:3000/m/<id>`. Before the first ball it says "This match has not started yet" and checks again every 10 seconds.
   B. After *Start innings* the page shows **Lions 0/0** within about 10 seconds; after that every ball appears within about 4 seconds: runs, overs, run rate, the batters and bowler. The wicket shows the batter with "b Tarun" and the fall of wickets; the undo takes the 3 off again. The airplane-mode ball does **not** appear while the phone is offline and appears a few seconds after it reconnects.
   C. The laptop must never ask for a sign-in. `/live` ("Live cricket" in the header) lists the match with its score; typing the city (if you set one) filters it in any letter case. On the phone, *Copy link* (it works over plain http) and *Share on WhatsApp* give the same `/m/<id>` link.
   D. Optional: open the browser's developer tools on the laptop page, Network tab, and filter on `scorecard`: most requests are `304` with no body, and none carries an `Authorization` header. `http://<laptop address>:3000/api/v1/matches/<id>` (the setup route) answers 401 without a sign-in.

   **Check it** (on the computer)
   9. Open the same match in the computer's browser: the scorer screen shows **Lions 18/1 (2.0 ov)** and the scorecard: Arun 2 (3) not out, Bala 4 (3) b Tarun, Chetan 12 (6) not out, extras 0; Tarun 1.0-0-8-1, Uday 1.0-0-10-0. (Arun 2 + Bala 4 + Chetan 12 = 18; Tarun 8 + Uday 10 = 18.)
   10. The log must show the airplane ball **once**. With the database up: `docker exec dsports-postgres psql -U dsports -d dsports -c "select seq, type from ball_events where match_id = '<match id from the address bar>' order by seq"`. Expect 17 rows, seq 1 to 17 with no gap: `START_INNINGS`, three `DELIVER`, `DELIVER` (the wicket), `SELECT_BATTER`, `DELIVER` (the mistaken 3), `UNDO`, two `DELIVER`, `SELECT_BOWLER`, then six `DELIVER`. The undone 3 is still in the log (an undo voids, it never deletes) and the screen's "Latest" list says "Undid: 3 runs".

   **Optional extras.** The full offline drill is script 7c below.

7c. **Scoring several balls in airplane mode on a real phone** (about 25 minutes; extends 7b: balls scored with no signal are saved on the phone, survive the browser being closed, and arrive once each, in order, when the network returns). Needs the same set-up as 7b: the match with Lions (`Arun`, `Bala`, `Chetan`, `Dev`) and Tigers (`Tarun`, `Uday`, `Vik`, `Wasim`), 2 overs, 4 a side, the stack running, Vite with `--host`, the phone on the same Wi-Fi, and the laptop signed out on `/m/<match id>`. The balls below are the data in `frontend/src/features/cricket/offline/phoneScript7c.ts`; a test plays them through the engine and checks the numbers in this section.

   1. **Online.** Sign in as the organiser on the phone, open the match's scorer page. Under the title it says "Keep this page open while scoring offline." Press *Start innings* (Arun and Bala open, Tarun bowls). Tap `1`, `4`, `•`. Tap `W`, *Bowled*, *Confirm*; in "Who bats next?" choose Chetan. The phone and the laptop both say **Lions 5/1**.
   2. **Airplane mode on.** The phone shows "Offline: scoring continues, saved on this phone". Score these **13 changes** (each tap is one change; the sheets that open for WD and W are not): `3` (by mistake), **Undo** ("Undo: 3 runs"), `2`, `1` (the over is complete), choose **Uday** to bowl, `WD` then *Wd*, `1`, `W` *Bowled* *Confirm*, choose **Dev**, `6`, `•`, `2`, `1`. The phone's score reads **Lions 19/2 (2.0 ov)** and the screen shows the innings break and "Tigers need 20 to win". The chip says "13 unsent", the scoreboard has a dashed border, and "Not on the server yet" lists the balls.
   3. **Check nothing moved.** The laptop still says **Lions 5/1**. In the database `select count(*) from ball_events where match_id = '<id>'` says 6.
   4. **Kill the browser.** From the phone's app switcher close the browser completely. Turn airplane mode **off**. Open the browser and the scorer page again. The "13 unsent" chip is there (or already gone if it has sent), and within a minute it sends by itself. The laptop follows: **Lions 19/2 (2.0 ov)**.
   5. **Check the log.** `select seq, type from ball_events where match_id = '<id>' order by seq` gives **19 rows**, seq 1 to 19 with no gap, the mistaken `3` and its `UNDO` each present once (the screen's "Latest" list says "Undid: 3 runs"), and `select count(distinct client_event_id) from ball_events where match_id = '<id>'` says 19. The scorecard on the laptop: Arun 1 (2), Bala 4 (3), Chetan 4 (3), Dev 9 (4), extras 1 (the wide); Tarun 1.0-0-8-1, Uday 1.0-0-11-1. (1 + 4 + 4 + 9 + 1 extra = 19; 8 + 11 = 19.)
   6. **Conflict drill.** Airplane mode on. Tap three balls (for example start the second innings: openers Tarun and Uday, bowler Arun, then `1`, `4`). On the laptop sign in as the organiser in a second window and score **two** balls on the same match. Turn airplane mode off. The phone shows "Someone else scored while you were away", lists their 2 changes and your unsent ones, and a preview ("All of yours can be added after theirs"). Choose **Add mine after theirs**. The totals are theirs plus yours, and the log (`select seq, type ...`) has no gap and no id twice.
   7. **Report** the phone model, the browser, and whether *Copy or download unsent balls* worked (on plain http the text is shown to select by hand, and a download is offered).

   The same drill runs in a real browser with `node e2e/cricket-offline.mjs [shotDir]` (backend on 8080, Vite on 3000): it uses an emulated phone (airplane mode is `setOffline`), cleans up after itself, and refuses to run against anything but the local test database. It is **not** a real phone: only this script proves a phone's own browser keeps the saved balls when closed.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Every AI call 401 | `JWT_SECRET` differs between backend and `ai-service/.env`: export it from `.env` before `java -jar` (the start scripts do) |
| `npm install` fails with `Invalid Version` | the frontend is a pnpm project: use `pnpm install` |
| Jar cannot be rebuilt ("Unable to rename") | stop the backend first (`bash e2e/ai/free-port.sh 8080`) |
| 29 Testcontainers tests silently skipped | set `DOCKER_HOST` and `-Dapi.version=1.44` |
| Vite serves stale/empty files after external edits | restart Vite |
| Live AI steps BLOCKED / `LLM_QUOTA` | Gemini quota or billing: record as BLOCKED, do not retry in a loop |
| Engineering AI shows no sources | run the `ingest` commands in §5; the AI log file is overwritten at each service restart |
| Port held by another project | the scripts report it and refuse to kill it; use another port |
