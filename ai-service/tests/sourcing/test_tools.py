"""The sourcing tools are READ-only, role-gated by the registry for every caller (REST agent or MCP), strict about arguments, and can do nothing but read."""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.sourcing import tools as t
from app.sourcing.workflow import BUYER_ROLES
from app.tools.build import build_registry
from app.tools.registry import Kind, ToolContext


def ctx(roles, services=None):
    principal = None if roles is None else SimpleNamespace(roles=list(roles), customer_hash="h", token="tok")
    return ToolContext(principal=principal, dsports=None, settings=SimpleNamespace(sourcing_lookback_days=30, timeout_s=5), services=services or SimpleNamespace(sourcing=None))


def test_tools_are_read_only_and_buyer_gated():
    tools = t.tools()
    assert {x.name for x in tools} == {"get_sourcing_overview", "search_product_opportunities", "get_opportunity_evidence", "get_opportunity_score", "get_unmet_demand",
                                       "compare_supplier_offers", "calculate_landed_cost"}
    assert all(x.kind is Kind.READ and set(x.required_any_role) == BUYER_ROLES and x.requires_auth for x in tools)


def test_no_tool_can_start_research_approve_buy_publish_or_price():
    names = " ".join(x.name for x in build_registry().all())
    for forbidden in ("approve", "place_order", "purchase_order", "create_purchase", "publish", "set_price", "contact_supplier", "send_email", "start_research", "change_status"):
        assert forbidden not in names


@pytest.mark.asyncio
@pytest.mark.parametrize("roles", [["CUSTOMER"], ["SUPPORT_EXECUTIVE"], []])
async def test_non_buyers_are_refused_by_the_registry_before_the_handler_runs(roles):
    reg = build_registry()
    for tool in t.tools():
        r = await reg.call(tool.name, {}, ctx(roles))
        assert not r.ok and r.error_code == "FORBIDDEN", tool.name


@pytest.mark.asyncio
async def test_anonymous_is_refused_and_unknown_arguments_are_rejected():
    reg = build_registry()
    assert (await reg.call("get_sourcing_overview", {}, ctx(None))).error_code == "AUTH_REQUIRED"
    r = await reg.call("get_sourcing_overview", {"customerId": "x"}, ctx(["PROCUREMENT_BUYER"]))
    assert not r.ok and r.error_code == "INVALID_ARGUMENTS"


@pytest.mark.asyncio
async def test_without_a_database_the_tool_reports_unavailable_not_empty_success():
    r = await build_registry().call("get_sourcing_overview", {}, ctx(["ADMIN"]))
    assert not r.ok and r.error_code == "SOURCING_UNAVAILABLE"


@pytest.mark.asyncio
async def test_landed_cost_tool_is_the_deterministic_calculator_and_never_guesses_an_fx_rate():
    settings = SimpleNamespace(sourcing_base_currency="INR", sourcing_fx_rates=None, sourcing_lookback_days=30, timeout_s=5)
    svc = SimpleNamespace(settings=settings, fx=lambda c: None)
    c = ctx(["PROCUREMENT_BUYER"], SimpleNamespace(sourcing=svc))
    ok = await build_registry().call("calculate_landed_cost", {"quantity": 100, "unit_price": 100, "freight_total": 1000, "duty_pct": 10}, c)
    assert ok.ok and ok.data["status"] == "OK" and ok.data["values"]["landed_cost_per_unit"] == "121.00"
    c.calls = 0
    incomplete = await build_registry().call("calculate_landed_cost", {"quantity": 100, "unit_price": 10, "currency": "USD"}, c)
    assert incomplete.ok and incomplete.data["status"] == "INCOMPLETE" and any("fx_rate" in m for m in incomplete.data["missing"])
    c.calls = 0
    bad = await build_registry().call("calculate_landed_cost", {"quantity": 0, "unit_price": 10}, c)
    assert not bad.ok and bad.error_code == "INVALID_ARGUMENTS"
