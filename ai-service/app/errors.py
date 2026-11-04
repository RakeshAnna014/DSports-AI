"""One error shape for every response, aligned with the DSports ApiError contract."""
from __future__ import annotations

from datetime import datetime, timezone

import asyncpg
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.obs.context import correlation_id_var
import logging

log = logging.getLogger(__name__)


class ApiException(Exception):
    def __init__(self, status: int, code: str, message: str, details: dict | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.details = details                 # machine-readable extras (for example the lines that conflicted); never personal data


def error_body(status: int, code: str, message: str, path: str, details: list[dict] | None = None, extra: dict | None = None) -> dict:
    body = {
        "status": status,
        "error": {401: "Unauthorized", 403: "Forbidden", 404: "Not Found", 422: "Unprocessable Entity",
                  500: "Internal Server Error", 503: "Service Unavailable"}.get(status, "Error"),
        "code": code,
        "message": message,
        "path": path,
        "correlationId": correlation_id_var.get(),
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
    }
    if details:
        body["validationErrors"] = details
    if extra:
        body["details"] = extra
    return body


def _response(status: int, code: str, message: str, request: Request, details: list[dict] | None = None,
              headers: dict[str, str] | None = None, extra: dict | None = None) -> JSONResponse:
    return JSONResponse(error_body(status, code, message, request.url.path, details, extra), status_code=status, headers=headers)


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiException)
    async def _api(request: Request, exc: ApiException) -> JSONResponse:
        headers = {"WWW-Authenticate": "Bearer"} if exc.status == 401 else None
        return _response(exc.status, exc.code, exc.message, request, headers=headers, extra=exc.details)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Field names only: input values are never echoed back (they may contain personal data).
        details = [{"field": ".".join(str(p) for p in e["loc"] if p != "body"), "message": e["msg"]} for e in exc.errors()]
        return _response(422, "VALIDATION_ERROR", "Validation failed", request, details)

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = {404: "ROUTE_NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}.get(exc.status_code, "HTTP_ERROR")
        return _response(exc.status_code, code, "Request could not be processed", request)

    @app.exception_handler(asyncpg.PostgresError)
    async def _database(request: Request, exc: asyncpg.PostgresError) -> JSONResponse:
        # SQLSTATE class 22 is "data exception": the caller sent a value the database cannot represent (a malformed id, a number out of range, a bad date). That is a 422, never a 500,
        # and the database's own message (table, column, value) is never returned.
        if str(getattr(exc, "sqlstate", "") or "").startswith("22"):
            log.info("rejected a value the database cannot represent", extra={"sqlstate": getattr(exc, "sqlstate", None)})
            return _response(422, "INVALID_REQUEST", "A value in the request is not valid", request)
        log.error("Database error", extra={"sqlstate": getattr(exc, "sqlstate", None)}, exc_info=exc)
        return _response(500, "INTERNAL_ERROR", "An unexpected error occurred", request)

    @app.exception_handler(asyncpg.DataError)
    async def _bad_argument(request: Request, exc: asyncpg.DataError) -> JSONResponse:
        log.info("rejected an argument the database driver cannot convert")
        return _response(422, "INVALID_REQUEST", "A value in the request is not valid", request)
    @app.exception_handler(Exception)
    async def _unexpected(request: Request, exc: Exception) -> JSONResponse:
        log.error("Unhandled error", exc_info=exc)
        return _response(500, "INTERNAL_ERROR", "An unexpected error occurred", request)
