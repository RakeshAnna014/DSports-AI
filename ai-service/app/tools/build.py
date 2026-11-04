"""Builds the single typed tool registry (the source of truth for what the assistant can do)."""
from __future__ import annotations

from app.engineering import tools as engineering_tools
from app.inventory import tools as inventory_tools
from app.planning import tools as planning_tools
from app.sourcing import tools as sourcing_tools
from app.tools import account_tools, reco_tools, shop_tools, support_tools, write_tools
from app.tools.registry import ToolRegistry


def build_registry() -> ToolRegistry:
    registry = ToolRegistry()
    for tool in [*shop_tools.tools(), *account_tools.tools(), *write_tools.tools(), *support_tools.tools(), *reco_tools.tools(),
                 *inventory_tools.tools(), *engineering_tools.tools(), *sourcing_tools.tools(), *planning_tools.tools()]:
        registry.register(tool)
    return registry
