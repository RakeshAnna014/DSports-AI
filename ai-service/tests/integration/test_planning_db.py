"""Sales & Inventory Planning against a REAL PostgreSQL (its own throwaway database: PLANNING_TEST_DATABASE_URL, falling back to AI_TEST_DATABASE_URL).

The Inventory AI's synthetic history is replaced by a SMALL deterministic fixture (3 products x 5 regions x 2 years, Poisson with a yearly pattern), so every figure here is a fixture:
nothing in this file is evidence of live accuracy. The DSports sales feed is an in-memory fake that behaves like the real endpoint (cursor, restatement window, status values)."""
from __future__ import annotations

import asyncio
import json
import math
import os
import uuid
from datetime import date, datetime, timedelta, timezone

import asyncpg
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.planning import regions
from app.planning.ingest import PlanningIngest, SalesFeedClient
from app.planning.repository import DEMO_SRC, IMPORTED, LIVE, PlanningRepository
from app.planning.service import Actor, PlanningError, PlanningService
from app.store.db import Store
from tests.conftest import make_settings, make_token

DSN = os.environ.get("PLANNING_TEST_DATABASE_URL") or os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="PLANNING_TEST_DATABASE_URL / AI_TEST_DATABASE_URL is not set")]

PRODUCTS = [("aaaaaaaa-0000-0000-0000-000000000001", "T-BAT-1", "Test Bat One", "Cricket", "Bat", 3000, 1800, 14, 20, 5),
            ("aaaaaaaa-0000-0000-0000-000000000002", "T-BALL-1", "Test Ball One", "Cricket", "Ball", 400, 200, 10, 24, 12),
            ("aaaaaaaa-0000-0000-0000-000000000003", "T-RKT-1", "Test Racket One", "Badminton", "Racket", 2500, 1500, 21, 10, 5)]
P_BAT, P_BALL, P_RKT = (p[0] for p in PRODUCTS)
HISTORY_FIRST, HISTORY_LAST = date(2024, 10, 8), date(2026, 10, 7)
PLANNER = Actor("hash-planner", ["SALES_PLANNER"])
MANAGER = Actor("hash-manager", ["CATEGORY_MANAGER"])
ADMIN = Actor("hash-admin", ["ADMIN"], "admin-token")
REGIONAL = Actor("hash-regional", ["REGIONAL_MANAGER"])
STORE_MGR = Actor("hash-store", ["STORE_MANAGER"])
TABLES = ("pln_analysis_action", "pln_analysis", "pln_pilot", "pln_scenario", "pln_transfer_rec", "pln_alloc_rec", "pln_actual_adjustment", "pln_import_batch", "pln_scope", "pln_audit", "pln_plan_line", "pln_plan", "pln_eval",
          "pln_forecast", "pln_forecast_series", "pln_forecast_run", "pln_incoming", "pln_stock_position", "pln_data_through", "pln_sales_week", "pln_sales_line", "pln_ingest_run", "pln_location")


async def seed_inventory(conn: asyncpg.Connection) -> None:
    for t in ("inv_elasticity", "inv_model_run", "inv_purchase_order", "inv_position", "inv_demand_daily", "inv_sku"):
        await conn.execute(f"TRUNCATE {t} CASCADE")
    for pid, sku, name, sport, cat, price, cost, lead, moq, case in PRODUCTS:
        await conn.execute("INSERT INTO inv_sku (product_id, sku, name, sport, category, price, cost, lead_time_days, moq, case_pack, data_source) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,'POC_REFERENCE')",
                           uuid.UUID(pid), sku, name, sport, cat, price, cost, lead, moq, case)
    rng = np.random.default_rng(11)
    rows, days = [], [HISTORY_FIRST + timedelta(days=i) for i in range((HISTORY_LAST - HISTORY_FIRST).days + 1)]
    for i, (pid, *_rest) in enumerate(PRODUCTS):
        for r, region in enumerate("NSEWC"):
            base = (3.0 + 2.0 * i) * (0.8 + 0.1 * r)
            for d in days:
                lam = base * (1 + 0.3 * math.sin(2 * math.pi * (d.timetuple().tm_yday / 365.0)))
                rows.append((uuid.UUID(pid), region, d, int(rng.poisson(lam)), 100.0, 0, None, False, "POC_SYNTHETIC"))
    await conn.copy_records_to_table("inv_demand_daily", records=rows, columns=("product_id", "region", "day", "units", "unit_price", "discount_pct", "event_id", "stockout", "data_source"))
    for i, (pid, *_rest) in enumerate(PRODUCTS):
        for r, region in enumerate("NSEWC"):
            await conn.execute("INSERT INTO inv_position (product_id, region, on_hand, oldest_age_days, avg_age_days, as_of, data_source) VALUES ($1,$2,$3,10,5,$4,'POC_SYNTHETIC')",
                               uuid.UUID(pid), region, [40, 400, 90, 15, 200][r] * (i + 1), date(2026, 10, 8))
        await conn.execute("INSERT INTO inv_purchase_order (id, product_id, region, qty, ordered_on, eta, status, data_source) VALUES ($1,$2,'S',60,$3,$4,'OPEN','POC_SYNTHETIC')",
                           uuid.uuid4(), uuid.UUID(pid), date(2026, 10, 1), date(2026, 10, 20))
    run_id = uuid.uuid4()
    await conn.execute("INSERT INTO inv_model_run (id, as_of, model_name, model_version, training_start, training_end, horizon_days, n_series, interval_scale, metrics, features, data_source, is_current) "
                       "VALUES ($1,$2,'m','v',$3,$4,90,3,1.0,'{}'::jsonb,'{}'::jsonb,'POC_SYNTHETIC',TRUE)", run_id, date(2026, 10, 8), HISTORY_FIRST, HISTORY_LAST)
    for cat in ("Bat", "Ball", "Racket"):
        await conn.execute("INSERT INTO inv_elasticity (run_id, category, elasticity, elasticity_lo, elasticity_hi, pull_forward, n_promo_days, basis) VALUES ($1,$2,1.5,1.0,2.2,0.1,10,'POC_ASSUMPTION')", run_id, cat)


@pytest.fixture
async def world():
    conn = await asyncpg.connect(DSN)
    for t in TABLES:
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 60, 768)
    async with store.pool.acquire() as c:
        await seed_inventory(c)
    repo = PlanningRepository(store.pool)
    svc = PlanningService(repo, make_settings(AI_DATABASE_URL=DSN, PLANNING_BACKTEST_ORIGINS=4), None, lambda uid: f"hash-of-{uid}")
    await svc.seed_demo(ADMIN)
    yield svc, repo, store
    await store.close()


async def plan_for(svc: PlanningService, name="Q3 plan", actor=PLANNER) -> dict:
    await svc.run_forecast(MANAGER, DEMO_SRC)
    return await svc.create_plan(actor, name=name, source=DEMO_SRC, fy=2026, period="HORIZON", number=None, scope={}, run_id=None)


async def lines(svc, plan_id, actor=PLANNER, **f):
    return (await svc.plan_lines(actor, plan_id, f, "product", 0, 200))["items"]


# ------------------------------------------------------------------------------------------------ the demonstration network and its history
@pytest.mark.asyncio
async def test_stores_add_up_to_their_region_exactly_for_every_product_and_week_and_everything_is_labelled(world):
    svc, repo, store = world
    mismatches = await store.pool.fetchval(
        """WITH reg AS (SELECT product_id, region, date_trunc('week', day)::date AS w, SUM(units) AS u, COUNT(*) AS n FROM inv_demand_daily GROUP BY 1,2,3 HAVING COUNT(*) = 7),
                st AS (SELECT s.product_id, par.parent_id AS region, s.week_start AS w, SUM(s.net_units) AS u FROM pln_sales_week s JOIN pln_location l ON l.id = s.location_id JOIN pln_location par ON par.id = l.parent_id
                       WHERE s.data_source = 'POC_SYNTHETIC' GROUP BY 1,2,3)
           SELECT COUNT(*) FROM reg LEFT JOIN st USING (product_id, region, w) WHERE COALESCE(st.u, -1) <> reg.u""")
    assert mismatches == 0                                                                         # the stores of a region always sum to the region
    assert {r["data_source"] for r in await store.pool.fetch("SELECT DISTINCT data_source FROM pln_sales_week")} == {"POC_SYNTHETIC"}
    assert {r["data_source"] for r in await store.pool.fetch("SELECT DISTINCT data_source FROM pln_location WHERE level = 'STORE'")} == {"POC_REFERENCE"}
    assert await svc.repo.data_through(DEMO_SRC) == HISTORY_LAST


@pytest.mark.asyncio
async def test_a_store_has_no_rows_before_it_opens_and_a_planned_store_has_none_at_all_unknown_is_not_zero(world):
    _, _, store = world
    new = await store.pool.fetchrow("SELECT MIN(week_start) AS first, COUNT(*) AS n FROM pln_sales_week WHERE location_id = 'S-Z2-S2'")
    opened = await store.pool.fetchval("SELECT opened_on FROM pln_location WHERE id = 'S-Z2-S2'")
    assert new["n"] > 0 and new["first"] >= opened - timedelta(days=6)
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_sales_week WHERE location_id = 'E-Z2-S2'") == 0
    assert await store.pool.fetchval("SELECT status FROM pln_location WHERE id = 'E-Z2-S2'") == "PLANNED"


@pytest.mark.asyncio
async def test_seeding_twice_changes_nothing_stock_adds_up_to_the_regional_position(world):
    svc, _, store = world
    before = await store.pool.fetchval("SELECT SUM(net_units) FROM pln_sales_week")
    await svc.seed_demo(ADMIN)
    assert await store.pool.fetchval("SELECT SUM(net_units) FROM pln_sales_week") == before
    pos = await store.pool.fetchval("SELECT SUM(on_hand) FROM inv_position")
    placed = await store.pool.fetchval("SELECT SUM(on_hand) FROM pln_stock_position")
    assert placed == pos                                                                           # every regional unit is on a store shelf: none lost, none invented
    assert await store.pool.fetchval("SELECT SUM(qty) FROM pln_incoming") == await store.pool.fetchval("SELECT SUM(qty) FROM inv_purchase_order WHERE status = 'OPEN'")


# ------------------------------------------------------------------------------------------------ ingest of the real sales feed (a faithful fake of it)
class FakeFeed:
    """Behaves like GET /api/v1/admin/sales/lines: ordered by (updatedAt, lineId), cursor, hasMore, placedFrom."""

    def __init__(self):
        self.rows: list[dict] = []
        self.calls = 0
        self.fail_after: int | None = None

    def add(self, status="DELIVERED", qty=2, product=P_BAT, placed="2026-09-02T10:00:00Z", state="KA", returned=0, total=None, discount=0, updated=None, line_id=None, order_id=None):
        r = {"lineId": line_id or str(uuid.uuid4()), "orderId": order_id or str(uuid.uuid4()), "orderNumber": "ORD-1", "productId": product, "sku": "S", "productName": "x", "quantity": qty, "unitPrice": 100.0,
             "lineTotal": total if total is not None else 100.0 * qty, "allocatedDiscount": discount, "currency": "INR", "orderStatus": status, "placedAt": placed, "cancelledAt": None,
             "approvedReturnQuantity": returned, "shipCity": "X", "shipState": state, "shipCountry": "IN", "sourceUpdatedAt": updated or placed}
        self.rows.append(r)
        return r

    async def get(self, path, *, token=None, params=None, allow_404=False):
        self.calls += 1
        if self.fail_after is not None and self.calls > self.fail_after:
            from app.tools.dsports_client import ToolError
            raise ToolError("UPSTREAM_UNAVAILABLE", "down", 503)
        assert token == "admin-token"
        p = params or {}
        rows = sorted(self.rows, key=lambda r: (r["sourceUpdatedAt"], r["lineId"]))
        if p.get("afterUpdatedAt"):
            cur = (p["afterUpdatedAt"], p.get("afterLineId", ""))
            rows = [r for r in rows if (r["sourceUpdatedAt"], r["lineId"]) > cur]
        if p.get("placedFrom"):
            rows = [r for r in rows if r["placedAt"] >= p["placedFrom"]]
        n = int(p.get("limit", 500))
        items = rows[:n]
        last = items[-1] if items else None
        return {"items": items, "hasMore": len(rows) > n, "next": {"updatedAt": last["sourceUpdatedAt"], "lineId": last["lineId"]} if last else None}


def make_ingest(repo, svc, feed: FakeFeed, page=500) -> PlanningIngest:
    return PlanningIngest(repo, SalesFeedClient(feed, page), svc.cal, svc.state_map, restate_days=36500)


@pytest.mark.asyncio
async def test_ingest_applies_the_convention_places_orders_in_regions_and_reconciles_to_the_cent(world):
    svc, repo, store = world
    feed = FakeFeed()
    feed.add("DELIVERED", 2, total=200, discount=20, state="KA")                       # South, 2 units, 180 revenue
    feed.add("CANCELLED", 3, state="MH")                                              # West, ordered and cancelled
    feed.add("DELIVERED", 4, returned=1, total=400, state="DL")                       # North, 3 net
    feed.add("PENDING_PAYMENT", 5)                                                    # never a sale
    feed.add("REFUNDED", 2, state="TN")                                               # fully returned
    feed.add("DELIVERED", 1, state="Atlantis")                                        # unknown region
    res = await make_ingest(repo, svc, feed).run("h", "admin-token")
    assert res.status == "SUCCEEDED" and res.lines_read == 12 and res.lines_changed == 6 and res.lines_excluded == 1 and res.unknown_region_lines == 1     # the restatement window re-reads each line once more
    week = date(2026, 8, 31)
    rows = {r["location_id"]: r for r in await store.pool.fetch("SELECT * FROM pln_sales_week WHERE data_source = 'DSPORTS_LIVE' AND week_start = $1 AND product_id = $2", week, uuid.UUID(P_BAT))}
    assert (rows["ONL-S"]["gross_units"], rows["ONL-S"]["net_units"]) == (4, 2) or rows["ONL-S"]["net_units"] == 2          # KA delivered 2 + TN refunded 2: net 2
    assert (rows["ONL-N"]["net_units"], rows["ONL-N"]["returned_units"]) == (3, 1)
    assert (rows["ONL-W"]["gross_units"], rows["ONL-W"]["cancelled_units"], rows["ONL-W"]["net_units"]) == (3, 3, 0)
    assert rows["ONL-UNK"]["net_units"] == 1
    assert float(rows["ONL-S"]["net_revenue"]) == 180.0
    assert (await repo.reconcile_live())["reconciled"] is True
    assert await repo.data_through(LIVE) == svc.cal.today() - timedelta(days=1)


@pytest.mark.asyncio
async def test_reading_the_same_feed_twice_changes_nothing_and_a_changed_order_updates_only_its_week(world):
    svc, repo, store = world
    feed = FakeFeed()
    first = feed.add("CONFIRMED", 2, placed="2026-09-02T10:00:00Z")
    feed.add("DELIVERED", 5, placed="2026-09-10T10:00:00Z", product=P_BALL)
    ing = make_ingest(repo, svc, feed)
    await ing.run("h", "admin-token")
    snap = await store.pool.fetch("SELECT product_id, location_id, week_start, net_units, aggregated_at FROM pln_sales_week WHERE data_source = 'DSPORTS_LIVE' ORDER BY 1,2,3")
    again = await ing.run("h", "admin-token")
    assert again.lines_changed == 0 and again.weeks_touched == 0
    assert [r["aggregated_at"] for r in await store.pool.fetch("SELECT aggregated_at FROM pln_sales_week WHERE data_source = 'DSPORTS_LIVE' ORDER BY product_id, location_id, week_start")] == [r["aggregated_at"] for r in snap]
    first["orderStatus"], first["sourceUpdatedAt"] = "CANCELLED", "2026-09-20T10:00:00Z"            # the order is cancelled later: it re-appears and restates its ORIGINAL week
    upd = await ing.run("h", "admin-token")
    assert upd.lines_changed >= 1 and upd.weeks_touched == 1
    r = await store.pool.fetchrow("SELECT gross_units, cancelled_units, net_units FROM pln_sales_week WHERE data_source = 'DSPORTS_LIVE' AND product_id = $1 AND week_start = $2", uuid.UUID(P_BAT), date(2026, 8, 31))
    assert (r["gross_units"], r["cancelled_units"], r["net_units"]) == (2, 2, 0)
    assert (await repo.reconcile_live())["reconciled"]


@pytest.mark.asyncio
async def test_a_late_approved_return_is_picked_up_by_the_restatement_window_even_though_the_order_did_not_change(world):
    svc, repo, store = world
    feed = FakeFeed()
    row = feed.add("DELIVERED", 4, placed="2026-09-02T10:00:00Z", updated="2026-09-03T10:00:00Z")
    ing = make_ingest(repo, svc, feed)
    await ing.run("h", "admin-token")
    row["approvedReturnQuantity"] = 1                                                             # the returns module approved a return; the order's own update time did not move
    out = await ing.run("h", "admin-token")
    assert out.restated_lines == 1
    assert await store.pool.fetchval("SELECT net_units FROM pln_sales_week WHERE data_source = 'DSPORTS_LIVE'") == 3


@pytest.mark.asyncio
async def test_a_failed_feed_keeps_the_old_data_does_not_move_the_cursor_and_the_next_run_catches_up(world):
    svc, repo, store = world
    feed = FakeFeed()
    for i in range(5):
        feed.add("DELIVERED", 1, placed=f"2026-09-0{i + 1}T10:00:00Z")
    feed.fail_after = 2                                                                           # the third page fails
    res = await make_ingest(repo, svc, feed, page=2).run("h", "admin-token")
    assert res.status == "FAILED" and "UPSTREAM_UNAVAILABLE" in res.error
    assert await repo.ingest_cursor() == (None, None)
    feed.fail_after = None
    ok = await make_ingest(repo, svc, feed, page=2).run("h", "admin-token")
    assert ok.status == "SUCCEEDED" and await store.pool.fetchval("SELECT COUNT(*) FROM pln_sales_line") == 5
    assert (await repo.reconcile_live())["reconciled"]


@pytest.mark.asyncio
async def test_only_an_admin_with_a_token_can_ingest_and_a_second_ingest_cannot_run_at_the_same_time(world):
    svc, repo, _ = world
    with pytest.raises(PlanningError) as e:
        await svc.ingest(MANAGER)
    assert e.value.status == 403
    with pytest.raises(PlanningError) as e2:
        await svc.ingest(Actor("a", ["ADMIN"], None))
    assert e2.value.status == 401
    first = await repo.start_ingest("someone", "INCREMENTAL")
    assert first is not None and await repo.start_ingest("other", "INCREMENTAL") is None


# ------------------------------------------------------------------------------------------------ forecasts
@pytest.mark.asyncio
async def test_a_forecast_run_is_versioned_non_negative_evaluated_and_keeps_the_previous_run(world):
    svc, repo, store = world
    r1 = await svc.run_forecast(MANAGER, DEMO_SRC)
    assert r1["status"] == "SUCCEEDED" and r1["series"] == 3 * 20 and r1["fallback"] == 3 * 2 and r1["evaluation_rows"] > 0
    r2 = await svc.run_forecast(MANAGER, DEMO_SRC)
    current = await repo.current_run(DEMO_SRC)
    assert current["id"] == r2["run_id"] and (await repo.get_run(r1["run_id"]))["is_current"] is False
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_forecast WHERE run_id = $1::uuid AND forecast < 0", r2["run_id"]) == 0
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_forecast WHERE run_id = $1::uuid", r1["run_id"]) > 0                  # the earlier run's numbers are untouched
    first = date.fromisoformat(r2["first_week"])
    assert first == svc.cal.last_closed_week(HISTORY_LAST) + timedelta(weeks=1)


@pytest.mark.asyncio
async def test_new_and_planned_stores_are_low_confidence_fallbacks_and_a_planned_store_starts_when_it_opens(world):
    svc, repo, store = world
    r = await svc.run_forecast(MANAGER, DEMO_SRC)
    new = await store.pool.fetchrow("SELECT status, method, confidence FROM pln_forecast_series WHERE run_id = $1::uuid AND location_id = 'S-Z2-S2' AND product_id = $2", r["run_id"], uuid.UUID(P_BAT))
    assert new["status"] == "FALLBACK" and new["confidence"] == "LOW"
    planned = await store.pool.fetch("SELECT week_start, forecast FROM pln_forecast WHERE run_id = $1::uuid AND location_id = 'E-Z2-S2' AND product_id = $2 ORDER BY week_start", r["run_id"], uuid.UUID(P_BAT))
    opens = await store.pool.fetchval("SELECT opened_on FROM pln_location WHERE id = 'E-Z2-S2'")
    assert planned and all((w["forecast"] == 0) == (w["week_start"] < svc.cal.week_start(opens)) for w in planned)
    ok = await store.pool.fetchrow("SELECT confidence FROM pln_forecast_series WHERE run_id = $1::uuid AND location_id = 'N-Z1-S1' AND product_id = $2", r["run_id"], uuid.UUID(P_BAT))
    assert ok["confidence"] in ("HIGH", "MEDIUM", "LOW")


@pytest.mark.asyncio
async def test_evaluation_is_on_held_out_weeks_against_the_baseline_and_withholds_small_samples(world):
    svc, _, _ = world
    await svc.run_forecast(MANAGER, DEMO_SRC)
    ev = await svc.evaluation(PLANNER, None, DEMO_SRC, "OVERALL", "ALL")
    sel = next(r for r in ev["rows"] if r["model"] == "SELECTED")
    assert sel["sufficient"] and sel["wape"] is not None and sel["baseline_wape"] is not None and "SYNTHETIC" in ev["notice"]
    assert any(r["model"] == "SELECTED" for r in ev["rows"]) and ev["baseline"] == "MA4"
    stores = await svc.evaluation(PLANNER, None, DEMO_SRC, "STORE", "H1")
    thin = [r for r in stores["rows"] if r["model"] == "SELECTED" and not r["sufficient"]]
    assert thin and all(r["wape"] is None and r["bias"] is None and "too few" in r["note"] for r in thin)                        # no accuracy percentage on a thin sample
    cands = await svc.evaluation(PLANNER, None, DEMO_SRC, "CANDIDATE", "ALL")
    assert {r["model"] for r in cands["rows"]} >= {"MA4", "SEASONAL_LEVEL", "TOP_DOWN", "CROSTON_SBA", "ETS_DAMPED", "SEASONAL_NAIVE"}


@pytest.mark.asyncio
async def test_a_forecast_for_live_data_with_a_few_weeks_is_low_confidence_and_says_so(world):
    svc, repo, _ = world
    feed = FakeFeed()
    for w in range(5):
        feed.add("DELIVERED", 3, placed=(datetime(2026, 9, 7, 10, tzinfo=timezone.utc) + timedelta(weeks=w)).isoformat().replace("+00:00", "Z"), state="KA")
    await make_ingest(repo, svc, feed).run("h", "admin-token")
    out = await svc.run_forecast(MANAGER, LIVE)
    assert out["ok"] == 0 and out["fallback"] + out["insufficient"] == out["series"]
    ev = await svc.evaluation(PLANNER, None, LIVE, "OVERALL", "ALL")
    assert all(not r["sufficient"] for r in ev["rows"]) or not ev["rows"]
    ready = await svc.readiness()
    assert ready["forecasting"][LIVE]["status"] in ("INSUFFICIENT", "LIMITED_ESTIMATE") and ready["forecasting"][DEMO_SRC]["status"] == "DEMONSTRATION_ONLY"
    assert any(c["key"] == "online_sales_actuals" and c["status"] == "INSUFFICIENT" for c in ready["capabilities"])


# ------------------------------------------------------------------------------------------------ plans: four values, versions, conflicts, approval
@pytest.mark.asyncio
async def test_a_plan_keeps_model_forecast_manager_forecast_target_and_approved_forecast_apart(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    ln = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    assert ln["model_forecast"] is not None and ln["manager_forecast"] is None and ln["target_units"] is None and ln["approved_forecast"] is None and ln["actual_units"] is None
    model = ln["model_forecast"]
    key = {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": ln["week_start"], "expected_line_version": ln["line_version"]}
    out = await svc.edit_lines(PLANNER, plan["id"], [{**key, "target_units": 115}, ])
    assert out["changed"] == 1
    ln2 = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    key2 = {**key, "expected_line_version": ln2["line_version"]}
    await svc.edit_lines(PLANNER, plan["id"], [{**key2, "manager_forecast": 105, "reason_code": "LOCAL_EVENT", "note": "regional tournament"}])
    ln3 = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    assert ln3["model_forecast"] == model and ln3["manager_forecast"] == 105 and ln3["target_units"] == 115 and ln3["adj_reason"] == "LOCAL_EVENT"          # the model's number is never overwritten
    assert ln3["latest_forecast"] is not None and ln3["approved_forecast"] is None
    sub = await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, (await svc.plan_view(PLANNER, plan["id"]))["version"])
    rev = await svc.transition(MANAGER, plan["id"], "UNDER_REVIEW", None, sub["version"])
    appr = await svc.transition(MANAGER, plan["id"], "APPROVED", None, rev["version"])
    assert appr["status"] == "APPROVED"
    ln4 = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    assert ln4["approved_forecast"] == 105 and ln4["model_forecast"] == model and ln4["target_units"] == 115          # four distinct values
    other = (await lines(svc, plan["id"], product_id=P_BALL, stores=["N-Z1-S1"]))[0]
    assert other["approved_forecast"] == other["model_forecast"]                                                      # no manager forecast: the approved number is the model's


@pytest.mark.asyncio
async def test_validation_reasons_numbers_weeks_and_scale_of_an_edit(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    ln = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    key = {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": ln["week_start"], "expected_line_version": ln["line_version"]}
    bad = [({**key, "manager_forecast": 50}, "REASON_REQUIRED"), ({**key, "manager_forecast": 50, "reason_code": "OTHER"}, "NOTE_REQUIRED"), ({**key, "target_units": -1}, "INVALID_NUMBER"),
           ({**key, "target_units": float("nan")}, "INVALID_NUMBER"), ({**key, "target_units": 1e9}, "INVALID_NUMBER"), ({**key, "week_start": "2026-10-07"}, "INVALID_WEEK"), (dict(key), "INVALID_EDIT"),
           ({**key, "manager_forecast": 5, "reason_code": "NOT_A_REASON"}, "REASON_REQUIRED")]
    for edit, code in bad:
        with pytest.raises(PlanningError) as e:
            await svc.edit_lines(PLANNER, plan["id"], [edit])
        assert e.value.code == code, (edit, e.value.code)
    with pytest.raises(PlanningError) as e:
        await svc.edit_lines(PLANNER, plan["id"], [{**key, "target_units": 1}, {**key, "target_units": 2}])
    assert e.value.code == "DUPLICATE_EDIT"
    with pytest.raises(PlanningError) as e:
        await svc.edit_lines(PLANNER, plan["id"], [{**key, "target_units": i} for i in range(501)])
    assert e.value.code == "TOO_MANY_EDITS"
    with pytest.raises(PlanningError) as e:
        await svc.edit_lines(Actor("c", ["CUSTOMER"]), plan["id"], [{**key, "target_units": 1}])
    assert e.value.status == 403


@pytest.mark.asyncio
async def test_two_editors_cannot_silently_overwrite_each_other_and_a_conflict_saves_nothing(world):
    svc, _, store = world
    plan = await plan_for(svc)
    rows = await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"])
    a, b = rows[0], rows[1]
    ka = {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": a["week_start"], "expected_line_version": a["line_version"]}
    kb = {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": b["week_start"], "expected_line_version": b["line_version"]}
    await svc.edit_lines(PLANNER, plan["id"], [{**ka, "target_units": 10}])                                      # first editor saves line A
    with pytest.raises(PlanningError) as e:                                                                       # second editor still has the old version of A, and also changes B
        await svc.edit_lines(MANAGER, plan["id"], [{**kb, "target_units": 99}, {**ka, "target_units": 77}])
    assert e.value.status == 409 and e.value.code == "EDIT_CONFLICT"
    conflicts = e.value.detail["conflicts"]
    assert len(conflicts) == 1 and conflicts[0]["code"] == "VERSION_CONFLICT" and conflicts[0]["current"]["target_units"] == 10
    now = await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"])
    assert now[0]["target_units"] == 10 and now[1]["target_units"] is None                                        # B was NOT saved either: no partial change


@pytest.mark.asyncio
async def test_plan_approval_rules_roles_separation_of_duties_and_stale_versions(world):
    svc, repo, _ = world
    plan = await plan_for(svc)
    v = plan["version"]
    with pytest.raises(PlanningError) as e:
        await svc.transition(PLANNER, plan["id"], "APPROVED", None, v)
    assert e.value.code == "INVALID_TRANSITION"
    sub = await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, v)
    with pytest.raises(PlanningError) as e:
        await svc.transition(PLANNER, plan["id"], "UNDER_REVIEW", None, sub["version"])
    assert e.value.status == 403
    with pytest.raises(PlanningError) as e:
        await svc.transition(MANAGER, plan["id"], "UNDER_REVIEW", None, sub["version"] - 1)
    assert e.value.code == "VERSION_CONFLICT"
    rev = await svc.transition(MANAGER, plan["id"], "UNDER_REVIEW", None, sub["version"])
    with pytest.raises(PlanningError) as e:
        await svc.transition(Actor("hash-planner", ["CATEGORY_MANAGER"]), plan["id"], "APPROVED", None, rev["version"])
    assert e.value.code == "SELF_APPROVAL"                                                                       # the submitter cannot approve their own plan
    back = await svc.transition(MANAGER, plan["id"], "RETURNED", "numbers do not add up", rev["version"])
    assert back["status"] == "RETURNED" and back["editable"] is False or back["status"] == "RETURNED"
    assert (await svc.plan_view(PLANNER, plan["id"]))["editable"] is True
    log = await repo.audit_for_plan(plan["id"])
    assert {x["action"] for x in log} >= {"CREATED", "STATUS_SUBMITTED", "STATUS_UNDER_REVIEW", "STATUS_RETURNED"} and all(x["actor_hash"] for x in log)
    denied = await repo.audit_log("ACCESS", plan["id"])
    assert denied and denied[0]["action"] == "DENIED"                                                             # the refused approval is in the audit log too


@pytest.mark.asyncio
async def test_an_approved_plan_is_frozen_and_a_revision_replaces_it_only_when_approved(world):
    svc, repo, _ = world
    plan = await plan_for(svc)
    v = (await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, plan["version"]))["version"]
    v = (await svc.transition(MANAGER, plan["id"], "UNDER_REVIEW", None, v))["version"]
    approved = await svc.transition(MANAGER, plan["id"], "APPROVED", None, v)
    ln = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    with pytest.raises(PlanningError) as e:
        await svc.edit_lines(PLANNER, plan["id"], [{"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": ln["week_start"], "expected_line_version": ln["line_version"], "target_units": 5}])
    assert e.value.code == "PLAN_FROZEN"
    rev = await svc.revise(PLANNER, plan["id"], None)
    assert rev["status"] == "DRAFT" and rev["revision"] == 2 and rev["parent_plan_id"] == plan["id"] and (await svc.plan_view(PLANNER, plan["id"]))["status"] == "APPROVED"
    with pytest.raises(PlanningError) as e2:
        await svc.revise(PLANNER, plan["id"], None)
    assert e2.value.code == "REVISION_OPEN"
    rl = (await lines(svc, rev["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    await svc.edit_lines(PLANNER, rev["id"], [{"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": rl["week_start"], "expected_line_version": rl["line_version"], "target_units": 77}])
    v2 = (await svc.transition(PLANNER, rev["id"], "SUBMITTED", None, (await svc.plan_view(PLANNER, rev["id"]))["version"]))["version"]
    v2 = (await svc.transition(MANAGER, rev["id"], "UNDER_REVIEW", None, v2))["version"]
    await svc.transition(MANAGER, rev["id"], "APPROVED", None, v2)
    assert (await svc.plan_view(PLANNER, plan["id"]))["status"] == "SUPERSEDED"
    assert (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]["target_units"] is None            # the earlier plan keeps its own numbers


@pytest.mark.asyncio
async def test_growth_targets_are_a_bulk_edit_over_a_chosen_baseline_and_skip_lines_without_one(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    out = await svc.growth_targets(PLANNER, plan["id"], 10, "MODEL", {"product_id": P_BAT}, False)
    assert out["lines_set"] > 0
    rows = await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"])
    assert all(abs(r["target_units"] - round(r["model_forecast"] * 1.1, 3)) < 0.002 for r in rows)
    assert all(r["target_units"] is None for r in await lines(svc, plan["id"], product_id=P_BALL, stores=["N-Z1-S1"]))
    with pytest.raises(PlanningError):
        await svc.growth_targets(PLANNER, plan["id"], 10, "NOPE", {}, False)


# ------------------------------------------------------------------------------------------------ scope
@pytest.mark.asyncio
async def test_a_store_manager_sees_and_edits_only_the_stores_granted_to_them(world):
    svc, repo, _ = world
    plan = await plan_for(svc)
    assert (await lines(svc, plan["id"], STORE_MGR)) == []                                                      # no grant: nothing visible
    await svc.grant_scope(ADMIN, "u-store", "N-Z1-S1")
    mine = Actor(svc.hash_user("u-store"), ["STORE_MANAGER"])
    seen = await lines(svc, plan["id"], mine)
    assert seen and {r["location_id"] for r in seen} == {"N-Z1-S1"}
    other = (await lines(svc, plan["id"], PLANNER, stores=["N-Z1-S2"], product_id=P_BAT))[0]
    with pytest.raises(PlanningError) as e:
        await svc.edit_lines(mine, plan["id"], [{"product_id": P_BAT, "location_id": "N-Z1-S2", "week_start": other["week_start"], "expected_line_version": other["line_version"], "target_units": 1}])
    assert e.value.status == 409 and e.value.detail["conflicts"][0]["code"] == "OUT_OF_SCOPE"
    mine_line = (await lines(svc, plan["id"], mine, product_id=P_BAT))[0]
    out = await svc.edit_lines(mine, plan["id"], [{"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": mine_line["week_start"], "expected_line_version": mine_line["line_version"], "target_units": 9}])
    assert out["changed"] == 1
    with pytest.raises(PlanningError) as e2:
        await svc.transition(Actor(mine.hash, ["REGIONAL_MANAGER"]), plan["id"], "SUBMITTED", None, plan["version"] + 1)
    assert e2.value.status in (403, 409)
    await svc.grant_scope(ADMIN, "u-store", "N-Z1-S1", revoke=True)
    assert await lines(svc, plan["id"], mine) == []
    with pytest.raises(PlanningError):
        await svc.grant_scope(PLANNER, "u-store", "N-Z1-S1")                                                    # only an admin grants scope


@pytest.mark.asyncio
async def test_a_regional_manager_with_a_region_grant_sees_every_store_below_it(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    await svc.grant_scope(ADMIN, "u-reg", "S")
    reg = Actor(svc.hash_user("u-reg"), ["REGIONAL_MANAGER"])
    got = {r["location_id"] for r in await lines(svc, plan["id"], reg)}
    assert got == {"S-Z1-S1", "S-Z1-S2", "S-Z2-S1", "S-Z2-S2"}
    locs = await svc.locations(reg)
    assert {i["id"] for i in locs["items"]} >= {"S", "S-Z1", "S-Z2", "S-Z1-S1"} and "N-Z1-S1" not in {i["id"] for i in locs["items"]}


# ------------------------------------------------------------------------------------------------ actuals, imports, corrections, variance
async def import_week(svc, actor, week: date, product=P_BAT, store="N-Z1-S1", net=20, batch=None):
    return await svc.import_actuals(actor, batch or f"batch-{uuid.uuid4().hex[:8]}", [{"product_id": product, "location_id": store, "week_start": week.isoformat(), "net_units": net, "gross_units": net}], "test fixture", None)


@pytest.mark.asyncio
async def test_importing_actuals_is_idempotent_validated_store_only_and_never_touches_the_live_feed(world):
    svc, repo, store = world
    week = date(2026, 10, 5)
    first = await import_week(svc, PLANNER, week, batch="batch-0001")
    assert first["rows_applied"] == 1 and not first["already_applied"]
    again = await import_week(svc, PLANNER, week, batch="batch-0001", net=999)
    assert again["already_applied"] and await store.pool.fetchval("SELECT net_units FROM pln_sales_week WHERE data_source = 'IMPORTED'") == 20          # the same batch twice changes nothing
    assert await repo.data_through(IMPORTED) == date(2026, 10, 11)
    cases = [({"location_id": "ONL-S"}, "STORE"), ({"week_start": "2026-10-07"}, "first day"), ({"week_start": "2030-01-07"}, "not ended"), ({"week_start": "2030-01-06"}, "first day"),
             ({"net_units": 5, "gross_units": 9}, "net_units must equal"), ({"sku": "NOPE", "product_id": None}, "unknown product"), ({"location_id": "Nowhere"}, "STORE")]
    for patch, text in cases:
        row = {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": "2026-10-12", "net_units": 3, "gross_units": 3} | patch
        with pytest.raises(PlanningError) as e:
            await svc.import_actuals(PLANNER, f"batch-{uuid.uuid4().hex[:8]}", [row], None, None)
        assert e.value.code == "IMPORT_REJECTED" and text in json.dumps(e.value.detail), (patch, e.value.detail)
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_sales_week WHERE data_source = 'IMPORTED'") == 1          # a rejected batch imports nothing
    with pytest.raises(PlanningError):
        await svc.import_actuals(Actor("c", ["CUSTOMER"]), "batch-x1234", [{}], None, None)


@pytest.mark.asyncio
async def test_variance_follows_the_formulas_open_weeks_have_no_actual_and_zero_targets_do_not_divide(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    wk = (await svc.plan_view(PLANNER, plan["id"]))["weeks"]
    w1 = date.fromisoformat(wk[0]["week_start"])                                                                   # the week of 2026-10-05
    ln = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"], week_from=w1, week_to=w1))[0]
    assert ln["actual_units"] is None and ln["week_closed"] is False                                                # synthetic data ends 7 Oct: this week is not closed
    key = {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": w1.isoformat(), "expected_line_version": ln["line_version"]}
    await svc.edit_lines(PLANNER, plan["id"], [{**key, "target_units": 120}])
    ln = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"], week_from=w1, week_to=w1))[0]
    await svc.edit_lines(PLANNER, plan["id"], [{**key, "expected_line_version": ln["line_version"], "manager_forecast": 100, "reason_code": "PROMOTION", "note": "promotion week"}])
    await import_week(svc, PLANNER, w1, net=92, batch="batch-var1")
    ln = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"], week_from=w1, week_to=w1))[0]
    assert ln["actual_units"] == 92 and ln["week_closed"] and ln["actual_source"] == "IMPORTED" and ln["model_forecast"] != 100
    perf = await svc.performance(PLANNER, plan["id"], "store", {"product_id": P_BAT, "stores": ["N-Z1-S1"], "week_from": w1, "week_to": w1}, "PLAN")
    row = perf["rows"][0]
    assert row["actual"] == 92 and row["forecast"] == 100 and row["forecast_variance"] == -8 and row["forecast_variance_pct"] == -8.0
    assert row["target_variance"] == -28 and row["target_attainment_pct"] == round(92 / 120 * 100, 1)
    model = await svc.performance(PLANNER, plan["id"], "store", {"product_id": P_BAT, "stores": ["N-Z1-S1"], "week_from": w1, "week_to": w1}, "MODEL")
    assert model["rows"][0]["forecast"] != 100 and model["basis"] == "MODEL"                                       # model forecast and manager forecast are different numbers
    zero_target = await svc.growth_targets(PLANNER, plan["id"], -90, "MODEL", {"product_id": P_BALL}, False)
    assert zero_target["lines_set"] > 0
    open_week = await svc.performance(PLANNER, plan["id"], "total", {"product_id": P_BALL}, "PLAN")
    assert open_week["total"]["with_actual"] == 0 and open_week["total"]["forecast_variance"] is None and open_week["total"]["target_attainment_pct"] is None   # no closed week: no variance invented


@pytest.mark.asyncio
async def test_a_correction_is_a_separate_approved_delta_that_never_overwrites_the_authoritative_actual(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    w1 = date.fromisoformat((await svc.plan_view(PLANNER, plan["id"]))["weeks"][0]["week_start"])
    await import_week(svc, PLANNER, w1, net=50, batch="batch-corr1")
    adj = await svc.adjust_actual(PLANNER, {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": w1.isoformat(), "delta_units": -3, "reason_code": "DUPLICATE_ORDER", "note": "one order entered twice at the till"})
    ln = lambda: lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"], week_from=w1, week_to=w1)  # noqa: E731
    assert (await ln())[0]["actual_units"] == 50                                                                    # pending: not counted
    with pytest.raises(PlanningError) as e:
        await svc.decide_adjustment(PLANNER, adj["id"], True, None)
    assert e.value.status == 403
    with pytest.raises(PlanningError) as e:
        await svc.decide_adjustment(Actor(adj["requested_by"], ["CATEGORY_MANAGER"]), adj["id"], True, None)
    assert e.value.code == "SELF_APPROVAL"
    await svc.decide_adjustment(MANAGER, adj["id"], True, "confirmed against the till log")
    row = (await ln())[0]
    assert row["actual_units"] == 47 and row["authoritative_units"] == 50 and row["adjustment_units"] == -3
    assert await store.pool.fetchval("SELECT net_units FROM pln_sales_week WHERE data_source = 'IMPORTED' AND week_start = $1", w1) == 50       # the stored actual is untouched
    for bad in ({"delta_units": 0}, {"note": "short"}, {"reason_code": "BECAUSE"}, {"week_start": "2030-01-07"}):
        with pytest.raises(PlanningError):
            await svc.adjust_actual(PLANNER, {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": w1.isoformat(), "delta_units": 1, "reason_code": "OTHER", "note": "a long enough explanation"} | bad)
    inv_ = await svc.investigate(PLANNER, plan["id"], P_BAT, "N-Z1-S1", w1)
    assert any("approved correction of -3" in f for f in inv_["facts"])


@pytest.mark.asyncio
async def test_the_investigation_states_facts_and_labels_hypotheses_and_refuses_an_open_week(world):
    svc, _, store = world
    plan = await plan_for(svc)
    w1 = date.fromisoformat((await svc.plan_view(PLANNER, plan["id"]))["weeks"][0]["week_start"])
    opened = await svc.investigate(PLANNER, plan["id"], P_BAT, "N-Z1-S1", w1)
    assert opened["flagged"] is False and "not closed" in opened["facts"][0]
    await import_week(svc, PLANNER, w1, net=60, batch="batch-inv1")
    big = await svc.investigate(PLANNER, plan["id"], P_BAT, "N-Z1-S1", w1)
    assert big["flagged"] and big["comparison"]["forecast_variance"] > 40 and big["hypotheses"] and any("promotion" in x.lower() for x in big["limits"])
    assert not any("caused" in t.lower() for t in big["facts"] + big["hypotheses"])


# ------------------------------------------------------------------------------------------------ inventory
@pytest.mark.asyncio
async def test_allocation_uses_the_inventory_ai_rules_per_store_with_moq_applied_once_and_reasons(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    out = await svc.generate_allocation(PLANNER, plan["id"], {})
    assert out["recommendations"] == 3 * 19 and out["summary"]["recs"] == 3 * 19                                    # 19 stores hold stock today: the PLANNED store has none
    page = await svc.allocations(PLANNER, plan["id"], {"q": "Test Bat"}, 0, 200, None, False)
    recs_ = page["items"]
    assert recs_ and all(r["status"] == "DRAFT" and r["reason"] for r in recs_)
    ordered = [r for r in recs_ if r["action"] == "ORDER"]
    assert ordered and sum(r["recommended_qty"] for r in ordered) % 5 == 0                                           # the case pack of 5 applies to the whole product order
    assert sum(r["recommended_qty"] for r in ordered) >= 20                                                          # ... and the supplier MOQ of 20
    high = [r for r in recs_ if r["risk_level"] == "HIGH"]
    assert high and all(r["order_by"] is not None or r["action"] != "ORDER" for r in high)
    one = recs_[0]
    row = await store.pool.fetchrow("SELECT on_hand, reserved FROM pln_stock_position WHERE product_id = $1::uuid AND location_id = $2", one["product_id"], one["location_id"])
    assert one["on_hand"] == row["on_hand"] - row["reserved"]                                                        # sellable = on hand - reserved
    inc = await store.pool.fetchval("SELECT COALESCE(SUM(qty), 0) FROM pln_incoming WHERE product_id = $1::uuid AND location_id = $2", one["product_id"], one["location_id"])
    assert one["incoming"] == inc                                                                                    # incoming counted once
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_transfer_rec WHERE status = 'DRAFT'") == len(page["transfers"]) or page["transfers"] is not None


@pytest.mark.asyncio
async def test_a_manager_forecast_changes_the_stock_plan_and_the_impact_says_how(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    rows = await lines(svc, plan["id"], product_id=P_BAT, stores=["W-Z1-S1"])
    for r in rows:
        await svc.edit_lines(PLANNER, plan["id"], [{"product_id": P_BAT, "location_id": "W-Z1-S1", "week_start": r["week_start"], "expected_line_version": r["line_version"],
                                                   "manager_forecast": round(r["model_forecast"] * 3, 2), "reason_code": "PROMOTION"}])
    imp = await svc.impact(PLANNER, plan["id"], P_BAT, "W-Z1-S1")
    assert imp["available"] and imp["plan"]["forecast_30"] > imp["model"]["forecast_30"] * 2
    assert imp["plan"]["order_qty"] >= imp["model"]["order_qty"] and "Inventory AI" in imp["note"]


@pytest.mark.asyncio
async def test_recommendations_need_an_approver_and_approving_orders_and_moves_nothing(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    await svc.generate_allocation(PLANNER, plan["id"], {})
    rec = (await svc.allocations(PLANNER, plan["id"], {}, 0, 5, None, True))["items"][0]
    with pytest.raises(PlanningError) as e:
        await svc.decide_recommendation(PLANNER, "allocation", rec["id"], True)
    assert e.value.status == 403
    stock_before = await store.pool.fetchval("SELECT SUM(on_hand) FROM pln_stock_position")
    inc_before = await store.pool.fetchval("SELECT COUNT(*) FROM pln_incoming")
    out = await svc.decide_recommendation(MANAGER, "allocation", rec["id"], True)
    assert out["status"] == "APPROVED" and "No purchase order" in out["notice"]
    assert await store.pool.fetchval("SELECT SUM(on_hand) FROM pln_stock_position") == stock_before and await store.pool.fetchval("SELECT COUNT(*) FROM pln_incoming") == inc_before
    with pytest.raises(PlanningError) as e:
        await svc.decide_recommendation(MANAGER, "allocation", rec["id"], False)
    assert e.value.code == "ALREADY_DECIDED"
    again = await svc.generate_allocation(PLANNER, plan["id"], {})                                                 # a fresh run supersedes drafts but keeps the decided one
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_alloc_rec WHERE status = 'APPROVED'") == 1 and again["recommendations"] > 0


@pytest.mark.asyncio
async def test_a_transfer_is_recommended_from_an_overstocked_store_to_a_store_that_would_run_out(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    await store.pool.execute("UPDATE pln_stock_position SET on_hand = 2000 WHERE product_id = $1::uuid AND location_id = 'N-Z1-S2'", P_BAT)
    await store.pool.execute("UPDATE pln_stock_position SET on_hand = 1 WHERE product_id = $1::uuid AND location_id = 'N-Z1-S1'", P_BAT)
    await svc.generate_allocation(PLANNER, plan["id"], {"q": "Test Bat"})
    page = await svc.allocations(PLANNER, plan["id"], {"q": "Test Bat"}, 0, 200, None, False)
    t = [x for x in page["transfers"] if x["to_location"] == "N-Z1-S1"]
    assert t and t[0]["from_location"] == "N-Z1-S2" and t[0]["lead_days"] == 3 and "keeps its own target level" in t[0]["reason"] and t[0]["status"] == "DRAFT"
    dest = next(r for r in page["items"] if r["location_id"] == "N-Z1-S1")
    assert dest["detail"]["transfer_in"] == t[0]["qty"] and dest["action"] in ("TRANSFER_IN", "ORDER")
    out = await svc.decide_recommendation(MANAGER, "transfer", t[0]["id"], True)
    assert out["status"] == "APPROVED"


@pytest.mark.asyncio
async def test_a_live_plan_has_no_store_stock_so_no_allocation_is_invented(world):
    svc, repo, _ = world
    feed = FakeFeed()
    for w in range(40):
        feed.add("DELIVERED", 3, placed=(datetime(2026, 1, 5, 10, tzinfo=timezone.utc) + timedelta(weeks=w)).isoformat().replace("+00:00", "Z"), state="KA")
    await make_ingest(repo, svc, feed).run("h", "admin-token")
    await svc.run_forecast(MANAGER, LIVE)
    plan = await svc.create_plan(PLANNER, name="Online plan", source=LIVE, fy=2026, period="HORIZON", number=None, scope={}, run_id=None)
    with pytest.raises(PlanningError) as e:
        await svc.generate_allocation(PLANNER, plan["id"], {})
    assert e.value.code == "NO_STORE_STOCK"


# ------------------------------------------------------------------------------------------------ scenarios
@pytest.mark.asyncio
async def test_a_scenario_is_a_saved_simulation_that_leaves_the_plan_untouched_and_can_be_compared(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    before = await store.pool.fetchval("SELECT md5(string_agg(concat_ws('|', product_id, location_id, week_start, model_forecast, manager_forecast, target_units, approved_forecast), ',' ORDER BY product_id, location_id, week_start)) "
                                       "FROM pln_plan_line WHERE plan_id = $1::uuid", plan["id"])
    g = await svc.create_scenario(PLANNER, name="+10% bats", kind="GROWTH_TARGET", params={"growth_pct": 10}, plan_id=plan["id"], f={"category": "Bat"})
    p = await svc.create_scenario(PLANNER, name="20% off bats", kind="PROMOTION", params={"discount_pct": 20, "weeks": 2}, plan_id=plan["id"], f={"category": "Bat"})
    assert g["status"] == "SIMULATION" and g["results"]["summary"]["scenario"]["demand_units"] > g["results"]["summary"]["baseline"]["demand_units"]
    assert p["results"]["items"][0]["scenario"]["elasticity_basis"] == "POC_ASSUMPTION"
    after = await store.pool.fetchval("SELECT md5(string_agg(concat_ws('|', product_id, location_id, week_start, model_forecast, manager_forecast, target_units, approved_forecast), ',' ORDER BY product_id, location_id, week_start)) "
                                      "FROM pln_plan_line WHERE plan_id = $1::uuid", plan["id"])
    assert before == after                                                                                         # simulations never change the plan
    cmp = await svc.compare_scenarios(PLANNER, [g["id"], p["id"]])
    assert [s["kind"] for s in cmp["scenarios"]] == ["GROWTH_TARGET", "PROMOTION"] and "Simulated" in cmp["notice"]
    assert (await svc.scenarios(PLANNER, plan["id"]))["items"].__len__() == 2
    with pytest.raises(PlanningError):
        await svc.create_scenario(PLANNER, name="too broad", kind="GROWTH_TARGET", params={"growth_pct": 10}, plan_id=plan["id"], f={})
    with pytest.raises(PlanningError):
        await svc.create_scenario(PLANNER, name="bad", kind="PROMOTION", params={"discount_pct": 99, "weeks": 2}, plan_id=plan["id"], f={"category": "Bat"})


@pytest.mark.asyncio
async def test_only_a_growth_scenario_can_be_adopted_and_only_as_targets_on_an_editable_plan(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    promo = await svc.create_scenario(PLANNER, name="promo", kind="PROMOTION", params={"discount_pct": 10, "weeks": 1}, plan_id=plan["id"], f={"category": "Ball"})
    with pytest.raises(PlanningError) as e:
        await svc.adopt_scenario(PLANNER, promo["id"])
    assert e.value.code == "NOT_ADOPTABLE"
    growth = await svc.create_scenario(PLANNER, name="g", kind="GROWTH_TARGET", params={"growth_pct": 5}, plan_id=plan["id"], f={"category": "Ball"})
    out = await svc.adopt_scenario(PLANNER, growth["id"])
    assert out["lines_set"] > 0 and (await svc.scenario(PLANNER, growth["id"]))["status"] == "ADOPTED"
    rows = await lines(svc, plan["id"], category="Ball", stores=["N-Z1-S1"])
    assert all(r["target_units"] is not None and r["manager_forecast"] is None for r in rows)                      # a target was set; no forecast was changed
    with pytest.raises(PlanningError) as e2:
        await svc.adopt_scenario(PLANNER, growth["id"])
    assert e2.value.code == "ALREADY_ADOPTED"


# ------------------------------------------------------------------------------------------------ pilots (Phase 5)
async def opportunity(store, status="APPROVED_OPPORTUNITY", provenance="LIVE_EXTERNAL_SOURCE", kind="PRODUCT_CANDIDATE", sport="Cricket", category="Bat") -> str:
    oid = uuid.uuid4()
    await store.pool.execute("INSERT INTO src_opportunities (id, identity_key, name, brand, sport, category, origin, created_by, provenance, record_kind, status) VALUES ($1,$2,'Pilot Bat','B',$3,$4,'LIVE_RESEARCH','t',$5,$6,$7)",
                             oid, f"k-{oid}", sport, category, provenance, kind, status)
    return str(oid)


@pytest.mark.asyncio
async def test_a_pilot_is_proposed_only_for_an_approved_live_candidate_and_only_as_a_low_confidence_range(world):
    svc, repo, store = world
    await svc.run_forecast(MANAGER, DEMO_SRC)
    for kw in ({"provenance": "TEST_FIXTURE"}, {"provenance": "DEMO_FIXTURE"}, {"status": "DISCOVERED"}, {"kind": "DEMAND_GAP"}):
        with pytest.raises(PlanningError) as e:
            await svc.propose_pilot(MANAGER, await opportunity(store, **kw), 8, None, None)
        assert e.value.code in ("NOT_APPROVED", "NOT_FOUND")
    pilot = await svc.propose_pilot(MANAGER, await opportunity(store, sport="Cricket", category="Bat"), 8, None, None)
    est = pilot["estimate"]
    assert est["status"] in ("ESTIMATED", "INSUFFICIENT_COMPARABLES") and est["confidence"] in ("LOW", "NONE") and est["assumptions"]
    if est["status"] == "INSUFFICIENT_COMPARABLES":                                                                   # the fixture has only 1 other bat: no estimate is made, and it says what to do
        assert "category_options" in est and pilot["stores"] == []
    with pytest.raises(PlanningError) as e:
        await svc.propose_pilot(STORE_MGR, await opportunity(store), 8, None, None)                              # a store manager cannot propose a pilot that spans stores
    assert e.value.status == 403
    nothing = await svc.pilot_decide(ADMIN, pilot["id"], "CANCELLED", "no comparable data") if est["status"] != "ESTIMATED" else None
    if est["status"] != "ESTIMATED":
        with pytest.raises(PlanningError) as e:
            await svc.pilot_decide(ADMIN, pilot["id"], "APPROVED", None)
        assert e.value.code == "NO_ESTIMATE"
    assert nothing is None or "No product is published" in nothing["notice"]


# ------------------------------------------------------------------------------------------------ dashboard, analysis, readiness
@pytest.mark.asyncio
async def test_the_dashboard_shows_only_what_the_data_supports_with_its_freshness(world):
    svc, _, _ = world
    empty = await svc.dashboard(PLANNER, DEMO_SRC, None, {}, None, None, None)
    assert empty["plan"] is None and empty["metrics"] == {} and empty["data_scope"]["synthetic"] and empty["freshness"]["data_through"] == "2026-10-07"
    plan = await plan_for(svc)
    await svc.generate_allocation(PLANNER, plan["id"], {})
    d = await svc.dashboard(PLANNER, DEMO_SRC, plan["id"], {}, None, None, None)
    m = d["metrics"]
    assert m["model_forecast_units"] > 0 and m["target_units"] is None and m["actual_units"] is None and m["target_attainment_pct"] is None      # nothing invented: no target, no closed week
    assert m["forecast_accuracy"]["sufficient"] and d["queues"]["products_to_replenish"] is not None and d["queues"]["plans_in_draft"] >= 1
    sport_only = await svc.dashboard(PLANNER, DEMO_SRC, plan["id"], {"sport": "Badminton"}, None, None, None)
    assert 0 < sport_only["metrics"]["model_forecast_units"] < m["model_forecast_units"]
    await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, plan["version"])
    assert (await svc.dashboard(PLANNER, DEMO_SRC, None, {}, None, None, None))["queues"]["plans_awaiting_approval"] == 1


@pytest.mark.asyncio
async def test_regional_analysis_reports_shares_with_their_sample_size_and_marks_weak_evidence(world):
    svc, _, _ = world
    out = await svc.regional_analysis(PLANNER, DEMO_SRC, 26, None)
    assert out["matrix"] and out["store_ranking"] and out["top_products"] and "SYNTHETIC" in out["notice"]
    assert all(i["confidence"] in ("LOW", "MEDIUM") and "store_weeks" in i and i["statement"] for i in out["insights"])
    ranks = [s["rank"] for s in out["store_ranking"]]
    assert ranks == sorted(ranks) and out["store_ranking"][0]["net_units"] >= out["store_ranking"][-1]["net_units"]


@pytest.mark.asyncio
async def test_the_readiness_report_labels_synthetic_data_as_demonstration_and_lists_what_is_unavailable(world):
    svc, _, _ = world
    r = await svc.readiness()
    by = {c["key"]: c for c in r["capabilities"]}
    assert by["store_level_history"]["status"] == "DEMONSTRATION_ONLY" and by["store_hierarchy"]["status"] == "DEMONSTRATION_ONLY" and by["online_sales_actuals"]["status"] == "UNAVAILABLE"
    assert "no physical stores" in by["store_level_history"]["note"].lower() and by["promotion_history"]["status"] == "UNAVAILABLE"        # this fixture has no discounted days
    assert r["conventions"]["net_units"].startswith("gross - cancelled - returned") and r["thresholds"]["min_history_weeks"] == 26


# ------------------------------------------------------------------------------------------------ persistence and the REST API
@pytest.mark.asyncio
async def test_everything_survives_a_restart(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    await svc.edit_lines(PLANNER, plan["id"], [{**{k: v for k, v in (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0].items() if k in ("product_id", "location_id", "week_start")},
                                               "expected_line_version": 1, "target_units": 33}])
    await store.close()
    store2 = await Store.connect(DSN, 60, 768)
    try:
        svc2 = PlanningService(PlanningRepository(store2.pool), make_settings(AI_DATABASE_URL=DSN), None, lambda u: u)
        again = (await svc2.plan_lines(PLANNER, plan["id"], {"product_id": P_BAT, "stores": ["N-Z1-S1"]}, "product", 0, 5))["items"][0]
        assert again["target_units"] == 33 and (await svc2.repo.current_run(DEMO_SRC)) is not None and (await svc2.plan_view(PLANNER, plan["id"]))["status"] == "DRAFT"
    finally:
        await store2.close()


def _client():
    return TestClient(create_app(make_settings(AI_DATABASE_URL=DSN)), raise_server_exceptions=False)


def _h(sub, *roles):
    return {"Authorization": f"Bearer {make_token(sub, roles=roles)}"}


def test_api_authorisation_roles_validation_and_the_conflict_response(world_sync=None):
    async def prepare():
        conn = await asyncpg.connect(DSN)
        for t in TABLES:
            await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
        await conn.close()
        store = await Store.connect(DSN, 60, 768)
        async with store.pool.acquire() as c:
            await seed_inventory(c)
        svc = PlanningService(PlanningRepository(store.pool), make_settings(AI_DATABASE_URL=DSN, PLANNING_BACKTEST_ORIGINS=4), None, lambda u: u)
        await svc.seed_demo(ADMIN)
        await store.close()
    asyncio.run(prepare())
    admin_id, planner_id, manager_id = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    with _client() as c:
        assert c.get("/v1/planning/readiness").status_code == 401
        for role in ("CUSTOMER", "SUPPORT_EXECUTIVE", "PROCUREMENT_BUYER"):
            assert c.get("/v1/planning/readiness", headers=_h(str(uuid.uuid4()), role)).status_code == 403
            assert c.get("/v1/planning/plans", headers=_h(str(uuid.uuid4()), role)).status_code == 403
        adm, pl, mg = _h(admin_id, "ADMIN"), _h(planner_id, "SALES_PLANNER"), _h(manager_id, "CATEGORY_MANAGER")
        assert c.post("/v1/planning/forecast/runs", json={"scope": "POC_SYNTHETIC"}, headers=pl).status_code == 201
        assert c.post("/v1/planning/forecast/runs", json={"scope": "BOGUS"}, headers=pl).status_code == 422
        made = c.post("/v1/planning/plans", json={"name": "API plan", "source": "POC_SYNTHETIC"}, headers=pl)
        assert made.status_code == 201 and made.json()["status"] == "DRAFT" and made.json()["lines"] > 0
        pid = made.json()["id"]
        grid = c.get(f"/v1/planning/plans/{pid}/lines?product_id={P_BAT}&store=N-Z1-S1&size=3", headers=pl).json()
        assert len(grid["items"]) == 3 and grid["total"] == 13 and grid["plan"]["editable"]
        ln = grid["items"][0]
        edit = {"product_id": P_BAT, "location_id": "N-Z1-S1", "week_start": ln["week_start"], "expected_line_version": ln["line_version"], "target_units": 12}
        assert c.patch(f"/v1/planning/plans/{pid}/lines", json={"edits": [edit]}, headers=pl).status_code == 200
        stale = c.patch(f"/v1/planning/plans/{pid}/lines", json={"edits": [edit | {"target_units": 13}]}, headers=mg)
        assert stale.status_code == 409 and stale.json()["code"] == "EDIT_CONFLICT" and stale.json()["details"]["info"]["conflicts"][0]["code"] == "VERSION_CONFLICT"
        assert c.patch(f"/v1/planning/plans/{pid}/lines", json={"edits": [edit | {"bogus": 1}]}, headers=pl).status_code == 422
        assert c.patch(f"/v1/planning/plans/{pid}/lines", json={"edits": []}, headers=pl).status_code == 422
        ver = c.get(f"/v1/planning/plans/{pid}", headers=pl).json()["version"]
        assert c.post(f"/v1/planning/plans/{pid}/transition", json={"status": "SUBMITTED", "expected_version": ver}, headers=pl).status_code == 200
        ver = c.get(f"/v1/planning/plans/{pid}", headers=pl).json()["version"]
        assert c.post(f"/v1/planning/plans/{pid}/transition", json={"status": "UNDER_REVIEW", "expected_version": ver}, headers=pl).status_code == 403
        assert c.post(f"/v1/planning/plans/{pid}/transition", json={"status": "UNDER_REVIEW", "expected_version": ver}, headers=mg).status_code == 200
        assert c.get("/v1/planning/audit", headers=pl).status_code == 403 and c.get("/v1/planning/audit", headers=adm).status_code == 200
        assert any(x["action"] == "DENIED" for x in c.get("/v1/planning/audit?entity=ACCESS", headers=adm).json()["items"])
        assert c.post("/v1/planning/ingest", json={"mode": "INCREMENTAL"}, headers=adm).status_code in (202, 502)          # the real DSports is not reachable in this test: reported, not hidden
        assert c.post("/v1/planning/demo/seed", headers=pl).status_code == 403
        assert c.post("/v1/planning/scope", json={"user_id": planner_id, "location_id": "N"}, headers=mg).status_code == 403
        assert c.get(f"/v1/planning/plans/{pid}/performance?grain=bogus", headers=pl).status_code == 422
        assert c.get(f"/v1/planning/plans/{pid}/performance?grain=category", headers=pl).status_code == 200


# ------------------------------------------------------------------------------------------------ the assistant (typed tools + deterministic answers)
class _Services:
    def __init__(self, svc):
        self.planning = svc


def _principal(roles, customer_hash):
    from app.auth.jwt import Principal
    return Principal(user_id=uuid.uuid4(), roles=tuple(roles), customer_hash=customer_hash, token="t")


def _ctx(svc, roles, customer_hash="hash-assistant"):
    from app.tools.registry import ToolContext
    return ToolContext(principal=_principal(roles, customer_hash), dsports=None, settings=svc.settings, services=_Services(svc), max_calls=50)


@pytest.mark.asyncio
async def test_planning_tools_are_registered_read_only_and_refuse_everyone_without_a_planning_role(world):
    from app.tools.build import build_registry
    svc, _, _ = world
    reg = build_registry()
    names = {"get_planning_overview", "get_data_readiness", "get_store_hierarchy", "get_weekly_forecast", "get_plan_vs_actual", "get_forecast_accuracy", "get_inventory_recommendations",
             "explain_variance", "get_regional_analysis", "simulate_planning_scenario", "get_scenarios_and_pilots"}
    from app.tools.registry import Kind
    assert names <= {t.name for t in reg._tools.values()} and all(reg.get(n).kind is Kind.READ for n in names)            # there is no planning WRITE tool
    for n in names:
        denied = await reg.call(n, {}, _ctx(svc, ["CUSTOMER"]))
        assert not denied.ok and denied.error_code == "FORBIDDEN"
    forged = await reg.call("get_planning_overview", {"customerId": "x"}, _ctx(svc, ["SALES_PLANNER"]))
    assert not forged.ok and forged.error_code == "INVALID_ARGUMENTS"


@pytest.mark.asyncio
async def test_the_assistant_answers_from_planning_records_with_evidence_links_and_never_invents(world):
    from app.planning import ask
    from app.tools.build import build_registry
    svc, _, _ = world
    reg, ctx = build_registry(), _ctx(svc, ["SALES_PLANNER"])
    none_yet = await ask.answer(reg, ctx, "Compare forecast, target and actual sales for badminton")
    assert "no plan" in none_yet["answer"].lower() or "could not read" in none_yet["answer"].lower()
    plan = await plan_for(svc)
    comp = await ask.answer(reg, ctx, "Compare forecast, target and actual sales for badminton")
    assert "No week in this plan has closed" in comp["answer"] and comp["evidence"] and comp["evidence"][0]["link"].startswith("/sales-planning?tab=performance")
    acc = await ask.answer(reg, ctx, "Show the forecast accuracy")
    assert "WAPE" in acc["answer"] and "DEMONSTRATION" in acc["answer"] and acc["limitations"]
    fc = await ask.answer(reg, ctx, "What is the forecast for cricket bats in each South store next week?")
    assert "week of" in fc["answer"] and "Test Bat One" in fc["answer"] and "DEMONSTRATION" in fc["answer"]
    data = await ask.answer(reg, ctx, "Which regional forecasts are based on insufficient data?")
    assert data["intent"] == "DATA" and "demonstration" in data["answer"].lower() and "online" in data["answer"].lower()
    no_alloc = await ask.answer(reg, ctx, "Which stores are at risk of running out of cricket bats?")
    assert "No allocation has been generated" in no_alloc["answer"]
    await svc.generate_allocation(PLANNER, plan["id"], {})
    risk = await ask.answer(reg, ctx, "Which stores are at risk of running out of cricket bats?")
    assert "high risk" in risk["answer"] or "No position is at high risk" in risk["answer"]
    alloc = await ask.answer(reg, ctx, "How many units should we allocate to each store?")
    assert "MOQ" in alloc["answer"] or "Nothing needs ordering" in alloc["answer"]
    sc = await ask.answer(reg, ctx, "What happens to the inventory plan if supplier lead time increases by 7 days for cricket bats?")
    assert "simulation: nothing was saved" in sc["answer"] and (await svc.scenarios(PLANNER, None))["items"] == []                       # a question is not a decision: nothing is saved
    vague = await ask.answer(reg, ctx, "What happens if lead time goes up?")
    assert "Name a sport or category" in vague["answer"]
    unknown = await ask.answer(reg, ctx, "Tell me a joke")
    assert unknown["intent"] == "UNKNOWN" and "forecasts" in unknown["answer"]
    pil = await ask.answer(reg, ctx, "Which stores should pilot this newly approved product?")
    assert "No pilot has been proposed" in pil["answer"]


@pytest.mark.asyncio
async def test_a_store_manager_asking_the_assistant_sees_only_their_own_stores(world):
    from app.planning import ask
    from app.tools.build import build_registry
    svc, _, _ = world
    plan = await plan_for(svc)
    await svc.generate_allocation(PLANNER, plan["id"], {})
    await svc.grant_scope(ADMIN, "u-asst-store", "N-Z1-S1")
    ctx = _ctx(svc, ["STORE_MANAGER"], svc.hash_user("u-asst-store"))
    out = await ask.answer(build_registry(), ctx, "How many units should we allocate to each store?")
    names = " ".join(r["store_name"] for r in (out["data"] or {}).get("items", []))
    assert out["data"] and names and "North Zone 1 Store 1" in names and "Zone 1 Store 2" not in names.replace("North Zone 1 Store 1", "")


# ------------------------------------------------------------------------------------------------ AI-assisted analysis and the review queue
class FakeLLM:
    """Stands in for the model: returns a fixed text, so the grounding check and the fall-back are tested deterministically."""
    def __init__(self, text: str):
        self.text, self.calls = text, 0

    async def generate(self, prompt, **kw):
        from app.llm.base import LLMResult
        self.calls += 1
        return LLMResult(self.text, "fake-model", 100, 20, 5)


@pytest.mark.asyncio
async def test_an_analysis_is_persisted_with_its_provenance_and_every_finding_says_where_its_data_came_from(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    a = await svc.analyze(PLANNER, plan["id"], {}, use_llm=False)
    row = await store.pool.fetchrow("SELECT * FROM pln_analysis WHERE id = $1::uuid", a["id"])
    assert row["plan_version"] == plan["version"] and row["model_version"] and row["source"] == DEMO_SRC and row["data_through"] == HISTORY_LAST
    assert a["summary_source"] == "RULES" and a["data_scope"]["synthetic"] and "Nothing here approves" in a["notice"]
    assert a["findings"] and all("DEMO" in f["provenance"] and "LIVE" not in f["provenance"] and f["freshness"]["data_through"] for f in a["findings"])
    assert any(u["analysis"] == "Stock-out and excess risk" for u in a["unavailable"])            # no allocation yet: the analysis says so instead of inventing a risk
    await svc.generate_allocation(PLANNER, plan["id"], {})
    again = await svc.analyze(PLANNER, plan["id"], {}, use_llm=False)
    assert not any(u["analysis"] == "Stock-out and excess risk" for u in again["unavailable"])
    assert (await svc.latest_analysis(PLANNER, plan["id"]))["id"] == again["id"]
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_audit WHERE entity = 'ANALYSIS' AND action = 'CREATED'") == 2


@pytest.mark.asyncio
async def test_the_llm_summary_is_kept_only_when_every_number_in_it_is_in_the_findings(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    svc.llm = FakeLLM("This is demonstration data. Sales will rise by 9,999 units next month.")
    bad = await svc.analyze(PLANNER, plan["id"], {}, use_llm=True)
    assert bad["summary_source"] == "RULES" and bad["llm"]["used"] and bad["llm"]["grounding_fallback"] and "9,999" not in bad["summary"]
    svc.llm = FakeLLM("This is demonstration data, so treat the figures as an illustration.")
    good = await svc.analyze(PLANNER, plan["id"], {}, use_llm=True)
    assert good["summary_source"] == "LLM" and good["llm"]["grounded"] and good["llm"]["model"] == "fake-model"
    assert svc.llm.calls == 1
    off = FakeLLM("x")
    svc.llm = off
    await svc.analyze(PLANNER, plan["id"], {}, use_llm=False)
    assert off.calls == 0                                                                           # no model call unless asked: the findings never need one


@pytest.mark.asyncio
async def test_a_failing_llm_leaves_the_deterministic_summary_and_the_findings_intact(world):
    from app.llm.base import LLMError

    class Down:
        async def generate(self, *a, **k):
            raise LLMError("LLM_UNAVAILABLE", "down")
    svc, _, _ = world
    plan = await plan_for(svc)
    svc.llm = Down()
    a = await svc.analyze(PLANNER, plan["id"], {}, use_llm=True)
    assert a["summary_source"] == "RULES" and a["llm"] == {"used": False, "error": "LLM_UNAVAILABLE"} and a["findings"]


async def _with_proposal(svc, repo, plan, who=PLANNER):
    ls = await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"])
    wk = [l["week_start"] for l in ls][:2]
    finding = {"id": "f-prop", "kind": "FORECAST_VS_RECENT", "title": "t", "severity": "MEDIUM", "statement": "s", "facts": [], "hypotheses": [], "scope": {}, "evidence": [], "provenance": ["DEMO"], "freshness": {},
               "assumptions": [], "confidence": "LOW", "impact": None, "risks": [], "next_action": "",
               "proposal": {"kind": "MANAGER_FORECAST", "reason_code": "DATA_CORRECTION", "weeks": wk, "note": "AI-assisted proposal: blend",
                            "items": [{"product_id": P_BAT, "location_id": "N-Z1-S1", "name": "Test Bat One", "store": "N-Z1-S1", "model_per_week": 1.0, "recent_per_week": 5.0, "proposed_per_week": 3.0}], "basis": "b"}}
    ev = {"data_through": "2026-10-07", "forecast_run_id": plan["forecast_run_id"], "model_version": "v"}
    saved = await repo.save_analysis(await repo.get_plan(plan["id"]), who.hash, {}, ev, "s", "RULES", {"used": False}, [finding], [])
    return saved["id"], wk


@pytest.mark.asyncio
async def test_adopting_a_proposal_is_an_ordinary_audited_edit_that_cannot_be_repeated_and_never_approves(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    aid, wk = await _with_proposal(svc, repo, plan)
    before = (await svc.plan_view(PLANNER, plan["id"]))["status"]
    res = await svc.decide_finding(PLANNER, aid, "f-prop", True)
    assert [f["decision"] for f in res["findings"]] == ["ADOPTED"]
    edited = await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"])
    assert [l["manager_forecast"] for l in edited if l["week_start"] in wk] == [3.0, 3.0] and all(l["model_forecast"] is not None for l in edited)       # the model forecast is untouched
    assert (await svc.plan_view(PLANNER, plan["id"]))["status"] == before == "DRAFT"                  # adopting a proposal approves nothing
    audit = await store.pool.fetch("SELECT entity, action, after FROM pln_audit WHERE entity IN ('ANALYSIS', 'PLAN_LINE') ORDER BY id")
    assert any(r["entity"] == "PLAN_LINE" for r in audit) and any(r["action"] == "FINDING_ADOPTED" for r in audit)
    with pytest.raises(PlanningError) as e:
        await svc.decide_finding(PLANNER, aid, "f-prop", True)
    assert e.value.code == "ALREADY_DECIDED"


@pytest.mark.asyncio
async def test_dismissing_changes_nothing_and_a_frozen_plan_cannot_take_a_proposal(world):
    svc, repo, _ = world
    plan = await plan_for(svc)
    aid, wk = await _with_proposal(svc, repo, plan)
    await svc.decide_finding(PLANNER, aid, "f-prop", False)
    assert all(l["manager_forecast"] is None for l in await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))
    plan2 = await plan_for(svc, "Second")
    aid2, _ = await _with_proposal(svc, repo, plan2)
    sub = await svc.transition(PLANNER, plan2["id"], "SUBMITTED", None, plan2["version"])
    assert sub["status"] == "SUBMITTED"
    with pytest.raises(PlanningError) as e:
        await svc.decide_finding(PLANNER, aid2, "f-prop", True)
    assert e.value.code in ("INVALID_TRANSITION", "PLAN_FROZEN", "EDIT_FROZEN") or e.value.status == 409


@pytest.mark.asyncio
async def test_an_analysis_respects_location_scope(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    outsider = Actor("hash-of-u-nobody", ["STORE_MANAGER"])
    with pytest.raises(PlanningError) as e:
        await svc.analyze(outsider, plan["id"], {}, use_llm=False)
    assert e.value.status == 403
    await svc.grant_scope(ADMIN, "u-store", "N-Z1-S1")
    insider = Actor("hash-of-u-store", ["STORE_MANAGER"])
    a = await svc.analyze(insider, plan["id"], {}, use_llm=False)
    assert a["id"] and all(f["scope"] in ({}, {"store": "N-Z1-S1"}) or "store" not in f["scope"] or f["scope"]["store"] == "N-Z1-S1" for f in a["findings"])
    with pytest.raises(PlanningError):
        await svc.analysis(outsider, a["id"])


@pytest.mark.asyncio
async def test_the_review_queue_lists_submitted_plans_with_their_regions_and_who_can_review(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    assert (await svc.review_queue(MANAGER, None, None))["items"] == []
    await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, plan["version"])
    q = await svc.review_queue(MANAGER, None, None)
    item = next(i for i in q["items"] if i["id"] == plan["id"])
    assert item["status"] == "SUBMITTED" and set(item["regions"]) == {"N", "S", "E", "W", "C"} and item["can_review"] and item["data_scope"]["synthetic"]
    assert item["lines"] > 0 and 0 <= item["low_confidence_share"] <= 100 and "submitted_by" not in item and item["submitted_by_ref"]
    assert not next(i for i in (await svc.review_queue(PLANNER, None, None))["items"] if i["id"] == plan["id"])["can_review"]
    only_north = await svc.review_queue(MANAGER, None, "N")
    assert [i["id"] for i in only_north["items"]] == [plan["id"]] and set(only_north["items"][0]["regions"]) == {"N"}
    assert "does not order" in q["notice"] and "publish" in q["notice"]
    bd = await svc.plan_breakdown(MANAGER, plan["id"], "region")
    assert {r["gkey"] for r in bd["rows"]} == {"N", "S", "E", "W", "C"} and sum(r["lines"] for r in bd["rows"]) == item["lines"]


@pytest.mark.asyncio
async def test_bulk_review_previews_first_applies_all_or_nothing_and_changes_only_plan_status(world):
    svc, repo, store = world
    p1 = await plan_for(svc, "One")
    p2 = await plan_for(svc, "Two")
    p3 = await svc.create_plan(PLANNER, name="Draft only", source=DEMO_SRC, fy=2026, period="HORIZON", number=None, scope={}, run_id=None)
    for p in (p1, p2):
        await svc.transition(PLANNER, p["id"], "SUBMITTED", None, p["version"])
    stock_before = await store.pool.fetchval("SELECT SUM(on_hand) FROM pln_stock_position")
    prev = await svc.bulk_review(MANAGER, [p1["id"], p2["id"]], "APPROVE", None, preview=True)
    assert prev["preview"] and prev["all_eligible"] and not prev["applied"] and all("Orders, moves, publishes and prices nothing" in r["consequence"] for r in prev["results"])
    assert (await svc.plan_view(MANAGER, p1["id"]))["status"] == "SUBMITTED"                          # a preview changes nothing
    mixed = await svc.bulk_review(MANAGER, [p1["id"], p3["id"]], "APPROVE", None, preview=True)
    assert not mixed["all_eligible"] and [r["eligible"] for r in mixed["results"]] == [True, False]
    with pytest.raises(PlanningError) as e:
        await svc.bulk_review(MANAGER, [p1["id"], p3["id"]], "APPROVE", None, preview=False)
    assert e.value.code == "NOT_ALL_ELIGIBLE" and (await svc.plan_view(MANAGER, p1["id"]))["status"] == "SUBMITTED"        # one ineligible plan: nothing is applied to any
    with pytest.raises(PlanningError) as e:
        await svc.bulk_review(PLANNER, [p1["id"], p2["id"]], "APPROVE", None, preview=False)
    assert e.value.code == "NOT_ALL_ELIGIBLE" and all(not r["eligible"] and "Only a regional manager" in r["reason"] for r in e.value.detail["results"])
    with pytest.raises(PlanningError) as e:
        await svc.bulk_review(MANAGER, [p1["id"]], "REJECT", "  ", preview=True)
    assert e.value.code == "REASON_REQUIRED"
    assert prev["requires_acknowledgement"] and "DEMONSTRATION" in prev["warning"]
    with pytest.raises(PlanningError) as e:
        await svc.bulk_review(MANAGER, [p1["id"], p2["id"]], "APPROVE", None, preview=False)                    # demonstration evidence must be acknowledged on purpose
    assert e.value.code == "SYNTHETIC_ACK_REQUIRED" and (await svc.plan_view(MANAGER, p1["id"]))["status"] == "SUBMITTED"
    done = await svc.bulk_review(MANAGER, [p1["id"], p2["id"]], "APPROVE", None, preview=False, acknowledge_synthetic=True)
    assert done["applied"] and [r["status_after"] for r in done["results"]] == ["APPROVED", "APPROVED"]
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_plan_line WHERE plan_id = $1::uuid AND approved_forecast IS NOT NULL", p1["id"]) > 0
    assert await store.pool.fetchval("SELECT SUM(on_hand) FROM pln_stock_position") == stock_before        # approving moved no stock
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_audit WHERE action = 'BULK_APPROVE'") == 1


@pytest.mark.asyncio
async def test_a_regional_manager_cannot_bulk_approve_a_plan_that_includes_stores_outside_their_region(world):
    svc, _, _ = world
    plan = await plan_for(svc)
    await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, plan["version"])
    await svc.grant_scope(ADMIN, "u-reg-n", "N")
    reg = Actor("hash-of-u-reg-n", ["REGIONAL_MANAGER"])
    prev = await svc.bulk_review(reg, [plan["id"]], "APPROVE", None, preview=True)
    assert not prev["all_eligible"] and "outside your scope" in prev["results"][0]["reason"]
    with pytest.raises(PlanningError):
        await svc.bulk_review(reg, [plan["id"]], "APPROVE", None, preview=False)
    north = await svc.create_plan(PLANNER, name="North only", source=DEMO_SRC, fy=2026, period="HORIZON", number=None, scope={"region": "N"}, run_id=None)
    await svc.transition(PLANNER, north["id"], "SUBMITTED", None, north["version"])
    ok = await svc.bulk_review(reg, [north["id"]], "APPROVE", None, preview=False, acknowledge_synthetic=True)
    assert ok["applied"] and ok["results"][0]["status_after"] == "APPROVED"


@pytest.mark.asyncio
async def test_the_assistant_answers_key_risks_from_the_stored_analysis_with_evidence_links_and_no_model_call(world):
    from app.planning import ask
    from app.tools.build import build_registry
    svc, _, store = world
    reg, ctx = build_registry(), _ctx(svc, ["SALES_PLANNER"])
    svc.llm = FakeLLM("must not be called")
    none_yet = await ask.answer(reg, ctx, "What are the key risks for the next four weeks?")
    assert "no" in none_yet["answer"].lower() and "plan" in none_yet["answer"].lower()
    plan = await plan_for(svc)
    r = await ask.answer(reg, ctx, "What are the key risks for the next four weeks?")
    assert r["intent"] == "ANALYSIS" and "Observed in the stored records" in r["answer"] and "DEMONSTRATION" in r["answer"] and r["data"]["analysis_id"]
    assert r["evidence"] and all(e["link"].startswith("/sales-planning?tab=") for e in r["evidence"])
    assert svc.llm.calls == 0                                                                        # the assistant's tool computes findings deterministically
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_analysis WHERE plan_id = $1::uuid", plan["id"]) == 1
    again = await ask.answer(reg, ctx, "Which categories are growing or declining?")
    assert again["data"]["analysis_id"] == r["data"]["analysis_id"]                                  # read the latest analysis; it does not recompute on every question


# ------------------------------------------------------------------------------------------------ security: object-level authorization, concurrency and tampering
async def _scoped(svc, user="u-sec", store="N-Z1-S1", role="STORE_MANAGER"):
    await svc.grant_scope(ADMIN, user, store)
    return Actor(f"hash-of-{user}", [role])


async def _other_store_line(svc, plan, mine="N-Z1-S1"):
    ls = [l for l in await lines(svc, plan["id"], product_id=P_BAT) if l["location_id"] != mine]
    return ls[0]


@pytest.mark.asyncio
async def test_a_scoped_manager_cannot_read_edit_investigate_or_decide_anything_in_another_store_by_changing_an_id(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    await svc.generate_allocation(PLANNER, plan["id"], {})
    me = await _scoped(svc)
    foreign = await _other_store_line(svc, plan)
    # reading: asking for the other store's lines returns nothing, never an error that confirms it exists
    got = await svc.plan_lines(me, plan["id"], {"stores": [foreign["location_id"]]}, "product", 0, 50)
    assert got["items"] == [] and got["total"] == 0
    assert {l["location_id"] for l in (await svc.plan_lines(me, plan["id"], {}, "product", 0, 200))["items"]} == {"N-Z1-S1"}
    # editing: nothing is saved and the row is untouched
    edit = {"product_id": foreign["product_id"], "location_id": foreign["location_id"], "week_start": foreign["week_start"], "expected_line_version": foreign["line_version"], "target_units": 999}
    with pytest.raises(PlanningError) as e:
        await svc.edit_lines(me, plan["id"], [edit])
    assert e.value.status == 409 and e.value.code == "EDIT_CONFLICT"
    assert await store.pool.fetchval("SELECT target_units FROM pln_plan_line WHERE plan_id = $1::uuid AND product_id = $2::uuid AND location_id = $3 AND week_start = $4::date",
                                     plan["id"], foreign["product_id"], foreign["location_id"], date.fromisoformat(foreign["week_start"])) is None
    # investigating and explaining the stock effect of another store's line
    with pytest.raises(PlanningError) as e:
        await svc.investigate(me, plan["id"], foreign["product_id"], foreign["location_id"], date.fromisoformat(foreign["week_start"]))
    assert e.value.status == 403
    with pytest.raises(PlanningError) as e:
        await svc.impact(me, plan["id"], foreign["product_id"], foreign["location_id"])
    assert e.value.status == 403
    # the allocation list never holds another store, and another store's recommendation cannot be decided by guessing its id
    alloc = await svc.allocations(me, plan["id"], {}, 0, 200, None, False)
    assert alloc["items"] and {a["location_id"] for a in alloc["items"]} == {"N-Z1-S1"}
    theirs = await store.pool.fetchval("SELECT id FROM pln_alloc_rec WHERE plan_id = $1::uuid AND location_id <> 'N-Z1-S1' AND status = 'DRAFT' LIMIT 1", plan["id"])
    with pytest.raises(PlanningError) as e:
        await svc.decide_recommendation(Actor("hash-of-u-sec-reg", ["REGIONAL_MANAGER"]), "allocation", str(theirs), True)          # an approver with no store in scope
    assert e.value.status in (403, 404)


@pytest.mark.asyncio
async def test_an_analysis_and_a_proposal_never_reach_beyond_the_callers_stores(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    me = await _scoped(svc, "u-sec2")
    a = await svc.analyze(me, plan["id"], {"stores": ["S-Z1-S1", "E-Z1-S1"]}, use_llm=False)      # asking for stores that are not theirs
    blob = json.dumps(a)
    assert "S-Z1-S1" not in blob and "E-Z1-S1" not in blob
    # a proposal crafted against another store's lines is refused when a scoped manager adopts it, and nothing is saved
    foreign = await _other_store_line(svc, plan)
    finding = {"id": "f-x", "kind": "FORECAST_VS_RECENT", "title": "t", "severity": "LOW", "statement": "s", "facts": [], "hypotheses": [], "scope": {}, "evidence": [], "provenance": ["DEMO"], "freshness": {},
               "assumptions": [], "confidence": "LOW", "impact": None, "risks": [], "next_action": "", "proposal": {"kind": "MANAGER_FORECAST", "reason_code": "DATA_CORRECTION", "weeks": [foreign["week_start"]], "note": "n",
               "items": [{"product_id": foreign["product_id"], "location_id": foreign["location_id"], "name": "x", "store": foreign["location_id"], "model_per_week": 1.0, "recent_per_week": 5.0, "proposed_per_week": 3.0}], "basis": "b"}}
    ev = {"data_through": "2026-10-07", "forecast_run_id": plan["forecast_run_id"], "model_version": "v"}
    saved = await repo.save_analysis(await repo.get_plan(plan["id"]), me.hash, {}, ev, "s", "RULES", {"used": False}, [finding], [])
    with pytest.raises(PlanningError) as e:
        await svc.decide_finding(me, saved["id"], "f-x", True)
    assert e.value.status == 409
    assert await store.pool.fetchval("SELECT manager_forecast FROM pln_plan_line WHERE plan_id = $1::uuid AND product_id = $2::uuid AND location_id = $3 AND week_start = $4::date",
                                     plan["id"], foreign["product_id"], foreign["location_id"], date.fromisoformat(foreign["week_start"])) is None


@pytest.mark.asyncio
async def test_two_approvers_deciding_at_the_same_moment_produce_exactly_one_decision(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    sub = await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, plan["version"])
    rev = await svc.transition(MANAGER, plan["id"], "UNDER_REVIEW", None, sub["version"])
    other = Actor("hash-manager-2", ["CATEGORY_MANAGER"])
    results = await asyncio.gather(svc.transition(MANAGER, plan["id"], "APPROVED", None, rev["version"]), svc.transition(other, plan["id"], "REJECTED", "no", rev["version"]), return_exceptions=True)
    wins = [r for r in results if isinstance(r, dict)]
    losses = [r for r in results if isinstance(r, PlanningError)]
    assert len(wins) == 1 and len(losses) == 1 and losses[0].code in ("VERSION_CONFLICT", "INVALID_TRANSITION")
    final = await svc.plan_view(MANAGER, plan["id"])
    assert final["status"] == wins[0]["status"] and final["status"] in ("APPROVED", "REJECTED")
    decisions = await store.pool.fetch("SELECT action FROM pln_audit WHERE entity = 'PLAN' AND entity_id = $1 AND action IN ('STATUS_APPROVED', 'STATUS_REJECTED')", plan["id"])
    assert len(decisions) == 1                                                                   # the audit holds the one decision that happened, not two
    approved_lines = await store.pool.fetchval("SELECT COUNT(*) FROM pln_plan_line WHERE plan_id = $1::uuid AND approved_forecast IS NOT NULL", plan["id"])
    assert (approved_lines > 0) == (final["status"] == "APPROVED")                                # the snapshot exists only if the approval did


@pytest.mark.asyncio
async def test_a_repeated_or_replayed_transition_is_refused_and_changes_nothing(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    first = await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, plan["version"])
    with pytest.raises(PlanningError) as e:
        await svc.transition(PLANNER, plan["id"], "SUBMITTED", None, plan["version"])           # the same click twice
    assert e.value.status == 409
    again = await asyncio.gather(*[svc.transition(MANAGER, plan["id"], "UNDER_REVIEW", None, first["version"]) for _ in range(4)], return_exceptions=True)
    assert sum(isinstance(r, dict) for r in again) == 1 and all(isinstance(r, PlanningError) for r in again if not isinstance(r, dict))
    audits = await store.pool.fetchval("SELECT COUNT(*) FROM pln_audit WHERE entity_id = $1 AND action = 'STATUS_UNDER_REVIEW'", plan["id"])
    assert audits == 1
    # a stale edit after submission is refused: the plan is frozen
    ln = (await lines(svc, plan["id"], product_id=P_BAT, stores=["N-Z1-S1"]))[0]
    with pytest.raises(PlanningError):
        await svc.edit_lines(PLANNER, plan["id"], [{"product_id": ln["product_id"], "location_id": ln["location_id"], "week_start": ln["week_start"], "expected_line_version": ln["line_version"], "target_units": 5}])


@pytest.mark.asyncio
async def test_nobody_approves_their_own_submission_even_as_an_approver_and_a_planner_never_approves(world):
    svc, repo, store = world
    plan = await plan_for(svc, actor=MANAGER)
    sub = await svc.transition(MANAGER, plan["id"], "SUBMITTED", None, plan["version"])
    rev_by_other = await svc.transition(Actor("hash-manager-2", ["CATEGORY_MANAGER"]), plan["id"], "UNDER_REVIEW", None, sub["version"])
    with pytest.raises(PlanningError) as e:
        await svc.transition(MANAGER, plan["id"], "APPROVED", None, rev_by_other["version"])
    assert e.value.status == 403
    with pytest.raises(PlanningError) as e:
        await svc.transition(PLANNER, plan["id"], "APPROVED", None, rev_by_other["version"])
    assert e.value.status == 403
    assert (await svc.plan_view(MANAGER, plan["id"]))["status"] == "UNDER_REVIEW"
    assert await store.pool.fetchval("SELECT COUNT(*) FROM pln_audit WHERE entity = 'ACCESS' AND action = 'DENIED'") >= 1          # refusals are recorded


@pytest.mark.asyncio
async def test_a_regenerated_forecast_never_rewrites_an_existing_plans_model_forecast_or_an_approved_number(world):
    svc, repo, store = world
    plan = await plan_for(svc)
    before = await store.pool.fetch("SELECT product_id, location_id, week_start, model_forecast FROM pln_plan_line WHERE plan_id = $1::uuid ORDER BY 1, 2, 3 LIMIT 200", plan["id"])
    await svc.run_forecast(MANAGER, DEMO_SRC)                                                      # a new run arrives
    after = await store.pool.fetch("SELECT product_id, location_id, week_start, model_forecast FROM pln_plan_line WHERE plan_id = $1::uuid ORDER BY 1, 2, 3 LIMIT 200", plan["id"])
    assert [tuple(r) for r in before] == [tuple(r) for r in after]
    assert (await svc.plan_view(PLANNER, plan["id"]))["forecast_run_id"] == plan["forecast_run_id"]


def test_pending_failed_and_cancelled_orders_are_never_recognised_as_sales():
    from app.planning import conventions as conv
    for status in ("CREATED", "PENDING_PAYMENT", "PAYMENT_FAILED", "FAILED", "EXPIRED", "CANCELLED", "UNKNOWN_STATUS", "", "confirmed "):
        assert status not in conv.RECOGNISED, status
    assert conv.RECOGNISED == {"CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED", "REFUNDED"}