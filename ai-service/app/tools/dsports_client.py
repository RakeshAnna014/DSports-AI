"""DSports REST client used by the tools.

Every call carries the CUSTOMER's own bearer token and the correlation id, has a timeout, and turns failures into a
small set of stable ToolError codes. Raw upstream bodies are never propagated (they may carry internal detail).
"""
from __future__ import annotations

import logging
from typing import Any

import httpx

from app.obs.context import correlation_id_var

log = logging.getLogger(__name__)


class ToolError(Exception):
    """A tool failed. `code` is stable and safe to show; `message` is customer-safe."""

    def __init__(self, code: str, message: str, status: int | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


class DSportsClient:
    def __init__(self, http: httpx.AsyncClient):
        self._http = http

    async def request(self, method: str, path: str, *, token: str | None = None, params: dict[str, Any] | None = None,
                      json: Any = None, idempotency_key: str | None = None, allow_404: bool = False) -> Any:
        headers = {"Accept": "application/json"}
        if cid := correlation_id_var.get():
            headers["X-Correlation-Id"] = cid
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        try:
            response = await self._http.request(method, path, params=params, json=json, headers=headers)
        except httpx.TimeoutException:
            raise ToolError("UPSTREAM_TIMEOUT", "The shop did not answer in time", 504) from None
        except httpx.HTTPError:
            raise ToolError("UPSTREAM_UNAVAILABLE", "The shop service is not reachable right now", 503) from None
        return self._interpret(response, path, allow_404)

    async def get(self, path: str, *, token: str | None = None, params: dict[str, Any] | None = None, allow_404: bool = False):
        return await self.request("GET", path, token=token, params=params, allow_404=allow_404)

    async def post(self, path: str, *, token: str | None, json: Any = None, idempotency_key: str | None = None):
        return await self.request("POST", path, token=token, json=json, idempotency_key=idempotency_key)

    @staticmethod
    def _interpret(response: httpx.Response, path: str, allow_404: bool) -> Any:
        status = response.status_code
        if status < 300:
            if status == 204 or not response.content:
                return None
            try:
                return response.json()
            except ValueError:
                raise ToolError("UPSTREAM_BAD_RESPONSE", "The shop returned an unreadable response", 502) from None
        if status == 404 and allow_404:
            return None
        code, message = {
            401: ("AUTH_REQUIRED", "You need to sign in again to do that"),
            403: ("FORBIDDEN", "That item does not belong to your account"),
            404: ("NOT_FOUND", "That could not be found"),
        }.get(status, ("UPSTREAM_ERROR" if status >= 500 else "REJECTED", "The shop could not complete that request"))
        # A DSports ApiError carries a stable machine code (e.g. NOT_ELIGIBLE) and a customer-safe message.
        if status < 500:
            try:
                body = response.json()
                if isinstance(body, dict):
                    code = str(body.get("code") or code)
                    if status not in (401, 403, 404):
                        message = str(body.get("message") or message)
            except ValueError:
                pass
        log.info("dsports call rejected", extra={"path": path.split("?")[0], "status": status, "code": code})
        raise ToolError(code, message, status)
