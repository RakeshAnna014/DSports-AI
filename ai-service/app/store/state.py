"""Conversation state, single-use pending actions and the audit trail.

Two backends with one interface: PostgreSQL (the AI database) and an in-process one used when the database is not
available (degraded mode) and in unit tests. Customers are always referenced by their HMAC hash. Guests have no
identity: their conversation is owned by the sentinel GUEST and is reachable only through its random id.
"""
from __future__ import annotations

import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Protocol

import asyncpg

from app.obs.context import correlation_id_var

log = logging.getLogger(__name__)
GUEST = "GUEST"


@dataclass
class ConversationState:
    """Deliberately small: everything the next turn needs, nothing it does not."""
    conversation_id: str
    owner: str
    intent: str | None = None
    slots: dict[str, Any] = field(default_factory=dict)
    candidate_product_ids: list[str] = field(default_factory=list)
    last_products: list[dict[str, Any]] = field(default_factory=list)  # compact facts of the products last shown
    last_order: dict[str, Any] | None = None  # {"id","orderNumber"} last discussed
    selected_product: dict[str, Any] | None = None   # {"id","name",...} the product the customer is asking about (validated against DSports)
    comparison_ids: list[str] = field(default_factory=list)   # the products currently being compared
    pending_nonce: str | None = None
    pending_token: str | None = None   # the signed token issued for the pending action (useless without the owner's login)
    rolling_summary: str = ""
    turns: int = 0
    ttl_hours: float | None = None   # a longer lifetime than the default for conversations the customer may resume (case-linked support chats)

    def to_json(self) -> dict[str, Any]:
        d = dict(self.__dict__)
        d.pop("conversation_id"); d.pop("owner")
        return d

    @classmethod
    def from_json(cls, conversation_id: str, owner: str, data: dict[str, Any]) -> "ConversationState":
        known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__ and k not in ("conversation_id", "owner")}
        return cls(conversation_id=conversation_id, owner=owner, **known)


@dataclass
class PendingAction:
    nonce: str
    customer_hash: str
    conversation_id: str | None
    tool: str
    args: dict[str, Any]
    args_hash: str
    summary: str
    expires_at: datetime
    status: str = "PENDING"


class StateStore(Protocol):
    async def load(self, conversation_id: str, owner: str) -> ConversationState | None: ...
    async def save(self, state: ConversationState) -> None: ...
    async def find_by_case(self, owner: str, case_ref: str) -> ConversationState | None: ...
    async def create_pending(self, action: PendingAction) -> None: ...
    async def peek_pending(self, nonce: str) -> PendingAction | None: ...
    async def consume_pending(self, nonce: str, customer_hash: str) -> PendingAction | None: ...
    async def finish_pending(self, nonce: str, outcome: str) -> None: ...
    async def audit(self, *, customer_hash: str, action: str, outcome: str, tool: str | None = None,
                    args_hash: str | None = None, conversation_id: str | None = None,
                    detail: dict[str, Any] | None = None) -> None: ...


def _now() -> datetime:
    return datetime.now(timezone.utc)


class MemoryStateStore:
    """Thread-unsafe by design (single event loop). Expiry and ownership rules mirror the SQL implementation."""

    def __init__(self, ttl_hours: float = 24.0):
        self._ttl = timedelta(hours=ttl_hours)
        self._conversations: dict[str, tuple[ConversationState, datetime]] = {}
        self._pending: dict[str, PendingAction] = {}
        self.audit_log: list[dict[str, Any]] = []

    async def load(self, conversation_id: str, owner: str) -> ConversationState | None:
        entry = self._conversations.get(conversation_id)
        if entry is None or entry[1] < _now() or entry[0].owner != owner:
            return None
        return ConversationState.from_json(conversation_id, owner, json.loads(json.dumps(entry[0].to_json())))

    async def save(self, state: ConversationState) -> None:
        ttl = timedelta(hours=state.ttl_hours) if state.ttl_hours else self._ttl
        self._conversations[state.conversation_id] = (
            ConversationState.from_json(state.conversation_id, state.owner, json.loads(json.dumps(state.to_json(), default=str))),
            _now() + ttl)

    async def find_by_case(self, owner: str, case_ref: str) -> ConversationState | None:
        live = [(s, exp) for s, exp in self._conversations.values() if exp >= _now() and s.owner == owner and s.slots.get("case_ref") == case_ref]
        if not live:
            return None
        best = max(live, key=lambda e: e[1])[0]
        return ConversationState.from_json(best.conversation_id, owner, json.loads(json.dumps(best.to_json())))

    async def create_pending(self, action: PendingAction) -> None:
        self._pending[action.nonce] = action

    async def peek_pending(self, nonce: str) -> PendingAction | None:
        return self._pending.get(nonce)

    async def consume_pending(self, nonce: str, customer_hash: str) -> PendingAction | None:
        action = self._pending.get(nonce)
        if action is None or action.status != "PENDING" or action.customer_hash != customer_hash or action.expires_at < _now():
            return None
        action.status = "CONSUMED"
        return action

    async def finish_pending(self, nonce: str, outcome: str) -> None:
        pass

    async def audit(self, *, customer_hash, action, outcome, tool=None, args_hash=None, conversation_id=None, detail=None):
        self.audit_log.append({"customer_hash": customer_hash, "action": action, "outcome": outcome, "tool": tool,
                               "args_hash": args_hash, "conversation_id": conversation_id, "detail": detail,
                               "correlation_id": correlation_id_var.get()})


class PgStateStore:
    def __init__(self, pool: asyncpg.Pool, ttl_hours: float = 24.0):
        self._pool = pool
        self._ttl = timedelta(hours=ttl_hours)

    async def load(self, conversation_id: str, owner: str) -> ConversationState | None:
        try:
            cid = uuid.UUID(conversation_id)
        except ValueError:
            return None
        row = await self._pool.fetchrow(
            "SELECT state, summary FROM conversations WHERE id=$1 AND customer_hash=$2 AND expires_at > now()", cid, owner)
        if row is None:
            return None
        data = json.loads(row["state"]) if isinstance(row["state"], str) else dict(row["state"])
        data["rolling_summary"] = row["summary"] or data.get("rolling_summary", "")
        return ConversationState.from_json(conversation_id, owner, data)

    async def save(self, state: ConversationState) -> None:
        expires = _now() + (timedelta(hours=state.ttl_hours) if state.ttl_hours else self._ttl)
        await self._pool.execute(
            """INSERT INTO conversations (id, customer_hash, summary, state, updated_at, expires_at)
               VALUES ($1, $2, $3, $4::jsonb, now(), $5)
               ON CONFLICT (id) DO UPDATE SET summary=EXCLUDED.summary, state=EXCLUDED.state, updated_at=now(),
                                              expires_at=EXCLUDED.expires_at
               WHERE conversations.customer_hash = EXCLUDED.customer_hash""",
            uuid.UUID(state.conversation_id), state.owner, state.rolling_summary,
            json.dumps(state.to_json(), default=str), expires)

    async def find_by_case(self, owner: str, case_ref: str) -> ConversationState | None:
        row = await self._pool.fetchrow(
            """SELECT id, state, summary FROM conversations WHERE customer_hash=$1 AND expires_at > now() AND state->'slots'->>'case_ref' = $2
               ORDER BY updated_at DESC LIMIT 1""", owner, case_ref)
        if row is None:
            return None
        data = json.loads(row['state']) if isinstance(row['state'], str) else dict(row['state'])
        data['rolling_summary'] = row['summary'] or data.get('rolling_summary', '')
        return ConversationState.from_json(str(row['id']), owner, data)

    async def create_pending(self, a: PendingAction) -> None:
        await self._pool.execute(
            """INSERT INTO pending_actions (nonce, customer_hash, conversation_id, tool, args, args_hash, summary, expires_at)
               VALUES ($1,$2,$3,$4,$5::jsonb,$6,$7,$8)""",
            a.nonce, a.customer_hash, uuid.UUID(a.conversation_id) if a.conversation_id else None, a.tool,
            json.dumps(a.args), a.args_hash, a.summary, a.expires_at)

    async def peek_pending(self, nonce: str) -> PendingAction | None:
        row = await self._pool.fetchrow("SELECT * FROM pending_actions WHERE nonce=$1", nonce)
        return self._to_action(row)

    async def consume_pending(self, nonce: str, customer_hash: str) -> PendingAction | None:
        """Atomic single-use: only one caller can flip PENDING -> CONSUMED, and only the owning customer, before expiry."""
        row = await self._pool.fetchrow(
            """UPDATE pending_actions SET status='CONSUMED', consumed_at=now()
               WHERE nonce=$1 AND customer_hash=$2 AND status='PENDING' AND expires_at > now() RETURNING *""",
            nonce, customer_hash)
        return self._to_action(row)

    async def finish_pending(self, nonce: str, outcome: str) -> None:
        await self._pool.execute("UPDATE pending_actions SET outcome=$2 WHERE nonce=$1", nonce, outcome)

    @staticmethod
    def _to_action(row) -> PendingAction | None:
        if row is None:
            return None
        args = json.loads(row["args"]) if isinstance(row["args"], str) else dict(row["args"])
        return PendingAction(nonce=row["nonce"], customer_hash=row["customer_hash"],
                             conversation_id=str(row["conversation_id"]) if row["conversation_id"] else None,
                             tool=row["tool"], args=args, args_hash=row["args_hash"], summary=row["summary"],
                             expires_at=row["expires_at"], status=row["status"])

    async def audit(self, *, customer_hash, action, outcome, tool=None, args_hash=None, conversation_id=None, detail=None):
        await self._pool.execute(
            """INSERT INTO audit (customer_hash, conversation_id, correlation_id, action, tool, args_hash, outcome, detail)
               VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb)""",
            customer_hash, uuid.UUID(conversation_id) if conversation_id else None, correlation_id_var.get(), action, tool,
            args_hash, outcome, json.dumps(detail) if detail is not None else None)


class ResilientStateStore:
    """Prefers the database; if it fails, serves from memory so a database blip degrades history, not the assistant.

    Writes that must be durable (pending actions, audit) are NOT silently downgraded: they raise, and the caller refuses
    the state-changing action rather than executing it without an audit trail."""

    def __init__(self, primary: PgStateStore, fallback: MemoryStateStore):
        self._p, self._m = primary, fallback

    async def load(self, conversation_id, owner):
        try:
            return await self._p.load(conversation_id, owner)
        except Exception:
            log.warning("conversation store unavailable; using in-memory state", exc_info=True)
            return await self._m.load(conversation_id, owner)

    async def save(self, state):
        try:
            await self._p.save(state)
        except Exception:
            log.warning("conversation store unavailable; keeping state in memory", exc_info=True)
            await self._m.save(state)

    async def find_by_case(self, owner, case_ref):
        try:
            return await self._p.find_by_case(owner, case_ref)
        except Exception:
            log.warning("conversation store unavailable; searching in-memory state", exc_info=True)
            return await self._m.find_by_case(owner, case_ref)

    async def create_pending(self, action): await self._p.create_pending(action)
    async def peek_pending(self, nonce): return await self._p.peek_pending(nonce)
    async def consume_pending(self, nonce, customer_hash): return await self._p.consume_pending(nonce, customer_hash)
    async def finish_pending(self, nonce, outcome): await self._p.finish_pending(nonce, outcome)
    async def audit(self, **kw): await self._p.audit(**kw)
