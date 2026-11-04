"""Inventory Intelligence against a REAL PostgreSQL (throwaway database): seeding, labelling, training, model versioning, planning results,
failure behaviour (insufficient data, missing lead time) and the separation of internal documents from customer retrieval."""
import os
import uuid
from datetime import date

import pytest

from app.inventory import generator as G
from app.inventory import pipeline
from app.inventory.repository import InvalidRequest, InventoryRepository, InventoryUnavailable
from app.rag.embedder import Embedder
from app.rag.ingest import DocSpec, Ingestor
from app.rag.retrieval import Retriever
from app.store.db import Store
from tests.conftest import make_settings
from tests.integration.test_rag_db import BowClient, FakeTika

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]
AS_OF = date(2026, 10, 8)
INV_TABLES = ("inv_forecast", "inv_assessment", "inv_event_impact", "inv_elasticity", "inv_region_share", "inv_model_run", "inv_demand_daily", "inv_stock_snapshot",
              "inv_position", "inv_purchase_order", "inv_event", "inv_region", "inv_sku", "inv_scenario_run", "inv_dq_report", "inv_audit")


def skus():
    out, k = [], 0
    for sport, cat, price in [("Cricket", "Bat", 7000.0), ("Cricket", "Gloves", 3500.0), ("Cricket", "Jersey", 1500.0), ("Football", "Jersey", 1500.0),
                              ("Football", "Shoes", 4200.0), ("Running", "Shoes", 5000.0), ("Tennis", "Jersey", 1800.0), ("Tennis", "Ball", 700.0)]:
        for i in range(2):
            k += 1
            out.append(G.SkuRef(str(uuid.UUID(int=k)), f"{sport[:2]}-{cat[:3]}-{i}", f"{sport} {cat} {i}", sport, cat, "Brand", price, price * 1.2, 100))
    return out


@pytest.fixture
async def mart():
    import asyncpg
    conn = await asyncpg.connect(DSN)
    for t in (*INV_TABLES, "chunks", "documents", "product_index", "pending_actions", "usage", "audit", "conversations", "ai_meta"):
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    summary = await pipeline.seed_skus(store.pool, skus(), AS_OF)
    yield store, summary
    await store.close()


async def test_seed_labels_every_row_as_poc_data_and_never_as_real(mart):
    store, summary = mart
    assert summary["data_source"] == "POC_SYNTHETIC" and summary["skus"] == 16
    allowed = {"POC_SYNTHETIC", "POC_REFERENCE"}
    for table in ("inv_demand_daily", "inv_stock_snapshot", "inv_position", "inv_purchase_order", "inv_sku", "inv_event", "inv_region"):
        seen = {r["data_source"] for r in await store.pool.fetch(f"SELECT DISTINCT data_source FROM {table}")}
        assert seen and seen <= allowed, (table, seen)
    assert await store.pool.fetchval("SELECT count(*) FROM inv_event WHERE name NOT LIKE '%(POC)%'") == 0


async def test_training_persists_a_versioned_run_with_metrics_and_forecasts_for_every_sku(mart):
    store, _ = mart
    out = await pipeline.train(store.pool, actor_hash="admin-hash", correlation_id="cid-1")
    run = await store.pool.fetchrow("SELECT * FROM inv_model_run WHERE is_current")
    assert str(run["id"]) == out["run_id"] and run["model_name"] in ("ridge_regression", "holt_winters", "seasonal_naive_7", "moving_average_28")
    assert run["model_version"] and run["training_start"] < run["training_end"] and run["horizon_days"] == 90 and run["n_series"] == 16 and run["data_source"] == "POC_SYNTHETIC"
    metrics = run["metrics"] if isinstance(run["metrics"], dict) else __import__("json").loads(run["metrics"])
    assert {"selection", "holdout", "selected", "baseline", "interval_coverage_holdout"} <= metrics.keys() and "synthetic" in metrics["note"].lower()
    rows = await store.pool.fetch("SELECT * FROM inv_forecast WHERE run_id = $1", run["id"])
    assert len(rows) == 16 and all(r["status"] == "OK" and len(r["daily_mean"]) == 90 and len(r["daily_lo"]) == 90 and r["model_version"] == run["model_version"] for r in rows)
    assert all(lo <= m <= hi for r in rows for lo, m, hi in zip(r["daily_lo"], r["daily_mean"], r["daily_hi"], strict=True))
    assert await store.pool.fetchval("SELECT count(*) FROM inv_audit WHERE action='TRAIN' AND actor_hash='admin-hash' AND correlation_id='cid-1'") == 1
    assert await store.pool.fetchval("SELECT count(*) FROM inv_assessment WHERE run_id=$1 AND scenario='BASELINE'", run["id"]) == 16


async def test_a_second_run_keeps_history_and_is_identical_for_identical_data(mart):
    store, _ = mart
    a = await pipeline.train(store.pool)
    first = {str(r["product_id"]): list(r["daily_mean"]) for r in await store.pool.fetch("SELECT product_id, daily_mean FROM inv_forecast WHERE run_id=$1", uuid.UUID(a["run_id"]))}
    b = await pipeline.train(store.pool)
    assert await store.pool.fetchval("SELECT count(*) FROM inv_model_run") == 2 and await store.pool.fetchval("SELECT count(*) FROM inv_model_run WHERE is_current") == 1
    assert str(await store.pool.fetchval("SELECT id FROM inv_model_run WHERE is_current")) == b["run_id"]
    second = {str(r["product_id"]): list(r["daily_mean"]) for r in await store.pool.fetch("SELECT product_id, daily_mean FROM inv_forecast WHERE run_id=$1", uuid.UUID(b["run_id"]))}
    assert first == second                                                           # the pipeline is deterministic


async def test_assessments_are_internally_consistent_and_use_the_planning_rules(mart):
    store, _ = mart
    await pipeline.train(store.pool)
    repo = InventoryRepository(store.pool)
    ov = await repo.overview()
    assert ov["total"] == 16 and ov["healthy"] + ov["watch"] + ov["slow_only"] + ov["critical_aging"] == 16 and ov["data_source"] == "POC_SYNTHETIC"
    for r in await repo.stock_risks(limit=50):
        assert r["risk_level"] in ("HIGH", "MEDIUM") and r["on_hand"] is not None
        if r["risk_level"] == "HIGH" and r["days_of_cover"] is not None:
            assert r["days_of_cover"] < r["lead_time_days"] or r["p_stockout_lt"] >= 0.5          # the documented HIGH rule
    for r in await repo.replenishment(limit=50):
        if r["reorder_qty"]:
            assert r["reorder_qty"] >= (r["moq"] or 1) and r["arrival_date"] > r["order_by"] and r["safety_stock"] is not None
    for r in await repo.overstock(limit=50):
        assert r["excess_units"] > 0 and r["overstock_level"] in ("HIGH", "MEDIUM")


async def test_insufficient_history_gets_no_forecast_and_says_so(mart):
    store, _ = mart
    victim = skus()[0].product_id
    await store.pool.execute("DELETE FROM inv_demand_daily WHERE product_id=$1::uuid AND day < $2", victim, date(2026, 9, 18))     # only ~20 days remain
    out = await pipeline.train(store.pool)
    repo = InventoryRepository(store.pool)
    f = await repo.forecast(victim)
    assert f["status"] == "INSUFFICIENT_DATA" and f["reason"].startswith("Insufficient data to produce a reliable forecast") and f["daily"] == []
    a = await repo.assessment(victim)
    assert a["status"] == "INSUFFICIENT_DATA" and a["risk_level"] is None and a["reorder_qty"] is None        # no number is invented
    assert out["metrics"]["series_modelled"] == 15
    others = await repo.assessment(skus()[1].product_id)
    assert others["status"] in ("OK", "MISSING_LEAD_TIME")
    assert any(c["check"] == "insufficient_history" and c["status"] == "WARN" for c in (await repo.data_quality())["checks"])


async def test_a_missing_supplier_lead_time_is_reported_and_no_order_quantity_is_proposed(mart):
    store, _ = mart
    pid = skus()[2].product_id
    await store.pool.execute("UPDATE inv_sku SET lead_time_days = NULL WHERE product_id=$1::uuid", pid)
    await pipeline.train(store.pool)
    a = await InventoryRepository(store.pool).assessment(pid)
    assert a["status"] == "MISSING_LEAD_TIME" and a["reorder_qty"] is None and "lead time" in a["reason"].lower() and a["risk_level"] in ("HIGH", "MEDIUM", "LOW")
    dq = await InventoryRepository(store.pool).data_quality()
    assert next(c for c in dq["checks"] if c["check"] == "reference_data")["missing_lead_time"] == 1


async def test_the_repository_refuses_politely_before_any_training(mart):
    store, _ = mart
    with pytest.raises(InventoryUnavailable) as e:
        await InventoryRepository(store.pool).overview()
    assert "train" in str(e.value).lower()


async def test_scenarios_are_logged_and_never_touch_prices(mart):
    store, _ = mart
    await pipeline.train(store.pool)
    before = await store.pool.fetch("SELECT product_id, price, cost FROM inv_sku ORDER BY product_id")
    out = await InventoryRepository(store.pool).scenario(sport="Cricket", discounts=[30, 50], actor_hash="who")
    assert {s["discount_pct"] for s in out["scenarios"]} == {0.0, 30.0, 50.0} and "nothing" in out["recommendation_status"].lower() or "decision support" in out["recommendation_status"].lower()
    assert await store.pool.fetchval("SELECT count(*) FROM inv_scenario_run WHERE actor_hash='who'") == 1
    assert [dict(r) for r in await store.pool.fetch("SELECT product_id, price, cost FROM inv_sku ORDER BY product_id")] == [dict(r) for r in before]
    with pytest.raises(InvalidRequest):                                                      # an impossible promotion is a client error, not "no data"
        await InventoryRepository(store.pool).scenario(sport="Cricket", discounts=[95])
    with pytest.raises(InvalidRequest):
        await InventoryRepository(store.pool).scenario(sport="Cricket", discounts=[30], days=0)


async def test_event_impact_is_estimated_from_past_events_and_labelled(mart):
    store, _ = mart
    await pipeline.train(store.pool)
    repo = InventoryRepository(store.pool)
    ev = await repo.event_impact("cricket-premier-2026")
    assert ev["impact"] and all(i["basis"] in ("HISTORICAL", "HISTORICAL_SPORT_LEVEL", "HISTORICAL_OTHER_SPORT", "POC_ASSUMPTION") for i in ev["impact"])
    assert any(i["basis"] == "HISTORICAL" and i["uplift"] > 1.2 for i in ev["impact"])             # the injected tournament effect was found
    assert ev["readiness"] and all(r["scenario"] == "EVENT:cricket-premier-2026" for r in ev["readiness"])


# ---------------------------------------------------------------- internal knowledge is invisible to customers

async def test_internal_documents_are_never_returned_to_customer_retrieval_and_vice_versa(mart):
    store, _ = mart
    embedder = Embedder(BowClient(), "bow-test", 768, cache_ttl_s=0)
    ingestor = Ingestor(store, FakeTika(), embedder, make_settings(AI_CHUNK_TOKENS=120, AI_CHUNK_OVERLAP_TOKENS=20))
    text = "Clearance policy for aged inventory: discounts above 50 percent need approval from the Head of Merchandising."
    await ingestor.ingest(DocSpec(file="a.md", source="internal.md", document_type="inv_policy", title="Internal clearance policy"), text.encode())
    await ingestor.ingest(DocSpec(file="b.md", source="public.md", document_type="policy", title="Public return policy"),
                          b"Return policy: items can be returned within 7 days of delivery for a refund, clearance items excepted.")
    retriever = Retriever(store, embedder, 20, 0.0)
    customer = await retriever.search_chunks("clearance policy discounts approval", limit=10)
    internal = await retriever.search_chunks("clearance policy discounts approval", limit=10, internal=True)
    assert customer.hits and all(h.payload["source"] == "public.md" and not h.payload["document_type"].startswith("inv_") for h in customer.hits)
    assert internal.hits and all(h.payload["source"] == "internal.md" and h.payload["document_type"] == "inv_policy" for h in internal.hits)
    # even when a customer asks for the internal type explicitly, the SQL filter wins
    sneaky = await retriever.search_chunks("Head of Merchandising approval", document_types=["inv_policy"], limit=10)
    assert sneaky.hits == []


async def test_seeding_twice_is_reproducible_and_never_duplicates_records(mart):
    store, first = mart
    async def counts():
        return {t: await store.pool.fetchval(f"SELECT count(*) FROM {t}") for t in ("inv_sku", "inv_demand_daily", "inv_stock_snapshot", "inv_position", "inv_purchase_order", "inv_event", "inv_region")}
    async def digest():
        return await store.pool.fetchval("SELECT md5(string_agg(concat_ws(',', product_id, region, day, units, discount_pct), '|' ORDER BY product_id, region, day)) FROM inv_demand_daily")
    c1, d1 = await counts(), await digest()
    again = await pipeline.seed_skus(store.pool, skus(), AS_OF)
    assert again["demand_rows"] == first["demand_rows"] and await counts() == c1          # same rows, not twice the rows
    assert await digest() == d1                                                           # byte-identical demand history
    await pipeline.train(store.pool)
    await pipeline.seed_skus(store.pool, skus(), AS_OF)                                  # re-seeding drops the old model results with the data they were built from
    assert await store.pool.fetchval("SELECT count(*) FROM inv_model_run") == 0 and await store.pool.fetchval("SELECT count(*) FROM inv_forecast") == 0
