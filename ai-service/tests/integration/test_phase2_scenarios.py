"""The 35 Phase 2 functional scenarios of the stabilization brief (section 8), one test each (S01 ... S35), against a REAL PostgreSQL mart.

Every scenario checks output VALUES and, where relevant, the tool calls that really ran, the permissions, and that nothing in the protected tables changed.
The copilot runs with a scripted model (FakeLLM) so the checks are deterministic; the same questions are asked of the real Gemini in the evaluation suites
(`eval/run_inventory_eval.py`) and the real-browser journeys. Hand-calculated arithmetic is in the comments."""
import hashlib
import os
import uuid
from datetime import date

import numpy as np
import pytest

from app.auth.jwt import verify_token
from app.inventory import forecasting as F
from app.inventory import pipeline
from app.inventory import planning as P
from app.inventory import scenarios as SC
from app.inventory.copilot import AskRequest, InventoryCopilot
from app.inventory.repository import InvalidRequest, InventoryRepository
from app.rag.embedder import Embedder
from app.rag.ingest import DocSpec, Ingestor
from app.rag.retrieval import Retriever
from app.services import build_services
from app.store.db import Store
from tests.conftest import make_settings, make_token
from tests.fakes import FakeLLM
from tests.integration.test_inventory_db import INV_TABLES, skus
from tests.integration.test_rag_db import BowClient, FakeTika

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]
AS_OF = date(2026, 10, 8)
ADMIN = verify_token(make_token(str(uuid.uuid4()), roles=("ADMIN",)), make_settings())
PROTECTED = ("inv_sku", "inv_position", "inv_purchase_order", "inv_event", "inv_demand_daily", "inv_stock_snapshot", "inv_forecast", "inv_assessment",
             "inv_elasticity", "inv_event_impact", "inv_region_share", "inv_model_run")


def flat(daily, n=90):
    return np.full(n, float(daily))


async def fingerprint(pool):
    out = {}
    for t in PROTECTED:
        rows = await pool.fetch(f"SELECT t::text AS r FROM {t} t ORDER BY 1")
        out[t] = hashlib.sha256("\n".join(r["r"] for r in rows).encode()).hexdigest()
    return out


@pytest.fixture
async def world():
    import asyncpg
    conn = await asyncpg.connect(DSN)
    for t in (*INV_TABLES, "chunks", "documents", "product_index", "pending_actions", "usage", "audit", "conversations", "ai_meta"):
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    await pipeline.seed_skus(store.pool, skus(), AS_OF)
    train = await pipeline.train(store.pool)
    llm = FakeLLM(default="This is an AI Recommendation. Nothing was executed.")
    services = await build_services(make_settings(), store=store, llm_inner=llm)
    calls: list[str] = []
    original = services.registry.call

    async def spy(name, args, ctx):
        calls.append(name)
        return await original(name, args, ctx)

    services.registry.call = spy
    yield store, InventoryRepository(store.pool), InventoryCopilot(services), calls, train
    await services.aclose()


# ============================================================================================ forecasting (1-7)
async def test_s01_a_forecast_is_generated_and_persisted(world):
    store, repo, _, _, train = world
    n = await store.pool.fetchval("SELECT count(*) FROM inv_forecast f JOIN inv_model_run r ON r.id = f.run_id WHERE r.is_current")
    assert n == len(skus()) and train["metrics"]["series_modelled"] == len(skus())
    f = await repo.forecast(skus()[1].product_id)
    assert f["status"] == "OK" and len(f["daily"]) == 90 and all(d["lo"] <= d["mean"] <= d["hi"] for d in f["daily"])


async def test_s02_a_stored_forecast_is_retrieved_without_retraining(world):
    store, repo, _, _, _ = world
    pid = skus()[1].product_id
    runs_before = await store.pool.fetchval("SELECT count(*) FROM inv_model_run")
    a, b = await repo.forecast(pid), await repo.forecast(pid)
    assert a["forecast_created_at"] == b["forecast_created_at"] and a["daily"] == b["daily"]
    assert await store.pool.fetchval("SELECT count(*) FROM inv_model_run") == runs_before


async def test_s03_the_forecast_is_compared_with_the_baseline_on_exactly_the_same_windows(world):
    _, repo, _, _, _ = world
    holdout = (await repo.model_info())["metrics"]["holdout"]
    assert {"ridge_regression", "holt_winters", "seasonal_naive_7"} <= set(holdout)
    assert len({m["n_days"] for m in holdout.values()}) == 1                      # every model, baselines included, scored on the same days
    assert all(m["wape"] is not None for m in holdout.values())


async def test_s04_insufficient_history_gives_no_forecast_and_says_so(world):
    store, repo, _, _, _ = world
    victim = skus()[0].product_id
    await store.pool.execute("DELETE FROM inv_demand_daily WHERE product_id=$1::uuid AND day < $2", victim, date(2026, 9, 18))
    await pipeline.train(store.pool)
    f = await repo.forecast(victim)
    assert f["status"] == "INSUFFICIENT_DATA" and f["daily"] == [] and f["reason"].startswith("Insufficient data to produce a reliable forecast")
    assert (await repo.assessment(victim))["reorder_qty"] is None


def test_s05_zero_demand_forecasts_zero_for_every_model():
    for m in F.MODEL_NAMES:
        out = F.predict(m, np.zeros(200), date(2024, 1, 1), 90)
        assert (out >= 0).all() and out.max() < 0.01, m
    a = P.assess(AS_OF, P.Position(40), P.Reference(lead_time_days=7), flat(0.0), 0.0)
    assert a.reorder_qty == 0 and a.risk_level == "LOW"                            # nothing is sold: no stock-out risk, nothing to order


def test_s06_missing_dates_are_rebuilt_from_observed_regions_not_zeroed():
    n = 60
    units = np.full((2, n), 5.0)
    units[1, 10:13] = np.nan
    c = F.clean_series(units, np.zeros((2, n), dtype=bool), np.zeros(n), np.full(n, -1), np.array([0.5, 0.5]))
    assert c.raw[10] == pytest.approx(10.0)                                        # 5 observed / 0.5 share
    gone = np.full((2, n), np.nan)
    assert F.clean_series(gone, np.zeros((2, n), dtype=bool), np.zeros(n), np.full(n, -1), np.array([0.5, 0.5])).counts["missing_days"] == n


async def test_s07_forecast_provenance_names_the_run_the_model_the_window_and_the_data_class(world):
    _, repo, _, _, _ = world
    p = await repo.provenance()
    assert uuid.UUID(p["run_id"]) and p["model_name"] and p["model_version"] and p["training_start"] < p["training_end"] <= p["as_of"]
    assert p["demand_history"] == "POC_SYNTHETIC" and p["cost_supplier_lead_time_moq_regions_events"] == "POC_REFERENCE" and p["catalogue_and_prices"].startswith("DSPORTS_LIVE")


# ============================================================================================ stock risk and replenishment (8-13)
def test_s08_high_stock_out_risk_is_identified():
    # 50 units, 12 a day, lead time 7: 50 / 12 = 4.17 days of cover < 7 days lead time
    a = P.assess(AS_OF, P.Position(50), P.Reference(lead_time_days=7), flat(12), 3.0)
    assert a.risk_level == "HIGH" and a.priority == "HIGH" and a.stockout_date == date(2026, 10, 12)


def test_s09_days_of_cover_is_stock_over_daily_demand():
    assert P.assess(AS_OF, P.Position(50), P.Reference(lead_time_days=7), flat(12), 3.0).days_of_cover == 4.2        # 50 / 12 = 4.1667 -> 4.2
    assert P.assess(AS_OF, P.Position(120), P.Reference(lead_time_days=7), flat(10), 3.0).days_of_cover == 12.0       # 120 / 10


def test_s10_replenishment_matches_the_hand_calculated_example():
    # demand during lead time 12 * 7 = 84; safety stock 20; position 50; no review period -> 84 + 20 - 50 = 54
    ref = P.Reference(lead_time_days=7, moq=1, case_pack=1)
    a = P.assess(AS_OF, P.Position(50), ref, flat(12), sigma_daily=0.0001, review_days=0,
                 interval_fn=lambda h, s: (s - 20 / P.SERVICE_Z * P._Z80, s + 20 / P.SERVICE_Z * P._Z80))
    assert a.detail["demand_during_lead_time"] == 84.0 and a.safety_stock == 20 and a.reorder_qty == 54


def test_s11_late_incoming_stock_does_not_hide_the_risk_and_recommends_expediting():
    ref = P.Reference(lead_time_days=7, moq=1, case_pack=1)
    late = P.assess(AS_OF, P.Position(50, arrivals=((40, 500),)), ref, flat(12), 3.0)        # 50 units last 4 days; the open order lands on day 40
    assert late.risk_level == "HIGH" and late.detail["action"] == "EXPEDITE_INCOMING"
    on_time = P.assess(AS_OF, P.Position(50, arrivals=((3, 500),)), ref, flat(12), 3.0)
    assert on_time.risk_level in ("LOW", "MEDIUM") and on_time.detail["action"] != "EXPEDITE_INCOMING"


async def test_s12_a_missing_supplier_lead_time_is_reported_and_no_quantity_is_invented(world):
    store, repo, _, _, _ = world
    pid = skus()[2].product_id
    await store.pool.execute("UPDATE inv_sku SET lead_time_days = NULL WHERE product_id=$1::uuid", pid)
    await pipeline.train(store.pool)
    a = await repo.assessment(pid)
    assert a["status"] == "MISSING_LEAD_TIME" and a["reorder_qty"] is None and "lead time" in a["reason"].lower()


def test_s13_a_product_with_no_incoming_purchase_order_is_planned_from_stock_alone():
    a = P.assess(AS_OF, P.Position(50), P.Reference(lead_time_days=7, moq=1, case_pack=1), flat(12), 3.0)
    assert a.incoming == 0 and a.reorder_qty > 0 and a.detail["action"] == "ORDER"


# ============================================================================================ overstock and clearance (14-19)
def test_s14_overstocked_products_are_identified():
    # 1,250 on hand vs a 90-day forecast of 400 -> excess 850
    a = P.assess(AS_OF, P.Position(1250, oldest_age_days=60), P.Reference(lead_time_days=10, cost=500.0), flat(400 / 90), 1.0)
    assert a.excess_units == 850 and a.overstock_level == "HIGH" and a.excess_value == 425000.0 and a.reorder_qty == 0


def test_s15_slow_moving_products_are_identified():
    assert P.health_class(130, 40, None) == "Slow Moving" and P.health_class(95, 20, -30.0) == "Slow Moving"
    assert P.health_class(20, 10, None) == "Healthy"


async def test_s16_inventory_older_than_90_days_is_identified(world):
    _, repo, _, _, _ = world
    old = await repo.overstock(min_age_days=90, limit=200)
    assert all(r["age_days"] >= 90 for r in old)
    buckets = (await repo.aging_distribution())["buckets"]
    assert [b["bucket"] for b in buckets][-1] == "120+ days" and sum(b["skus"] for b in buckets) == (await repo.aging_distribution())["total_skus"]


async def test_s17_clearance_candidates_are_ranked_by_net_benefit(world):
    _, repo, _, _, _ = world
    out = await repo.clearance_candidates(limit=15)
    benefits = [c["net_benefit"] for c in out["candidates"]]
    assert benefits == sorted(benefits, reverse=True) and all(c["excess_units"] > 0 for c in out["candidates"])


def test_s18_the_margin_floor_is_enforced():
    def it(name, cost):
        return SC.Item(name, name, 1000.0, cost, 2000, (), tuple([10.0] * 60), SC.Elasticity("Jersey", 2.0, 1.6, 2.4, 0.1, 500, "HISTORICAL"), 200, 1500)
    out = SC.evaluate([it("Thin margin", 900.0), it("Fat margin", 300.0)], [0.0, 30.0, 50.0], 14)
    plan = {p["name"]: p["best_discount_pct"] for p in out["per_product_best"]}
    assert plan["Thin margin"] is None and plan["Fat margin"] in (30.0, 50.0)          # 30% off 1000 leaves 700: a 900 cost would be a loss; the 10% floor blocks it
    assert out["recommended_discount_pct"] is None


async def test_s19_a_recommendation_never_modifies_real_prices(world):
    store, repo, _, _, _ = world
    before = [dict(r) for r in await store.pool.fetch("SELECT product_id, price, cost FROM inv_sku ORDER BY product_id")]
    fp = await fingerprint(store.pool)
    await repo.scenario(sport="Cricket", discounts=[30, 50], actor_hash="s19")
    await repo.clearance_candidates(actor_hash="s19")
    assert [dict(r) for r in await store.pool.fetch("SELECT product_id, price, cost FROM inv_sku ORDER BY product_id")] == before
    assert await fingerprint(store.pool) == fp


# ============================================================================================ events and promotions (20-25)
async def test_s20_event_adjusted_demand_comes_from_documented_assumptions(world):
    _, repo, _, _, _ = world
    ev = await repo.event_impact("cricket-premier-2026")
    assert ev["impact"] and all(i["basis"] in ("HISTORICAL", "HISTORICAL_SPORT_LEVEL", "HISTORICAL_OTHER_SPORT", "POC_ASSUMPTION") for i in ev["impact"])
    assert ev["event"]["data_source"] == "POC_REFERENCE" and all(r["scenario"] == "EVENT:cricket-premier-2026" for r in ev["readiness"])


def test_s21_unsupported_event_assumptions_are_flagged_not_measured():
    from app.inventory import events as E
    event = {"id": "x", "sport": "Cricket", "popularity": 1.2, "start_date": date(2026, 11, 5), "end_date": date(2026, 12, 2), "regions": {}}
    est = E.estimate_upcoming(event, [], ["Bat"])                                     # no past event to learn from
    assert est["Bat"]["basis"] == "POC_ASSUMPTION"


def test_s22_baseline_30_and_50_percent_scenarios_are_compared():
    item = SC.Item("p", "Jersey", 1000.0, 600.0, 5000, (), tuple([10.0] * 60), SC.Elasticity("Jersey", 2.0, 1.6, 2.4, 0.1, 500, "HISTORICAL"), 0, 0)
    out = SC.evaluate([item], [30.0, 50.0], 14)                                       # baseline is always included
    assert [s["discount_pct"] for s in out["scenarios"]] == [0.0, 30.0, 50.0]
    sold = [s["units_sold"] for s in out["scenarios"]]
    assert sold == sorted(sold) and sold[0] == 140.0                                  # 10 a day * 14


def test_s23_the_revenue_margin_inventory_trade_off_is_visible_in_the_numbers():
    item = SC.Item("p", "Jersey", 1000.0, 600.0, 5000, (), tuple([10.0] * 60), SC.Elasticity("Jersey", 2.0, 1.6, 2.4, 0.1, 500, "HISTORICAL"), 0, 0)
    base, d30 = SC.evaluate([item], [30.0], 14)["scenarios"][:2]
    assert d30["units_sold"] > base["units_sold"] and d30["inventory_reduction_units"] > 0           # more units leave the shelf ...
    assert d30["margin"] < base["margin"] and d30["min_unit_margin_pct"] < base["min_unit_margin_pct"]  # ... at a lower margin per unit


async def test_s24_unsupported_or_impossible_promotion_inputs_are_rejected(world):
    _, repo, _, _, _ = world
    for bad in ([95], [-10], [float("nan")], [100]):
        with pytest.raises(InvalidRequest):
            await repo.scenario(sport="Cricket", discounts=bad)
    with pytest.raises(InvalidRequest):
        await repo.scenario(sport="Cricket", discounts=[30], days=0)


async def test_s25_the_copilot_cannot_execute_a_promotion(world):
    store, _, cop, calls, _ = world
    fp = await fingerprint(store.pool)
    for q in ("Launch a 30% promotion on all jerseys now", "Go ahead and apply a 50% discount to all cricket products"):
        r = await cop.ask(ADMIN, AskRequest(message=q))
        assert r.status == "REFUSED" and r.tools == []
    assert calls == [] and await fingerprint(store.pool) == fp


# ============================================================================================ copilot and RAG (26-35)
async def test_s26_which_products_are_at_risk(world):
    _, repo, cop, calls, _ = world
    r = await cop.ask(ADMIN, AskRequest(message="Which products are at risk of stock-out?"))
    assert r.intent == "STOCK_RISK" and r.status == "OK" and "get_stock_risk" in calls
    assert str((await repo.overview())["at_risk"]) in r.headline                       # the number comes from the stored assessment, not the model


async def test_s27_why_is_a_product_at_risk(world):
    _, repo, cop, calls, _ = world
    first = await cop.ask(ADMIN, AskRequest(message="Which products are at risk of stock-out?"))
    r = await cop.ask(ADMIN, AskRequest(message="Why?", conversationId=first.conversationId))
    assert r.status == "OK" and r.intent in ("WHY", "STOCK_RISK") and set(calls) <= {"get_stock_risk", "get_inventory", "get_demand_forecast", "get_inventory_snapshot", "get_sales_history"}   # read tools only


async def test_s28_what_should_be_replenished(world):
    _, _, cop, calls, _ = world
    r = await cop.ask(ADMIN, AskRequest(message="What should we replenish this week?"))
    assert r.intent == "REPLENISH" and "get_replenishment_recommendation" in calls and r.recommendationLabel


async def test_s29_which_products_should_go_on_clearance(world):
    _, _, cop, calls, _ = world
    r = await cop.ask(ADMIN, AskRequest(message="Which products should go on clearance?"))
    assert r.intent == "CLEARANCE" and "get_clearance_candidates" in calls and r.status == "OK"


@pytest.mark.parametrize("pct", [30, 50])
async def test_s30_s31_what_happens_with_a_discount(world, pct):
    store, _, cop, calls, _ = world
    fp = await fingerprint(store.pool)
    r = await cop.ask(ADMIN, AskRequest(message=f"What happens if we give {pct}% discount?"))
    assert r.status == "OK" and "run_promotion_scenario" in calls and calls.count("run_promotion_scenario") == 1
    assert await fingerprint(store.pool) == fp                                          # a calculation, not an action


async def test_s32_the_upcoming_tournament_is_explained_from_event_data(world):
    _, _, cop, calls, _ = world
    r = await cop.ask(ADMIN, AskRequest(message="How will the upcoming cricket tournament affect demand?"))
    assert r.status == "OK" and r.intent == "EVENT" and "get_event_impact" in calls


async def test_s33_an_internal_policy_answer_carries_citations_and_stays_internal(world):
    store, _, _, _, _ = world
    embedder = Embedder(BowClient(), "bow-test", 768, cache_ttl_s=0)
    ing = Ingestor(store, FakeTika(), embedder, make_settings(AI_CHUNK_TOKENS=120, AI_CHUNK_OVERLAP_TOKENS=20))
    await ing.ingest(DocSpec(file="p.md", source="clearance-policy.md", document_type="inv_policy", title="Clearance policy"),
                     b"Clearance policy: stock older than 90 days may be discounted; discounts above 50 percent need approval from the Head of Merchandising.")
    retriever = Retriever(store, embedder, 20, 0.0)
    hits = (await retriever.search_chunks("discount above 50 percent approval", limit=5, internal=True)).hits
    assert hits and hits[0].payload["source"] == "clearance-policy.md" and hits[0].payload["document_type"] == "inv_policy" and hits[0].payload["title"] == "Clearance policy"
    assert (await retriever.search_chunks("discount above 50 percent approval", limit=5)).hits == []        # a customer search never sees it


async def test_s34_unavailable_data_gets_an_honest_limitation_not_a_guess(world):
    _, _, cop, calls, _ = world
    r = await cop.ask(ADMIN, AskRequest(message="What is the demand forecast for the Zorblax 9000 spaceship?"))
    assert "couldn't find" in r.message.lower() and not any(b.get("type") == "forecast_chart" for b in r.blocks)
    r2 = await cop.ask(ADMIN, AskRequest(message="What were our Mumbai store sales last Diwali?"))
    assert r2.status == "UNAVAILABLE" and r2.tools == [] and "won't guess" in r2.message and calls == []        # names it cannot place: no tool ran, no number was produced


async def test_s35_a_request_to_create_a_purchase_order_or_change_a_price_changes_nothing(world):
    store, _, cop, calls, _ = world
    fp = await fingerprint(store.pool)
    for q in ("Create a purchase order for 100 cricket bats", "Change the price of the MRF cricket bat to 5000", "Transfer 200 units of stock to the East warehouse"):
        r = await cop.ask(ADMIN, AskRequest(message=q))
        assert r.status == "REFUSED" and r.tools == []
    assert calls == [] and await fingerprint(store.pool) == fp
    audit = await store.pool.fetch("SELECT detail->>'status' AS s FROM inv_audit WHERE action='QUERY'")
    assert len(audit) >= 3 and all(a["s"] == "REFUSED" for a in audit)
