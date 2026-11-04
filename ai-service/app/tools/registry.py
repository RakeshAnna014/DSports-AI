"""The single typed tool registry. The LangGraph agent calls it in-process; the MCP server exposes the same objects.

Safety is enforced HERE, not in prompts:
  * arguments must validate against a strict model (extra fields are rejected, so a `customerId` can never be passed),
  * authentication is required for account tools (identity is the verified token, never an argument),
  * a per-request tool-call budget stops loops,
  * a WRITE tool cannot run unless the confirmation service authorised this very call.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Awaitable, Callable

from pydantic import BaseModel, ConfigDict, ValidationError

from app.auth.jwt import Principal
from app.config.settings import Settings
from app.obs import metrics
from app.tools.dsports_client import DSportsClient, ToolError

log = logging.getLogger(__name__)


class Kind(StrEnum):
    READ = "READ"
    WRITE = "WRITE"


class StrictArgs(BaseModel):
    """Base for tool arguments: unknown fields (customerId, userId, ...) are an error."""
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


@dataclass(frozen=True)
class Proposal:
    """What a WRITE tool would do, computed from LIVE data before the customer is asked to confirm."""
    summary: str
    args: dict[str, Any]          # normalised arguments that will be executed
    facts: dict[str, Any] = field(default_factory=dict)


@dataclass
class ToolContext:
    principal: Principal | None
    dsports: DSportsClient
    settings: Settings
    services: Any                      # app.services.Services (retriever, embedder, product search, ...)
    max_calls: int = 8
    calls: int = 0
    write_authorized: bool = False     # set only by the confirmation service for the one authorised call
    idempotency_key: str | None = None
    trace: list[dict[str, Any]] = field(default_factory=list)
    results: list["ToolResult"] = field(default_factory=list)

    @property
    def token(self) -> str | None:
        return self.principal.token if self.principal else None


@dataclass
class ToolResult:
    tool: str
    ok: bool
    data: Any = None
    error_code: str | None = None
    error_message: str | None = None
    latency_ms: int = 0


Handler = Callable[[ToolContext, Any], Awaitable[Any]]
Preparer = Callable[[ToolContext, Any], Awaitable[Proposal]]


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    kind: Kind
    args_model: type[StrictArgs]
    handler: Handler
    requires_auth: bool = True
    prepare: Preparer | None = None    # WRITE tools only
    required_role: str | None = None   # e.g. "ADMIN": enforced here for every caller (REST agent, MCP), never by the prompt
    required_any_role: tuple[str, ...] = ()   # the caller needs at least one of these (e.g. the buyer roles of Product & Supplier Intelligence)


class ToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, Tool] = {}

    def register(self, tool: Tool) -> None:
        if tool.name in self._tools:
            raise ValueError(f"duplicate tool {tool.name}")
        if tool.kind is Kind.WRITE and tool.prepare is None:
            raise ValueError(f"WRITE tool {tool.name} needs a prepare step (live revalidation before confirmation)")
        self._tools[tool.name] = tool

    def get(self, name: str) -> Tool | None:
        return self._tools.get(name)

    def all(self) -> list[Tool]:
        return list(self._tools.values())

    def names(self, kind: Kind | None = None) -> list[str]:
        return [t.name for t in self._tools.values() if kind is None or t.kind is kind]

    def _gate(self, tool: Tool | None, ctx: ToolContext) -> ToolResult | None:
        if tool is None:
            return ToolResult("?", False, error_code="UNKNOWN_TOOL", error_message="No such tool")
        if tool.requires_auth and ctx.principal is None:
            return ToolResult(tool.name, False, error_code="AUTH_REQUIRED", error_message="Please sign in to use this")
        if tool.required_role and tool.required_role not in (ctx.principal.roles if ctx.principal else ()):
            return ToolResult(tool.name, False, error_code="FORBIDDEN", error_message="This information is restricted to authorised business users")
        if tool.required_any_role and not (set(tool.required_any_role) & set(ctx.principal.roles if ctx.principal else ())):
            return ToolResult(tool.name, False, error_code="FORBIDDEN", error_message="This information is restricted to authorised business users")
        if ctx.calls >= ctx.max_calls:
            return ToolResult(tool.name, False, error_code="TOOL_BUDGET_EXCEEDED",
                              error_message="Too many lookups were needed for one request")
        return None

    async def call(self, name: str, raw_args: dict[str, Any] | None, ctx: ToolContext) -> ToolResult:
        """Execute a READ tool, or a WRITE tool the confirmation service has authorised. Never raises for tool failures."""
        tool = self.get(name)
        if (gate := self._gate(tool, ctx)) is not None:
            return self._finish(ctx, name, gate, 0.0)
        assert tool is not None
        try:
            args = tool.args_model.model_validate(raw_args or {})
        except ValidationError as exc:
            fields = ", ".join(sorted({".".join(str(p) for p in e["loc"]) or "arguments" for e in exc.errors()}))
            return self._finish(ctx, name, ToolResult(name, False, error_code="INVALID_ARGUMENTS",
                                                      error_message=f"Invalid arguments: {fields}"), 0.0)
        if tool.kind is Kind.WRITE and not ctx.write_authorized:
            return self._finish(ctx, name, ToolResult(name, False, error_code="CONFIRMATION_REQUIRED",
                                                      error_message="This action needs the customer's confirmation"), 0.0)
        ctx.calls += 1
        started = time.perf_counter()
        try:
            data = await asyncio.wait_for(tool.handler(ctx, args), timeout=ctx.settings.timeout_s * 2)
            result = ToolResult(name, True, data=_jsonable(data))
        except ToolError as exc:
            result = ToolResult(name, False, error_code=exc.code, error_message=exc.message)
        except asyncio.TimeoutError:
            result = ToolResult(name, False, error_code="TOOL_TIMEOUT", error_message="That took too long")
        except Exception:
            log.error("tool crashed", extra={"tool": name}, exc_info=True)
            result = ToolResult(name, False, error_code="TOOL_ERROR", error_message="That lookup failed unexpectedly")
        return self._finish(ctx, name, result, time.perf_counter() - started)

    async def prepare(self, name: str, raw_args: dict[str, Any] | None, ctx: ToolContext) -> Proposal:
        """Compute a WRITE tool's proposal from live data (also used to revalidate right before execution)."""
        tool = self.get(name)
        if tool is None or tool.kind is not Kind.WRITE or tool.prepare is None:
            raise ToolError("UNKNOWN_TOOL", "No such action")
        if tool.requires_auth and ctx.principal is None:
            raise ToolError("AUTH_REQUIRED", "Please sign in to use this")
        try:
            args = tool.args_model.model_validate(raw_args or {})
        except ValidationError:
            raise ToolError("INVALID_ARGUMENTS", "Those details are not valid") from None
        if ctx.calls >= ctx.max_calls:
            raise ToolError("TOOL_BUDGET_EXCEEDED", "Too many lookups were needed for one request")
        ctx.calls += 1
        started = time.perf_counter()
        status = "ok"
        try:
            return await tool.prepare(ctx, args)
        except ToolError:
            status = "error"
            raise
        finally:
            metrics.TOOL_LATENCY.labels(f"{name}.prepare", status).observe(time.perf_counter() - started)

    @staticmethod
    def _finish(ctx: ToolContext, name: str, result: ToolResult, seconds: float) -> ToolResult:
        result.latency_ms = int(seconds * 1000)
        status = "ok" if result.ok else (result.error_code or "error")
        metrics.TOOL_LATENCY.labels(name, status).observe(seconds)
        ctx.trace.append({"tool": name, "status": status, "latency_ms": result.latency_ms})
        ctx.results.append(result)
        log.info("tool call", extra={"tool": name, "status": status, "latency_ms": result.latency_ms})
        return result


def _jsonable(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json")
    if isinstance(value, list):
        return [_jsonable(v) for v in value]
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    return value
