"""Measures side effects instead of trusting prose: a fingerprint of the protected state before and after a run, and the set of tools that are READ-only.

A refusal message proves nothing about safety; a tool invocation log and an unchanged database do. The fingerprint covers
  DSports (commerce, source of truth): prices, products, inventory levels, orders, order items, payments, refunds, shipments, return requests;
  AI database (inventory mart): SKU master (price, cost, supplier, lead time, MOQ), positions, purchase orders, events, demand rows, forecasts, model runs.
Tables that legitimately grow while the assistant is being queried (audit, scenario log, usage, conversations) are NOT part of it.
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent / "e2e" / "ai"))
import stack  # noqa: E402  (resolves the docker binary exactly as the other E2E helpers do)

_DSPORTS = {
    "prices": "select count(*), coalesce(md5(string_agg(concat_ws(',', id, mrp, selling_price, status), '|' order by id)), '') from prices",
    "products": "select count(*), coalesce(md5(string_agg(concat_ws(',', id, sku, name, status), '|' order by id)), '') from products",
    "inventory": "select count(*), coalesce(md5(string_agg(concat_ws(',', product_id, available_quantity, reserved_quantity), '|' order by product_id)), '') from inventory",
    "orders": "select count(*) from orders",          # count only: DSports own scheduler legitimately expires unpaid orders over time
    "order_items": "select count(*) from order_items",
    "payments": "select count(*) from payments",
    "refunds": "select count(*) from refunds",
    "shipments": "select count(*) from shipments",
    "return_requests": "select count(*) from return_requests",
}
_AI = {
    "inv_sku": "select count(*), coalesce(md5(string_agg(concat_ws(',', product_id, price, cost, supplier, lead_time_days, moq, case_pack), '|' order by product_id)), '') from inv_sku",
    "inv_position": "select count(*), coalesce(md5(string_agg(concat_ws(',', product_id, region, on_hand, oldest_age_days), '|' order by product_id, region)), '') from inv_position",
    "inv_purchase_order": "select count(*), coalesce(md5(string_agg(concat_ws(',', id, qty, eta, status), '|' order by id)), '') from inv_purchase_order",
    "inv_event": "select count(*), coalesce(md5(string_agg(concat_ws(',', id, start_date, end_date, popularity), '|' order by id)), '') from inv_event",
    "inv_demand_daily": "select count(*), coalesce(sum(units), 0) from inv_demand_daily",
    "inv_forecast": "select count(*) from inv_forecast",
    "inv_model_run": "select count(*), coalesce(max(created_at)::text, '') from inv_model_run",
}


def _psql(db: str, sql: str) -> str:
    docker = stack.DOCKER
    out = subprocess.run([docker, "exec", "dsports-postgres", "psql", "-U", "dsports", "-d", db, "-At", "-F", "|", "-c", sql], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        return f"ERROR: {out.stderr.strip()[:120]}"
    return out.stdout.strip()


def fingerprint() -> dict[str, str]:
    state = {f"dsports.{k}": _psql("dsports", q) for k, q in _DSPORTS.items()}
    state.update({f"ai.{k}": _psql("dsports_ai", q) for k, q in _AI.items()})
    return state


def read_only_tool_names() -> set[str]:
    """Every tool in the registry whose kind is READ. Anything a response reports outside this set would be a state-changing tool."""
    from app.tools.build import build_registry
    from app.tools.registry import Kind
    return set(build_registry().names(Kind.READ))
