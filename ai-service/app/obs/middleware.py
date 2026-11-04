from __future__ import annotations

import logging
import time
import uuid

from fastapi import FastAPI, Request

from app.obs.context import conversation_id_var, correlation_id_var, request_id_var, resolve_correlation_id

log = logging.getLogger("ai.request")
CORRELATION_HEADER = "X-Correlation-Id"
REQUEST_HEADER = "X-Request-Id"


def install_request_context(app: FastAPI) -> None:
    @app.middleware("http")
    async def request_context(request: Request, call_next):
        correlation_id = resolve_correlation_id(request.headers.get(CORRELATION_HEADER))
        request_id = str(uuid.uuid4())
        tokens = (correlation_id_var.set(correlation_id), request_id_var.set(request_id), conversation_id_var.set(None))
        request.state.correlation_id = correlation_id
        started = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            response.headers[CORRELATION_HEADER] = correlation_id
            response.headers[REQUEST_HEADER] = request_id
            return response
        finally:
            # Path only: query strings and headers can carry personal data or credentials and are never logged.
            log.info("request completed", extra={
                "method": request.method, "path": request.url.path, "status": status,
                "duration_ms": int((time.perf_counter() - started) * 1000),
                "customer_hash": getattr(request.state, "customer_hash", None),
            })
            for var, token in zip((correlation_id_var, request_id_var, conversation_id_var), tokens):
                var.reset(token)
