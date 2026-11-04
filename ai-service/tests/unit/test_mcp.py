"""MCP facade over the same registry, exercised with an in-process MCP client (no network). The networked, authenticated flow
through a real MCP client against the running stack is covered by tests/live/test_stack_mcp.py."""
import json
from contextlib import asynccontextmanager

from mcp import Client

from app.mcp.server import build_mcp_server
from app.tools.registry import Kind
from tests.helpers import make_rig


@asynccontextmanager
async def connected():
    rig = await make_rig()
    async with Client(build_mcp_server(rig.services)) as client:
        yield rig, client


async def test_every_read_tool_is_exposed_with_its_registry_schema():
    async with connected() as (rig, client):
        tools = {t.name: t for t in (await client.list_tools()).tools}
        assert set(rig.services.registry.names(Kind.READ)) | {"propose_action", "confirm_action"} == set(tools)
        schema = tools["get_orders"].input_schema
        assert schema["properties"]["limit"]["maximum"] == 20 and schema["properties"]["since"]["anyOf"][0]["format"] == "date"
        assert "customerId" not in json.dumps(tools["get_orders"].input_schema) and "customer_id" not in json.dumps(schema)


async def test_write_tools_are_not_directly_exposed():
    async with connected() as (rig, client):
        names = {t.name for t in (await client.list_tools()).tools}
        assert "create_return" not in names and "reorder_product" not in names and {"propose_action", "confirm_action"} <= names


async def test_a_public_tool_works_without_credentials_and_uses_the_registry():
    async with connected() as (rig, client):
        r = await client.call_tool("search_products", {"query": "cricket bat", "sport": "Cricket", "category": "Bat", "limit": 2})
        assert not r.is_error and [p["name"] for p in r.structured_content["products"]][0] == "MRF Cricket Bat - Virgin"
        assert any(p == "/api/prices" for _, p, _ in rig.dsports.requests)


async def test_account_tools_refuse_anonymous_callers():
    async with connected() as (rig, client):
        for name, args in (("get_orders", {}), ("get_cart", {}), ("track_order", {"latest": True})):
            r = await client.call_tool(name, args)
            assert r.is_error and "AUTH_REQUIRED" in r.content[0].text, name


async def test_propose_and_confirm_need_a_signed_in_customer():
    async with connected() as (rig, client):
        r = await client.call_tool("propose_action", {"tool": "create_return", "arguments": {}})
        assert r.is_error and "AUTH_REQUIRED" in r.content[0].text
        r = await client.call_tool("confirm_action", {"confirmation_token": "v1.a.b"})
        assert r.is_error and "AUTH_REQUIRED" in r.content[0].text


async def test_invalid_arguments_are_rejected_by_the_schema():
    async with connected() as (rig, client):
        r = await client.call_tool("get_orders", {"limit": 999})
        assert r.is_error
