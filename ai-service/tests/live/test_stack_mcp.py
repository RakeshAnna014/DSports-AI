"""MCP over the NETWORK against the running stack: real MCP client -> MCP server (the AI service) -> tool registry -> real DSports REST API.

Needs the AI service (AI_BASE_URL, default http://localhost:8090) and the DSports backend running, and docker access (the helper
promotes a throwaway user to admin to move an order to DELIVERED, as the API E2E does).   Run:  AI_RUN_LIVE_TESTS=1 uv run pytest -m live
"""
import os
import sys
from pathlib import Path

import httpx
import pytest
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "e2e" / "ai"))
import stack as shop  # noqa: E402  (real-stack helpers)

pytestmark = [pytest.mark.live, pytest.mark.skipif(os.environ.get("AI_RUN_LIVE_TESTS") != "1", reason="live tests are opt-in")]
URL = os.environ.get("AI_BASE_URL", "http://localhost:8090") + "/mcp/"


def session_for(token: str | None):
    class _Ctx:
        async def __aenter__(self):
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            self._http = httpx.AsyncClient(headers={**headers, "X-Correlation-Id": "pytest-mcp-1"}, timeout=60)
            self._transport = streamable_http_client(URL, http_client=self._http)
            streams = await self._transport.__aenter__()
            self._session = ClientSession(streams[0], streams[1])
            await self._session.__aenter__()
            await self._session.initialize()
            return self._session

        async def __aexit__(self, *exc):
            await self._session.__aexit__(*exc)
            await self._transport.__aexit__(*exc)
            await self._http.aclose()
    return _Ctx()


@pytest.fixture(scope="module")
def customers():
    _, alice = shop.register("mcp-a")
    _, bob = shop.register("mcp-b")
    admin_email, _ = shop.register("mcp-adm")
    admin = shop.make_admin(admin_email)
    order = shop.delivered_order(alice, admin, shop.P_SHOE_DSC, 1)
    return alice, bob, order


async def test_authenticated_tool_call_reaches_the_real_dsports_api_as_the_caller(customers):
    alice, bob, order = customers
    async with session_for(alice) as s:
        listed = await s.list_tools()
        assert {"get_orders", "track_order", "check_return_eligibility", "search_products", "propose_action", "confirm_action"} <= {t.name for t in listed.tools}
        r = await s.call_tool("get_orders", {"limit": 5})
        assert not r.is_error and r.structured_content["orders"][0]["order_number"] == order["orderNumber"]
        r = await s.call_tool("track_order", {"order_number": order["orderNumber"]})
        assert not r.is_error and r.structured_content["shipment"]["status"] == "DELIVERED"
        r = await s.call_tool("check_return_eligibility", {"order_id": order["id"], "product_id": shop.P_SHOE_DSC})
        assert r.structured_content["eligible"] is True and r.structured_content["max_returnable_quantity"] == 1


async def test_another_customers_token_cannot_read_that_order(customers):
    alice, bob, order = customers
    async with session_for(bob) as s:
        r = await s.call_tool("get_orders", {})
        assert not r.is_error and r.structured_content["orders"] == []
        for tool, args in (("get_order", {"order_id": order["id"]}), ("track_order", {"order_number": order["orderNumber"]})):
            r = await s.call_tool(tool, args)
            assert r.is_error and order["orderNumber"] not in str(r.content), tool
        r = await s.call_tool("check_return_eligibility", {"order_id": order["id"], "product_id": shop.P_SHOE_DSC})
        assert r.structured_content["eligible"] is False and r.structured_content["reason"] == "ORDER_NOT_FOUND"


async def test_a_customer_id_argument_changes_nothing(customers):
    alice, bob, order = customers
    async with session_for(bob) as s:
        _, alice_profile = shop.dsports("GET", "/api/customers/me", alice)
        r = await s.call_tool("get_orders", {"customerId": alice_profile["userId"], "userId": alice_profile["userId"]})
        assert r.structured_content["orders"] == []


async def test_anonymous_callers_get_public_tools_only(customers):
    async with session_for(None) as s:
        r = await s.call_tool("get_orders", {})
        assert r.is_error and "AUTH_REQUIRED" in r.content[0].text
        r = await s.call_tool("search_products", {"query": "cricket bat", "sport": "Cricket", "category": "Bat", "max_price": 8000})
        assert not r.is_error and r.structured_content["products"] and all(p["price"] <= 8000 for p in r.structured_content["products"])


async def test_a_state_changing_action_over_mcp_needs_propose_then_confirm(customers):
    alice, bob, order = customers
    async with session_for(alice) as s:
        assert "create_return" not in {t.name for t in (await s.list_tools()).tools}          # not directly callable
        r = await s.call_tool("propose_action", {"tool": "create_return", "arguments": {
            "order_id": order["id"], "product_id": shop.P_SHOE_DSC, "quantity": 1, "reason": "MCP integration test"}})
        assert not r.is_error and "Return 1 x DSC Cricket Shoes - Spikes" in r.structured_content["summary"]
        token = r.structured_content["confirmation_token"]
    assert shop.sql(f"select count(*) from return_requests where order_id='{order['id']}'") == "0"      # proposing created nothing
    async with session_for(bob) as s:
        r = await s.call_tool("confirm_action", {"confirmation_token": token})
        assert r.is_error and "CONFIRMATION_INVALID" in r.content[0].text                               # not Bob's token
    async with session_for(alice) as s:
        r = await s.call_tool("confirm_action", {"confirmation_token": token})
        assert not r.is_error and r.structured_content["result"]["status"] == "REQUESTED"
        again = await s.call_tool("confirm_action", {"confirmation_token": token})
        assert again.is_error and "CONFIRMATION_ALREADY_USED" in again.content[0].text
    assert shop.sql(f"select count(*) from return_requests where order_id='{order['id']}'") == "1"
