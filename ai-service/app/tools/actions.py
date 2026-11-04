"""Executes a customer-confirmed action: redeem token -> revalidate against live data -> execute -> audit."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

from app.auth.jwt import Principal
from app.guard.confirmation import ConfirmationError, ConfirmationService
from app.store.state import StateStore
from app.tools.dsports_client import ToolError
from app.tools.registry import ToolContext, ToolRegistry

log = logging.getLogger(__name__)


@dataclass
class ActionOutcome:
    ok: bool
    tool: str | None = None
    code: str | None = None
    message: str | None = None
    result: dict[str, Any] | None = None
    summary: str | None = None
    facts: dict[str, Any] | None = None


class ActionExecutor:
    def __init__(self, registry: ToolRegistry, confirmations: ConfirmationService, state: StateStore):
        self._registry, self._confirmations, self._state = registry, confirmations, state

    async def confirm(self, principal: Principal, token: str, ctx: ToolContext) -> ActionOutcome:
        try:
            action = await self._confirmations.redeem(token, principal.customer_hash)
        except ConfirmationError as exc:
            return ActionOutcome(False, code=exc.code, message=str(exc))
        audit = dict(customer_hash=principal.customer_hash, tool=action.tool, args_hash=action.args_hash,
                     conversation_id=action.conversation_id)
        await self._state.audit(action="CONFIRMED", outcome="CONSUMED", **audit)

        # Revalidate against CURRENT data: eligibility, price and stock may have changed since the proposal.
        try:
            proposal = await self._registry.prepare(action.tool, action.args, ctx)
        except ToolError as exc:
            await self._state.audit(action="REVALIDATION_FAILED", outcome=exc.code, detail={"message": exc.message}, **audit)
            await self._state.finish_pending(action.nonce, f"REVALIDATION_FAILED:{exc.code}")
            return ActionOutcome(False, action.tool, exc.code, exc.message)
        # The summary carries what the customer saw (price, quantity, product): any difference means the world moved.
        if proposal.args != action.args or proposal.summary != action.summary:
            await self._state.audit(action="REVALIDATION_FAILED", outcome="STATE_CHANGED", **audit)
            await self._state.finish_pending(action.nonce, "REVALIDATION_FAILED:STATE_CHANGED")
            return ActionOutcome(False, action.tool, "STATE_CHANGED", "Things changed since you were asked; please ask me again.")

        ctx.write_authorized = True  # the only place a WRITE tool is ever authorised
        ctx.idempotency_key = f"ai-{action.nonce}"
        try:
            result = await self._registry.call(action.tool, action.args, ctx)
        finally:
            ctx.write_authorized = False
        if not result.ok:
            await self._state.audit(action="EXECUTION_FAILED", outcome=result.error_code or "ERROR", detail={"message": result.error_message}, **audit)
            await self._state.finish_pending(action.nonce, f"FAILED:{result.error_code}")
            return ActionOutcome(False, action.tool, result.error_code, result.error_message, summary=action.summary)
        await self._state.audit(action="EXECUTED", outcome="SUCCESS", detail={"idempotency_key": ctx.idempotency_key}, **audit)
        await self._state.finish_pending(action.nonce, "EXECUTED")
        return ActionOutcome(True, action.tool, result=result.data, summary=action.summary, facts=proposal.facts)
