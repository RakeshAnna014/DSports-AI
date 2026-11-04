"""MCP server facade over the SAME typed tool registry the LangGraph agent uses (official `mcp` SDK, streamable HTTP).

  * Every READ tool in the registry is exposed with its real argument schema (generated from the pydantic model).
  * WRITE tools are NOT directly callable: an MCP client uses `propose_action` (returns a summary and a single-use confirmation
    token) and then `confirm_action`. Same confirmation service, same revalidation, same audit as the web UI.
  * Authentication is the customer's bearer token on each HTTP request; it is verified exactly like the REST API's, forwarded to
    DSports, and no tool takes a customer id. Without a token only the public tools work.
The agent itself calls the registry in-process (no network hop); this facade exists so external MCP clients can use the tools.
"""
from __future__ import annotations

import inspect
import logging
import uuid
from typing import Annotated, Any

from mcp.server.mcpserver import Context, MCPServer
from mcp.server.mcpserver.exceptions import ToolError as McpToolError
from pydantic import Field
from pydantic.fields import FieldInfo

from app.auth.jwt import Principal, verify_token
from app.errors import ApiException
from app.obs.context import correlation_id_var, resolve_correlation_id
from app.services import Services
from app.tools.registry import Kind, Tool, ToolContext

log = logging.getLogger(__name__)


def _principal(services: Services, ctx: Context) -> Principal | None:
    headers = {k.lower(): v for k, v in (ctx.headers or {}).items()}
    auth = headers.get("authorization", "")
    cid = resolve_correlation_id(headers.get("x-correlation-id")) if headers.get("x-correlation-id") else str(uuid.uuid4())
    correlation_id_var.set(cid)
    if not auth:
        return None
    scheme, _, token = auth.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise McpToolError("AUTH_INVALID: unsupported authorization header")
    try:
        return verify_token(token.strip(), services.settings)
    except ApiException as exc:
        raise McpToolError(f"{exc.code}: {exc.message}") from None


def _tool_context(services: Services, principal: Principal | None) -> ToolContext:
    return ToolContext(principal=principal, dsports=services.dsports, settings=services.settings, services=services,
                       max_calls=services.settings.max_tool_calls)


def _typed_function(services: Services, tool: Tool):
    """A function whose signature mirrors the tool's pydantic arguments, so MCP publishes the exact same input schema."""
    params = [inspect.Parameter("ctx", inspect.Parameter.POSITIONAL_OR_KEYWORD, annotation=Context)]
    annotations: dict[str, Any] = {"ctx": Context}
    for name, field in tool.args_model.model_fields.items():
        annotation = Annotated[field.annotation, FieldInfo.merge_field_infos(field)] if field.metadata or field.description else field.annotation
        default = inspect.Parameter.empty if field.is_required() else field.default
        params.append(inspect.Parameter(name, inspect.Parameter.KEYWORD_ONLY, annotation=annotation, default=default))
        annotations[name] = annotation
    annotations["return"] = dict[str, Any]

    async def call(ctx: Context, **kwargs: Any) -> dict[str, Any]:
        principal = _principal(services, ctx)
        tool_ctx = _tool_context(services, principal)
        result = await services.registry.call(tool.name, {k: _plain(v) for k, v in kwargs.items() if v is not None}, tool_ctx)
        if not result.ok:
            raise McpToolError(f"{result.error_code}: {result.error_message}")
        return result.data if isinstance(result.data, dict) else {"result": result.data}

    call.__name__ = tool.name
    call.__doc__ = tool.description
    call.__signature__ = inspect.Signature(params, return_annotation=dict[str, Any])  # type: ignore[attr-defined]
    call.__annotations__ = annotations
    return call


def _plain(value: Any) -> Any:
    return str(value) if isinstance(value, uuid.UUID) else value


def build_mcp_server(services: Services) -> MCPServer:
    server = MCPServer("dsports-ai-tools", instructions=(
        "DSports shopping tools. Read tools return live catalogue, order and knowledge data for the signed-in customer (send their "
        "bearer token). State-changing actions need propose_action then confirm_action."))
    for tool in services.registry.all():
        if tool.kind is Kind.WRITE:
            continue
        server.add_tool(_typed_function(services, tool), name=tool.name, description=tool.description)

    write_tools = [t.name for t in services.registry.all() if t.kind is Kind.WRITE]

    async def propose_action(ctx: Context, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        """Prepare a state-changing action (create_return, reorder_product) and get a single-use confirmation token. Nothing is executed."""
        principal = _principal(services, ctx)
        if principal is None:
            raise McpToolError("AUTH_REQUIRED: sign in to propose actions")
        if tool not in write_tools:
            raise McpToolError(f"UNKNOWN_TOOL: choose one of {write_tools}")
        tool_ctx = _tool_context(services, principal)
        try:
            proposal = await services.registry.prepare(tool, arguments, tool_ctx)
        except Exception as exc:  # ToolError carries a customer-safe code/message
            raise McpToolError(f"{getattr(exc, 'code', 'ERROR')}: {getattr(exc, 'message', 'could not prepare that action')}") from None
        issued = await services.confirmations.propose(customer_hash=principal.customer_hash, tool=tool, args=proposal.args,
                                                      summary=proposal.summary, conversation_id=None)
        return {"summary": proposal.summary, "confirmation_token": issued.token, "expires_at": issued.expires_at.isoformat(),
                "next": "call confirm_action with this token to execute"}

    async def confirm_action(ctx: Context, confirmation_token: str) -> dict[str, Any]:
        """Execute a previously proposed action. The token is single-use, expires, and only works for the customer it was issued to."""
        principal = _principal(services, ctx)
        if principal is None:
            raise McpToolError("AUTH_REQUIRED: sign in to confirm actions")
        outcome = await services.executor.confirm(principal, confirmation_token, _tool_context(services, principal))
        if not outcome.ok:
            raise McpToolError(f"{outcome.code}: {outcome.message}")
        return {"tool": outcome.tool, "summary": outcome.summary, "result": outcome.result}

    server.add_tool(propose_action, name="propose_action", description=propose_action.__doc__)
    server.add_tool(confirm_action, name="confirm_action", description=confirm_action.__doc__)
    return server
