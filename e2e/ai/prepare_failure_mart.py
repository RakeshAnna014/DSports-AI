"""Builds a small REAL inventory mart in the throwaway test database (dsports_ai_test) in which ONE product has too little history to forecast, then trains it.
Used by the failure validation: an AI service instance started against this database shows how the product behaves with insufficient data (no invented forecast).
Prints JSON {victimId, victimName}.   Needs AI_TEST_DATABASE_URL (the validation script derives it from the AI service .env, never printing it)."""
import asyncio
import json
import os
import sys
import uuid
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "ai-service"
sys.path.insert(0, str(ROOT))
from app.inventory import generator as G  # noqa: E402
from app.inventory import pipeline  # noqa: E402
from app.store.db import Store  # noqa: E402

AS_OF = date.today()


async def main() -> None:
    dsn = os.environ["AI_TEST_DATABASE_URL"]
    import asyncpg
    conn = await asyncpg.connect(dsn)
    for t in ("inv_forecast", "inv_assessment", "inv_event_impact", "inv_elasticity", "inv_region_share", "inv_model_run", "inv_demand_daily", "inv_stock_snapshot", "inv_position",
              "inv_purchase_order", "inv_event", "inv_region", "inv_sku", "inv_scenario_run", "inv_dq_report", "inv_audit"):
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(dsn, 30, 768)
    skus, k = [], 0
    for sport, cat, price in [("Cricket", "Bat", 7000.0), ("Cricket", "Gloves", 3500.0), ("Cricket", "Jersey", 1500.0), ("Football", "Jersey", 1500.0), ("Football", "Shoes", 4200.0),
                              ("Running", "Shoes", 5000.0)]:
        for i in range(2):
            k += 1
            skus.append(G.SkuRef(str(uuid.UUID(int=k)), f"{sport[:2]}-{cat[:3]}-{i}", f"{sport} {cat} {i}", sport, cat, "Brand", price, price * 1.2, 100))
    await pipeline.seed_skus(store.pool, skus, AS_OF)
    victim = skus[0]
    await store.pool.execute("DELETE FROM inv_demand_daily WHERE product_id=$1::uuid AND day < $2", victim.product_id, AS_OF.fromordinal(AS_OF.toordinal() - 20))   # ~20 days remain
    await pipeline.train(store.pool)
    await store.close()
    print(json.dumps({"victimId": victim.product_id, "victimName": victim.name}))


asyncio.run(main())
