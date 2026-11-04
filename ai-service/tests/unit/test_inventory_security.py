"""Inventory Intelligence is INTERNAL: enforced by the registry and by SQL, not by the prompt or the frontend."""
import uuid

import pytest

from app.auth.jwt import verify_token
from app.tools.registry import Kind
from tests.conftest import make_settings, make_token
from tests.helpers import make_rig

ADMIN_ID = str(uuid.uuid4())


@pytest.fixture
async def rig():
    return await make_rig()


def _admin():
    return verify_token(make_token(ADMIN_ID, roles=("ADMIN",)), make_settings())


def _inventory_tools(rig):
    return [t for t in rig.services.registry.all() if t.required_role]


async def test_every_inventory_tool_is_read_only_and_admin_only(rig):
    tools = _inventory_tools(rig)
    assert len(tools) >= 15
    assert all(t.kind is Kind.READ and t.required_role == "ADMIN" for t in tools)
    # Phase 2 is decision support: there is no inventory WRITE tool at all (no price change, no purchase order, no transfer)
    # The only WRITE tools are the customer's own, each behind a confirmation (Phase 1: returns, reorder; Phase 3: the customer's own support case).
    customer_writes = {"create_return", "reorder_product", "create_support_case", "add_case_message", "request_case_escalation", "reopen_support_case"}
    assert {t.name for t in rig.services.registry.all() if t.kind is Kind.WRITE} == customer_writes


async def test_a_customer_is_forbidden_from_every_inventory_tool(rig):
    for tool in _inventory_tools(rig):
        r = await rig.services.registry.call(tool.name, {}, rig.ctx(rig.alice))
        assert not r.ok and r.error_code in ("FORBIDDEN", "INVALID_ARGUMENTS"), tool.name
        if r.error_code == "INVALID_ARGUMENTS":          # a tool with required arguments: still forbidden with valid ones
            r = await rig.services.registry.call(tool.name, {"question": "minimum order quantity", "product_id": str(uuid.uuid4())}, rig.ctx(rig.alice))
            assert not r.ok and r.error_code in ("FORBIDDEN", "INVALID_ARGUMENTS"), tool.name


async def test_a_guest_cannot_use_inventory_tools(rig):
    r = await rig.services.registry.call("get_inventory_overview", {}, rig.ctx(None))
    assert not r.ok and r.error_code == "AUTH_REQUIRED"


async def test_an_admin_passes_the_gate_but_gets_a_structured_error_without_a_database(rig):
    r = await rig.services.registry.call("get_inventory_overview", {}, rig.ctx(_admin()))
    assert not r.ok and r.error_code == "INVENTORY_UNAVAILABLE"      # past the role gate, then no AI database in unit tests


async def test_inventory_tools_reject_unknown_fields_such_as_a_price_or_user(rig):
    for field in ("newPrice", "userId", "customerId", "purchaseOrderQty"):
        r = await rig.services.registry.call("run_promotion_scenario", {field: 1}, rig.ctx(_admin()))
        assert not r.ok and r.error_code == "INVALID_ARGUMENTS"
