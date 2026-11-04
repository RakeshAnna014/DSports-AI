"""Async HTTP client for the DSports REST APIs (foundation only; no tools are built on it yet).

Every call forwards the customer's own Bearer token and the correlation id, so the Java backend's ownership checks are
the authorization boundary and its logs line up with ours.
"""
from __future__ import annotations

import httpx

from app.obs.context import correlation_id_var


def build_client(base_url: str, timeout_s: float) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=base_url,
        timeout=httpx.Timeout(timeout_s, connect=min(5.0, timeout_s)),
        limits=httpx.Limits(max_connections=50, max_keepalive_connections=20),
    )


def forward_headers(token: str | None = None) -> dict[str, str]:
    headers: dict[str, str] = {}
    correlation_id = correlation_id_var.get()
    if correlation_id:
        headers["X-Correlation-Id"] = correlation_id
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers
