"""Product identity, duplicate detection, catalogue matching and safe URL checks for Product & Supplier Intelligence.

Everything here is deterministic. An uncertain match is reported as POSSIBLE (with the reason) and is never merged automatically: a buyer decides.
"""
from __future__ import annotations

import ipaddress
import re
import socket
import unicodedata
from dataclasses import dataclass
from typing import Any, Callable, Iterable, Sequence
from urllib.parse import urlsplit

NOISE = {"the", "and", "for", "with", "pro", "new", "edition", "series", "model", "set", "of", "pack", "by", "in"}
_WORD = re.compile(r"[a-z0-9]+")


def _ascii(text: str) -> str:
    return unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii").lower()


def tokens(text: str | None, drop_noise: bool = True) -> list[str]:
    out = _WORD.findall(_ascii(text or ""))
    return [t for t in out if not (drop_noise and t in NOISE)]


def normalize_name(text: str | None) -> str:
    return " ".join(tokens(text))


# Words that are part of a company name, not of the brand ("VICTOR India", "Yonex Co., Ltd." -> the brand is Victor / Yonex).
BRAND_NOISE = {"sports", "sport", "co", "ltd", "limited", "inc", "corp", "corporation", "pvt", "private", "india", "global", "official", "international", "company", "llc", "gmbh"}
# Spelling variants of the same brand. A deliberately small, factual list; extend it through SOURCING_BRAND_ALIASES (JSON {"variant": "canonical"}).
BRAND_ALIASES: dict[str, str] = {"li ning": "li-ning", "lining": "li-ning", "li-ning": "li-ning", "victorsport": "victor", "yonex yonex": "yonex", "babolat paris": "babolat",
                                 "head sport": "head", "wilson sporting goods": "wilson", "asics corporation": "asics", "nivia sports": "nivia", "cosco": "cosco"}
BRAND_DISPLAY: dict[str, str] = {"li-ning": "Li-Ning", "victor": "Victor", "yonex": "Yonex", "babolat": "Babolat", "asics": "ASICS", "nivia": "Nivia", "cosco": "Cosco", "wilson": "Wilson", "head": "Head"}


def set_brand_aliases(extra: dict[str, str] | None) -> None:
    for k, v in (extra or {}).items():
        BRAND_ALIASES[" ".join(tokens(k, drop_noise=False))] = " ".join(tokens(v, drop_noise=False))


def normalize_brand(text: str | None) -> str:
    """The canonical comparison key of a brand: lower case, company suffixes removed, known spelling variants merged."""
    toks = [t for t in tokens(text, drop_noise=False) if t not in BRAND_NOISE] or tokens(text, drop_noise=False)
    key = " ".join(toks)
    return BRAND_ALIASES.get(key, key)


def display_brand(text: str | None) -> str | None:
    key = normalize_brand(text)
    if not key:
        return None
    return BRAND_DISPLAY.get(key) or " ".join(w.capitalize() for w in key.split())


def normalize_model(text: str | None) -> str:
    """A model number compares equal across spacing and punctuation: 'AX-88 D' == 'ax88d'."""
    return "".join(_WORD.findall(_ascii(text or "")))


def valid_gtin(value: str | None) -> str | None:
    """A GTIN-8/12/13/14 with a correct check digit, digits only; otherwise None."""
    digits = re.sub(r"\D", "", value or "")
    if len(digits) not in (8, 12, 13, 14):
        return None
    body, check = digits[:-1], int(digits[-1])
    total = sum(int(d) * (3 if i % 2 == 0 else 1) for i, d in enumerate(reversed(body)))
    return digits if (10 - total % 10) % 10 == check else None


def identity_key(name: str | None, brand: str | None = None, model: str | None = None, gtin: str | None = None, sku: str | None = None) -> str:
    """Strongest identifier first: GTIN, then brand+model number, then brand+manufacturer SKU, then brand+name tokens."""
    g = valid_gtin(gtin)
    if g:
        return f"gtin:{g}"
    b = normalize_brand(brand)
    m = normalize_model(model)
    if b and m:
        return f"bm:{b}|{m}"
    s = normalize_model(sku)
    if b and s:
        return f"bs:{b}|{s}"
    return f"name:{b}|{' '.join(sorted(set(tokens(name))))}"


def similarity(a: str | None, b: str | None) -> float:
    ta, tb = set(tokens(a)), set(tokens(b))
    return 0.0 if not ta or not tb else len(ta & tb) / len(ta | tb)


MATCH_LEVELS = ("EXACT", "PROBABLE_DUPLICATE", "SIMILAR", "REVIEW", "NONE")


@dataclass(frozen=True)
class Match:
    level: str                       # EXACT | PROBABLE_DUPLICATE | SIMILAR | REVIEW | NONE
    reason: str
    product_id: str | None = None
    score: float = 0.0
    alternatives: tuple[dict[str, Any], ...] = ()


def _desc(level: str, reason: str, pid: str, score: float, rest: Sequence[tuple[float, str, str]] = ()) -> Match:
    return Match(level, reason, pid, round(score, 2), tuple({"product_id": p, "name": n, "score": round(s, 2)} for s, p, n in rest[:3]))


def match_catalogue(candidate: dict[str, Any], catalogue: Sequence[dict[str, Any]], possible_at: float = 0.5, probable_at: float = 0.8) -> Match:
    """Compare a discovered product with the live catalogue, identifiers first.
      EXACT               same GTIN, or the same brand and model number / manufacturer SKU
      PROBABLE_DUPLICATE  the same brand and a very similar name or model, but no shared identifier: a person confirms before anything is merged
      SIMILAR             a related product (same or similar name), useful as an alternative, not the same product
      REVIEW              the evidence is ambiguous (several equally good candidates, or conflicting identifiers): a person must decide
      NONE                nothing in the catalogue shares an identifier, a model or enough of the name
    Nothing is ever merged automatically."""
    c_gtin, c_brand = valid_gtin(candidate.get("gtin")), normalize_brand(candidate.get("brand"))
    c_ids = {normalize_model(candidate.get(k)) for k in ("model", "sku", "mfr_sku", "model_number") if candidate.get(k)} - {""}
    scored: list[tuple[float, str, str, bool]] = []
    for p in catalogue:
        pid, pname = str(p.get("id") or p.get("product_id") or ""), str(p.get("name") or "")
        p_gtin = valid_gtin(p.get("gtin") or p.get("barcode"))
        if c_gtin and p_gtin:
            if c_gtin == p_gtin:
                return _desc("EXACT", "same GTIN", pid, 1.0)
        p_brand = normalize_brand(p.get("brand") or p.get("brandName"))
        p_ids = {normalize_model(p.get(k)) for k in ("model", "sku") if p.get(k)} - {""}
        same_brand = bool(c_brand and p_brand and c_brand == p_brand)
        if same_brand and c_ids & p_ids:
            return _desc("EXACT", "same brand and model number / SKU", pid, 1.0)
        score = similarity(candidate.get("name"), pname)
        if same_brand:
            score = min(1.0, score + 0.15)                  # a shared brand makes a similar name more credible, never decisive
        # the same model number under a DIFFERENT brand is a conflict, not a match
        conflict = bool(c_ids & p_ids and c_brand and p_brand and c_brand != p_brand)
        scored.append((score, pid, pname, conflict))
    scored.sort(key=lambda r: -r[0])
    if not scored:
        return Match("NONE", "the catalogue is empty or could not be read")
    rest = [(s, p, n) for s, p, n, _ in scored[1:] if s >= possible_at]
    top, pid, name, conflict = scored[0]
    if conflict:
        return _desc("REVIEW", "a catalogue product has the same model number under a different brand", pid, top, [(top, pid, name)] + rest)
    strong = [r for r in scored if r[0] >= probable_at]
    if len(strong) > 1 and strong[0][0] - strong[1][0] < 0.05:
        return _desc("REVIEW", f"{len(strong)} catalogue products match about equally well", pid, top, [(s, p, n) for s, p, n, _ in strong])
    if top >= probable_at:
        return _desc("PROBABLE_DUPLICATE", f"very similar to '{name}' (token overlap {top:.2f}) without a shared identifier", pid, top, rest)
    if top >= possible_at:
        return _desc("SIMILAR", f"similar to '{name}' (token overlap {top:.2f}); a related product, not the same one", pid, top, rest)
    return Match("NONE", "no catalogue product shares an identifier, a model number or enough of the name")


def find_duplicates(candidate: dict[str, Any], existing: Iterable[dict[str, Any]], threshold: float = 0.8) -> tuple[str | None, list[dict[str, Any]]]:
    """(identity key of an identical opportunity or None, near-duplicates that need review). Near duplicates must share the brand when both have one."""
    key = identity_key(candidate.get("name"), candidate.get("brand"), candidate.get("model"), candidate.get("gtin"), candidate.get("sku"))
    possible: list[dict[str, Any]] = []
    c_brand = normalize_brand(candidate.get("brand"))
    for e in existing:
        if e.get("identity_key") == key:
            return key, []
        e_brand = normalize_brand(e.get("brand"))
        if c_brand and e_brand and c_brand != e_brand:
            continue
        s = similarity(candidate.get("name"), e.get("name"))
        if s >= threshold:
            possible.append({"id": str(e.get("id")), "name": e.get("name"), "score": round(s, 2)})
    return None, possible


# ------------------------------------------------------------------------------------------------ safe retrieval of external URLs
class UnsafeUrl(ValueError):
    pass


def _blocked(ip: ipaddress._BaseAddress) -> bool:
    return bool(ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved or ip.is_unspecified or getattr(ip, "is_site_local", False))


def check_url(url: str, allowed_hosts: Sequence[str], resolver: Callable[[str], list[str]] | None = None) -> str:
    """Return the URL if it may be fetched. Only http(s), no credentials, only configured hosts, standard ports, and no private/loopback/link-local address after DNS
    resolution (SSRF). The resolved addresses are checked here; a fetcher must connect to a checked address or re-check on redirect (redirects are not followed)."""
    parts = urlsplit(url.strip())
    if parts.scheme not in ("http", "https"):
        raise UnsafeUrl("only http and https URLs are allowed")
    if "@" in parts.netloc or parts.username or parts.password:       # any user-info, even an empty one, is a smuggling vector (http://good.example@evil.example/)
        raise UnsafeUrl("URLs with embedded credentials are not allowed")
    host = (parts.hostname or "").lower()
    if not host:
        raise UnsafeUrl("the URL has no host")
    if parts.port not in (None, 80, 443):
        raise UnsafeUrl("only the standard ports 80 and 443 are allowed")
    allowed = {h.lower().lstrip(".") for h in allowed_hosts if h.strip()}
    if not any(host == a or host.endswith("." + a) for a in allowed):
        raise UnsafeUrl("the host is not in the configured allow-list")
    resolve_public(host, resolver)
    return url.strip()


def resolve_public(host: str, resolver: Callable[[str], list[str]] | None = None) -> list[str]:
    """Resolve `host` ONCE and return its addresses only if every one is public; the caller must then connect to one of these exact addresses (not to the name again)."""
    try:
        literal = ipaddress.ip_address(host)
        addresses = [str(literal)]
    except ValueError:
        resolve = resolver or (lambda h: sorted({i[4][0] for i in socket.getaddrinfo(h, None)}))
        try:
            addresses = list(resolve(host))
        except OSError as exc:
            raise UnsafeUrl("the host could not be resolved") from exc
    if not addresses:
        raise UnsafeUrl("the host did not resolve")
    for a in addresses:
        ip = ipaddress.ip_address(a)
        if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:      # ::ffff:127.0.0.1 is 127.0.0.1
            ip = ip.ipv4_mapped
        if _blocked(ip):
            raise UnsafeUrl("the host resolves to a private or reserved address")
    return addresses