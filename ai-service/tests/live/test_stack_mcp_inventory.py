"""Inventory tools over MCP, against the RUNNING stack: a real MCP client -> the MCP server -> the tool registry -> the real mart.
The tools are listed (the listing is global) but every CALL is role-gated per request. Run:  AI_RUN_LIVE_TESTS=1 uv run pytest -m live"""
import os
import time

import jwt
import pytest

from tests.live.test_stack_mcp import session_for, shop

pytestmark = [pytest.mark.live, pytest.mark.skipif(os.environ.get("AI_RUN_LIVE_TESTS") != "1", reason="live tests are opt-in")]

INVENTORY_TOOLS = ["get_inventory_overview", "get_inventory", "get_inventory_snapshot", "get_inventory_history", "get_sales_history", "get_demand_forecast", "get_stock_risk",
                   "get_replenishment_recommendation", "get_overstock_products", "get_slow_moving_products", "get_inventory_by_region", "get_events", "get_event_impact",
                   "run_promotion_scenario", "get_clearance_candidates", "get_forecast_quality", "get_inventory_policy"]
SAFE_ARGS = {"get_inventory_history": {"product_id": "00000000-0000-0000-0000-000000001001"}, "get_inventory_policy": {"question": "replenishment policy"}}


@pytest.fixture(scope="module")
def people():
    _, customer = shop.register("mcp-inv-c")
    admin_email, _ = shop.register("mcp-inv-a")
    admin = shop.make_admin(admin_email)
    return customer, admin


def forged(token: str, **changes) -> str:
    claims = jwt.decode(token, options={"verify_signature": False})
    claims.update(changes)
    return jwt.encode(claims, "an-attackers-own-secret-an-attackers-own-secret", algorithm="HS256")


async def test_the_inventory_tools_are_exposed_but_there_is_no_inventory_write_tool(people):
    _, admin = people
    async with session_for(admin) as s:
        names = {t.name for t in (await s.list_tools()).tools}
        assert set(INVENTORY_TOOLS) <= names
        assert not {n for n in names if any(w in n for w in ("create_purchase", "change_price", "set_price", "transfer", "launch", "approve", "update_price"))}
        r = await s.call_tool("propose_action", {"tool": "change_price", "arguments": {"newPrice": 1}})
        assert r.is_error and "UNKNOWN_TOOL" in r.content[0].text and "create_return" in r.content[0].text   # the only writable actions are the Phase 1 customer ones
        for read_tool in ("get_stock_risk", "run_promotion_scenario"):                                          # a READ tool cannot be proposed as an action either
            r = await s.call_tool("propose_action", {"tool": read_tool, "arguments": {}})
            assert r.is_error and "UNKNOWN_TOOL" in r.content[0].text
        r = await s.call_tool("confirm_action", {"confirmation_token": "forged-token"})
        assert r.is_error


@pytest.mark.parametrize("tool", INVENTORY_TOOLS)
async def test_a_customer_is_forbidden_every_inventory_tool_over_mcp(people, tool):
    customer, _ = people
    async with session_for(customer) as s:
        r = await s.call_tool(tool, SAFE_ARGS.get(tool, {}))
        assert r.is_error and "FORBIDDEN" in r.content[0].text, (tool, r.content[0].text[:120])
        assert "supplier" not in r.content[0].text.lower() and "margin" not in r.content[0].text.lower()


@pytest.mark.parametrize("tool", INVENTORY_TOOLS)
async def test_anonymous_callers_are_refused_every_inventory_tool_over_mcp(tool):
    async with session_for(None) as s:
        r = await s.call_tool(tool, SAFE_ARGS.get(tool, {}))
        assert r.is_error and "AUTH_REQUIRED" in r.content[0].text


async def test_expired_tampered_and_self_issued_admin_tokens_are_rejected_over_mcp(people):
    customer, admin = people
    now = int(time.time())
    for label, token in (("expired", forged(admin, exp=now - 60)), ("self-issued admin", forged(customer, roles=["ADMIN"])), ("tampered", admin[:-4] + "AAAA")):
        async with session_for(token) as s:
            r = await s.call_tool("get_stock_risk", {})
            assert r.is_error and any(code in r.content[0].text for code in ("INVALID_TOKEN", "TOKEN_EXPIRED", "AUTH_INVALID")), (label, r.content[0].text[:120])


async def test_an_admin_gets_real_read_only_results_and_provenance(people):
    _, admin = people
    async with session_for(admin) as s:
        r = await s.call_tool("get_stock_risk", {"limit": 3})
        assert not r.is_error and r.structured_content["risks"] and r.structured_content["data_source"] == "POC_SYNTHETIC"
        r = await s.call_tool("run_promotion_scenario", {"discounts": [30, 50], "slow_moving_only": True})
        assert not r.is_error and r.structured_content["provenance"]["model_name"] and "decision support" in r.structured_content["recommendation_status"].lower()
        r = await s.call_tool("run_promotion_scenario", {"discounts": [95]})                       # impossible input is a clean error, not a number
        assert r.is_error
        # Over MCP the typed signature drops unknown arguments before they reach the registry (the strict REST/agent check does not apply here), so a smuggled
        # price is IGNORED, never applied. Prove it against the real DSports price, not against the response.
        import json, urllib.request
        pid = "00000000-0000-0000-0000-000000001001"
        price = lambda: json.load(urllib.request.urlopen(f"http://localhost:8080/api/prices?productId={pid}"))   # noqa: E731
        before = price()
        r = await s.call_tool("get_stock_risk", {"limit": 3, "newPrice": 1, "productId": pid, "price": 1})
        assert not r.is_error and len(r.structured_content["risks"]) <= 3
        assert price() == before


async def test_internal_policies_never_reach_a_customer_through_the_customer_knowledge_tool(people):
    customer, admin = people
    question = "what is the clearance policy for old inventory, the minimum order quantity of suppliers and who approves a discount above 50 percent"
    async with session_for(customer) as s:
        r = await s.call_tool("get_product_knowledge", {"question": question, "document_types": ["policy", "faq", "guide"], "limit": 8})
        sources = {c["source"] for c in (r.structured_content or {}).get("chunks", [])}
        assert not any(src.startswith("inv-") for src in sources), sources
        text = " ".join(c["text"] for c in (r.structured_content or {}).get("chunks", [])).lower()
        assert "head of merchandising" not in text and "meerut" not in text
    async with session_for(admin) as s:                                                              # the same question from an admin reaches the internal documents
        r = await s.call_tool("get_inventory_policy", {"question": question, "limit": 6})
        assert not r.is_error and any(c["source"].startswith("inv-") for c in r.structured_content["chunks"])
