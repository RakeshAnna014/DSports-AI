"""Builds the configured typed research providers from SOURCING_PROVIDERS (a JSON list). A provider is only created when its hosts are on the operator's allow-list; an entry that cannot be used
is reported as a configuration problem (shown on the dashboard and in every run), never silently dropped."""
from __future__ import annotations

import json
from typing import Any

from app.sourcing.fetch import SafeFetcher
from app.sourcing.victor import BASE as VICTOR_BASE
from app.sourcing.victor import VictorSportProvider
from urllib.parse import urlsplit

KNOWN = {"victor_sport": (VICTOR_BASE, "Victor Sport (official manufacturer website)")}


def build_typed_providers(raw: str | None, fetcher: SafeFetcher, allowed_hosts: list[str]) -> tuple[list[Any], list[str]]:
    if not raw or not raw.strip():
        return [], []
    try:
        items = json.loads(raw)
        assert isinstance(items, list)
    except (ValueError, AssertionError):
        return [], ["SOURCING_PROVIDERS is not a JSON list"]
    providers: list[Any] = []
    problems: list[str] = []
    for i, it in enumerate(items):
        kind = it.get("type") if isinstance(it, dict) else None
        if it.get("enabled") is False if isinstance(it, dict) else False:
            continue
        if kind not in KNOWN:
            problems.append(f"provider #{i + 1}: unknown type {kind!r} (supported: {', '.join(sorted(KNOWN))})")
            continue
        base, label = KNOWN[kind]
        host = (urlsplit(base).hostname or "").lower()
        if host not in allowed_hosts:
            problems.append(f"{label} needs {host} in SOURCING_ALLOWED_HOSTS")
            continue
        if kind == "victor_sport":
            countries = tuple(it.get("supplier_countries") or ("India",))
            providers.append(VictorSportProvider(fetcher, base=base, max_products=int(it.get("max_products") or 12), supplier_countries=countries))
    return providers, problems
