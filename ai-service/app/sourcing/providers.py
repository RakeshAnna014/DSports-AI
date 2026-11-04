"""Research source adapters. A provider turns one permitted source into normalised product candidates with provenance.

Rules every provider follows:
  * only sources the operator configured (an allow-list of hosts); no scraping of unlisted sites, no bypassing access controls;
  * external content is untrusted data: strings are sanitised (instruction-like text removed), bounded and never executed;
  * if a source cannot be used (not configured, blocked, timed out, bad payload) the provider reports `unavailable` or `error` with the reason. It never returns invented rows.

Supported source formats, selected per feed in SOURCING_FEEDS (a JSON list): {"name": ..., "url": ..., "format": "json" | "jsonld", "sport": optional}
  json    the document is a list (or {"products": [...]}) of objects with name, brand, model, gtin, sku, sport, category, description, specs, price, currency, url
  jsonld  an HTML page or JSON-LD document containing schema.org Product objects
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Protocol

import httpx

from app.guard.untrusted import sanitize_untrusted
from app.sourcing.fetch import PinnedTransport
from app.sourcing.normalize import UnsafeUrl, check_url

log = logging.getLogger(__name__)
MAX_BYTES_DEFAULT = 1_000_000
MAX_CANDIDATES_PER_SOURCE = 200
_JSONLD = re.compile(r'<script[^>]+type=["\']application/ld\+json["\'][^>]*>(.*?)</script>', re.I | re.S)


@dataclass
class Candidate:
    name: str
    brand: str | None = None
    model: str | None = None
    gtin: str | None = None
    sku: str | None = None
    manufacturer: str | None = None
    sport: str | None = None
    category: str | None = None
    description: str | None = None
    specs: dict[str, Any] = field(default_factory=dict)
    public_price: float | None = None
    currency: str | None = None
    url: str = ""
    provider: str = ""
    retrieved_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    content_hash: str = ""
    excerpt: str = ""
    source_title: str | None = None          # exactly what the source page called the product
    model_name: str | None = None
    mfr_sku: str | None = None
    variant: dict[str, Any] = field(default_factory=dict)
    source_type: str = "PUBLIC_LISTING"      # OFFICIAL_BRAND_PAGE | AUTHORISED_DISTRIBUTOR | PUBLIC_LISTING | FEED
    images: list[Any] = field(default_factory=list)          # app.sourcing.images.ImageRef
    identity_confirmed: bool = False         # the page really is this product's own page (title/H1 and SKU found)


@dataclass
class SupplierCandidate:
    """A supplier seen in a public source (for example the manufacturer's own distributors page). Seeing it there is discovery, not verification."""
    name: str
    type: str
    country: str | None
    address: str | None
    phone: str | None
    email: str | None
    website: str | None
    brands: list[str]
    source_url: str
    source_note: str
    retrieved_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


@dataclass
class ProviderResult:
    name: str
    status: str                              # ok | unavailable | error
    candidates: list[Candidate] = field(default_factory=list)
    reason: str | None = None
    suppliers: list[SupplierCandidate] = field(default_factory=list)
    stats: dict[str, Any] = field(default_factory=dict)      # pages_requested, pages_ok, invalid, skipped, ...
    skipped: list[dict[str, str]] = field(default_factory=list)   # url + why, so a buyer can see what was not used


class Provider(Protocol):
    name: str

    async def fetch(self, params: dict[str, Any]) -> ProviderResult: ...


def _clean(value: Any, limit: int) -> str | None:
    if value is None:
        return None
    text, _ = sanitize_untrusted(str(value), limit)
    text = " ".join(text.split())
    return text or None


def _price(value: Any) -> float | None:
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    return f if f > 0 else None


def candidate_from_dict(d: dict[str, Any], provider: str, url: str, raw_hash: str) -> Candidate | None:
    name = _clean(d.get("name"), 200)
    if not name:
        return None
    brand = d.get("brand")
    if isinstance(brand, dict):
        brand = brand.get("name")
    offers = d.get("offers") if isinstance(d.get("offers"), dict) else {}
    specs = d.get("specs") if isinstance(d.get("specs"), dict) else {}
    specs = {str(k)[:60]: _clean(v, 120) for k, v in list(specs.items())[:25] if _clean(v, 120)}
    return Candidate(name=name, brand=_clean(brand, 80), model=_clean(d.get("model") or d.get("mpn"), 80), gtin=_clean(d.get("gtin") or d.get("gtin13") or d.get("gtin14"), 20),
                     sku=_clean(d.get("sku"), 60), manufacturer=_clean(d.get("manufacturer") if not isinstance(d.get("manufacturer"), dict) else d["manufacturer"].get("name"), 100),
                     sport=_clean(d.get("sport"), 40), category=_clean(d.get("category"), 80), description=_clean(d.get("description"), 600), specs=specs,
                     public_price=_price(d.get("price", offers.get("price"))), currency=_clean(d.get("currency", offers.get("priceCurrency")), 3),
                     url=str(d.get("url") or url)[:500], provider=provider, content_hash=raw_hash, excerpt=_clean(d.get("description") or d.get("name"), 300) or "")


def parse_json_feed(text: str, provider: str, url: str) -> list[Candidate]:
    data = json.loads(text)
    items = data.get("products") if isinstance(data, dict) else data
    if not isinstance(items, list):
        raise ValueError("the document is not a list of products")
    out = []
    for item in items[:MAX_CANDIDATES_PER_SOURCE]:
        if isinstance(item, dict) and (c := candidate_from_dict(item, provider, url, hashlib.sha256(json.dumps(item, sort_keys=True, default=str).encode()).hexdigest()[:32])):
            out.append(c)
    return out


def parse_jsonld(text: str, provider: str, url: str) -> list[Candidate]:
    blocks = _JSONLD.findall(text) or [text]
    out: list[Candidate] = []
    for block in blocks:
        try:
            data = json.loads(block.strip())
        except ValueError:
            continue
        stack = data if isinstance(data, list) else [data]
        while stack and len(out) < MAX_CANDIDATES_PER_SOURCE:
            node = stack.pop(0)
            if not isinstance(node, dict):
                continue
            if "@graph" in node and isinstance(node["@graph"], list):
                stack += node["@graph"]
            types = node.get("@type")
            if "Product" in (types if isinstance(types, list) else [types]):
                if c := candidate_from_dict(node, provider, url, hashlib.sha256(json.dumps(node, sort_keys=True, default=str).encode()).hexdigest()[:32]):
                    out.append(c)
    return out


Fetcher = Callable[[str], Awaitable[tuple[int, str]]]


class FeedProvider:
    """A configured JSON / JSON-LD source. Redirects are not followed (a redirect target would need its own host check)."""

    def __init__(self, name: str, url: str, fmt: str, allowed_hosts: list[str], timeout_s: float = 10.0, max_bytes: int = MAX_BYTES_DEFAULT,
                 transport: httpx.AsyncBaseTransport | None = None, resolver: Callable[[str], list[str]] | None = None, sport: str | None = None):
        self.name, self.url, self.fmt, self.sport = name, url, fmt, sport
        self._allowed, self._timeout, self._max, self._transport, self._resolver = allowed_hosts, timeout_s, max_bytes, transport, resolver

    async def fetch(self, params: dict[str, Any]) -> ProviderResult:
        try:
            check_url(self.url, self._allowed, self._resolver)
        except UnsafeUrl as exc:
            return ProviderResult(self.name, "unavailable", reason=f"source refused: {exc}")
        try:
            async with httpx.AsyncClient(timeout=self._timeout, follow_redirects=False, transport=self._transport or PinnedTransport(resolver=self._resolver), headers={"User-Agent": "DSports-ProductIntelligence/1.0"}) as client:
                async with client.stream("GET", self.url) as resp:
                    if resp.status_code != 200:
                        return ProviderResult(self.name, "error", reason=f"the source answered HTTP {resp.status_code}")
                    body = b""
                    async for chunk in resp.aiter_bytes():
                        body += chunk
                        if len(body) > self._max:
                            return ProviderResult(self.name, "error", reason=f"the response is larger than the {self._max} byte limit")
        except httpx.TimeoutException:
            return ProviderResult(self.name, "error", reason="the source timed out")
        except httpx.HTTPError as exc:
            return ProviderResult(self.name, "error", reason=f"the source could not be reached ({type(exc).__name__})")
        text = body.decode("utf-8", errors="replace")
        try:
            cands = parse_json_feed(text, self.name, self.url) if self.fmt == "json" else parse_jsonld(text, self.name, self.url)
        except ValueError as exc:
            return ProviderResult(self.name, "error", reason=f"the response could not be read: {exc}")
        wanted = [str(k).lower() for k in (params.get("keywords") or [])]
        sport = (params.get("sport") or "").lower()
        if sport:
            cands = [c for c in cands if (c.sport or self.sport or "").lower() in ("", sport)]
        if wanted:
            cands = [c for c in cands if any(k in f"{c.name} {c.brand or ''} {c.category or ''} {c.description or ''}".lower() for k in wanted)]
        for c in cands:
            c.sport = c.sport or self.sport
        return ProviderResult(self.name, "ok", cands)


def build_providers(feeds_json: str | None, allowed_hosts: list[str], timeout_s: float, max_bytes: int) -> tuple[list[FeedProvider], list[str]]:
    """(providers, configuration problems). A malformed entry is reported, not silently dropped."""
    if not feeds_json or not feeds_json.strip():
        return [], []
    try:
        items = json.loads(feeds_json)
        assert isinstance(items, list)
    except (ValueError, AssertionError):
        return [], ["SOURCING_FEEDS is not a JSON list"]
    providers, problems = [], []
    for i, it in enumerate(items):
        if not isinstance(it, dict) or not it.get("name") or not it.get("url") or it.get("format") not in ("json", "jsonld"):
            problems.append(f"feed #{i + 1} needs name, url and format (json or jsonld)")
            continue
        providers.append(FeedProvider(str(it["name"])[:60], str(it["url"]), it["format"], allowed_hosts, timeout_s, max_bytes, sport=it.get("sport")))
    return providers, problems
