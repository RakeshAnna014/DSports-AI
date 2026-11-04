"""Confirmation tokens for state-changing assistant actions.

  token = v1.<base64url(payload)>.<base64url(HMAC-SHA256(key, "v1." + base64url(payload)))>
  payload = {n: nonce, c: customer hash, t: tool name, a: sha256(canonical args), e: expiry (epoch seconds)}

A token is:
  * HMAC-bound   - altering any byte invalidates it;
  * customer-bound - only the customer it was issued to can redeem it;
  * tool-bound and argument-bound - the arguments are stored server-side at proposal time and the client never resends
    them, so there is nothing to tamper with; the hash in the token must match the stored arguments;
  * single-use   - redeeming flips the stored record PENDING -> CONSUMED atomically; a replay finds it CONSUMED;
  * short-lived  - default 5 minutes (AI_CONFIRMATION_TTL_S).
Every failure collapses into a few generic codes so a probing client learns nothing about why a token was refused.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from app.store.state import PendingAction, StateStore

VERSION = "v1"


class ConfirmationError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class IssuedToken:
    token: str
    nonce: str
    expires_at: datetime


def args_hash(tool: str, args: dict[str, Any]) -> str:
    canonical = json.dumps({"tool": tool, "args": args}, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class ConfirmationService:
    def __init__(self, secret: str, state: StateStore, ttl_s: int = 300, clock=time.time):
        # A dedicated sub-key: the customer-hash key itself never signs tokens.
        self._key = hmac.new(secret.encode("utf-8"), b"dsports-ai/confirmation/v1", hashlib.sha256).digest()
        self._state = state
        self._ttl = ttl_s
        self._clock = clock

    def _sign(self, body: str) -> str:
        return _b64(hmac.new(self._key, f"{VERSION}.{body}".encode("ascii"), hashlib.sha256).digest())

    async def propose(self, *, customer_hash: str, tool: str, args: dict[str, Any], summary: str,
                      conversation_id: str | None) -> IssuedToken:
        nonce = secrets.token_urlsafe(18)
        expires = int(self._clock()) + self._ttl
        h = args_hash(tool, args)
        payload = _b64(json.dumps({"n": nonce, "c": customer_hash, "t": tool, "a": h, "e": expires},
                                  separators=(",", ":")).encode("utf-8"))
        await self._state.create_pending(PendingAction(
            nonce=nonce, customer_hash=customer_hash, conversation_id=conversation_id, tool=tool, args=args, args_hash=h,
            summary=summary, expires_at=datetime.fromtimestamp(expires, tz=timezone.utc)))
        await self._state.audit(customer_hash=customer_hash, action="PROPOSED", outcome="PENDING_CONFIRMATION", tool=tool,
                                args_hash=h, conversation_id=conversation_id, detail={"summary": summary})
        return IssuedToken(f"{VERSION}.{payload}.{self._sign(payload)}", nonce, datetime.fromtimestamp(expires, tz=timezone.utc))

    async def redeem(self, token: str, customer_hash: str) -> PendingAction:
        """Verify and consume the token. Raises ConfirmationError; on success the pending action is marked CONSUMED."""
        invalid = ConfirmationError("CONFIRMATION_INVALID", "This confirmation is not valid")
        try:
            version, payload, signature = token.split(".")
        except ValueError:
            raise invalid from None
        if version != VERSION or not hmac.compare_digest(self._sign(payload), signature):
            raise invalid
        try:
            claims = json.loads(_unb64(payload))
        except (ValueError, UnicodeDecodeError):
            raise invalid from None
        if claims.get("c") != customer_hash:  # issued to someone else (or tampered): indistinguishable from invalid
            raise invalid
        if int(claims.get("e", 0)) < self._clock():
            await self._state.audit(customer_hash=customer_hash, action="CONFIRM_REJECTED", outcome="EXPIRED", tool=claims.get("t"))
            raise ConfirmationError("CONFIRMATION_EXPIRED", "This confirmation has expired; please ask again")
        action = await self._state.consume_pending(claims["n"], customer_hash)
        if action is None:
            await self._state.audit(customer_hash=customer_hash, action="CONFIRM_REJECTED", outcome="REPLAY_OR_UNKNOWN", tool=claims.get("t"))
            raise ConfirmationError("CONFIRMATION_ALREADY_USED", "This confirmation was already used or is no longer available")
        if action.tool != claims.get("t") or action.args_hash != claims.get("a") or args_hash(action.tool, action.args) != action.args_hash:
            await self._state.audit(customer_hash=customer_hash, action="CONFIRM_REJECTED", outcome="MISMATCH", tool=action.tool)
            raise invalid
        return action
