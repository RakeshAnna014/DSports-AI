"""Audits the REAL DSports transactional database for what Inventory Intelligence could use, and prints JSON.
This is why the Phase 2 history is a labelled synthetic POC dataset: see docs/ai/phase2/02-data-model.md.   Usage: python e2e/ai/inventory_real_data_audit.py"""
import json
import sys

import stack as s


def q(sql):
    return [row.split("|") for row in s.sql(sql).splitlines() if row]


def one(sql):
    r = q(sql)
    return r[0] if r else []


orders = one("select count(*), min(placed_at)::date, max(placed_at)::date, count(distinct user_id) from orders")
status = {k: int(v) for k, v in q("select status, count(*) from orders group by 1")}
items = one("select count(*), count(distinct product_id), sum(quantity) from order_items")
dup = one("select count(*) from (select order_number from orders group by 1 having count(*) > 1) x")
inv = one("select count(*), count(distinct warehouse_id), sum(available_quantity), min(available_quantity), max(available_quantity) from inventory")
tables = {r[0] for r in q("select table_name from information_schema.tables where table_schema='public'")}
cols = lambda t: {r[0] for r in q(f"select column_name from information_schema.columns where table_name='{t}'")}   # noqa: E731
days_with_orders = one("select count(distinct placed_at::date) from orders where status <> 'CANCELLED'")
by_month = q("select to_char(placed_at,'YYYY-MM'), count(*) from orders group by 1 order by 1")
returns = one("select count(*) from return_requests")
ship_cities = {r[0] for r in q("select distinct coalesce(state,'?') from shipments")}
report = {
    "orders": {"count": int(orders[0]), "first": orders[1], "last": orders[2], "distinct_customers": int(orders[3]), "by_status": status, "by_month": {m: int(n) for m, n in by_month},
               "days_with_non_cancelled_orders": int(days_with_orders[0]), "duplicate_order_numbers": int(dup[0])},
    "order_items": {"rows": int(items[0]), "distinct_products": int(items[1]), "units": int(items[2])},
    "inventory": {"rows": int(inv[0]), "warehouses": int(inv[1]), "units": int(inv[2]), "min": int(inv[3]), "max": int(inv[4]), "has_movements_table": "inventory_movements" in tables,
                  "columns": sorted(cols("inventory"))},
    "missing_in_dsports": {"purchase_cost": not any("cost" in c for c in cols("products") | cols("prices")), "supplier_or_lead_time": not any(t for t in tables if "supplier" in t or "purchase" in t),
                           "stock_history": "inventory_movements" not in tables, "regional_stock": int(inv[1]) <= 1, "events_calendar": not any("event" in t and "outbox" not in t for t in tables)},
    "returns": int(returns[0]), "shipment_states": sorted(ship_cities),
    "verdict": "Too little and too recent history, mostly test traffic, one warehouse, no stock movements, no cost / supplier / lead time / regions: not enough to forecast from.",
}
print(json.dumps(report, indent=1))
sys.exit(0)
