"""Unmet customer demand signals: a search through the shopping assistant that the catalogue could not really satisfy.

The assistant's search is semantic, so it almost always returns SOMETHING related (table-tennis bats for "pickleball paddles"). A zero-row count is therefore not the signal. A search counts as unmet when
  * it returned no product at all, or
  * it names a distinctive term (a product word such as "pickleball") that appears in none of the returned products AND nowhere in the catalogue vocabulary
    (product names, brands, categories and sports of the live catalogue).
Generic wording (light, best, beginner, carbon, ...) never counts as a missing product. The rule is deterministic, so a buyer can see exactly why a phrase was recorded.

Privacy: only a salted customer hash (already used for the audit log), a redacted search phrase and the result count are kept. The phrase has e-mail addresses, URLs, phone-like and other digit
runs removed and is cut to 80 characters; no conversation text and no customer identity is stored. Guests (no token) are not recorded. A search is customer INTEREST, not purchase intent,
and the buyer UI says so.
"""
from __future__ import annotations

import logging
import re
from typing import Any, Iterable

log = logging.getLogger(__name__)
_EMAIL = re.compile(r"\S+@\S+")
_URL = re.compile(r"https?://\S+|www\.\S+", re.I)
_DIGITS = re.compile(r"\d{3,}")
_JUNK = re.compile(r"[^a-z0-9\-\s]")
STOP = {"i", "want", "need", "looking", "for", "a", "an", "the", "some", "please", "show", "me", "find", "buy", "get", "do", "you", "have", "any", "is", "there", "can", "im", "my", "to", "of", "in",
        "sell", "stock", "available", "does", "your", "shop", "store", "are", "with", "and", "on", "at", "it", "this", "that", "would", "like", "good", "recommend", "suggest"}
GENERIC = {"light", "lightweight", "heavy", "best", "cheap", "cheaper", "budget", "premium", "professional", "pro", "beginner", "beginners", "junior", "kids", "adult", "adults", "carbon", "durable",
           "strong", "soft", "hard", "small", "large", "medium", "mens", "womens", "men", "women", "boys", "girls", "black", "white", "blue", "red", "green", "yellow", "new", "latest", "top", "quality",
           "affordable", "expensive", "comfortable", "waterproof", "training", "practice", "match", "indoor", "outdoor", "starter", "advanced", "intermediate", "set", "pack", "pair", "size"}


def redact_query(text: str | None) -> str | None:
    """A short, lower-case product phrase, or None when nothing meaningful is left."""
    if not text:
        return None
    t = _URL.sub(" ", _EMAIL.sub(" ", text.lower()))
    t = _JUNK.sub(" ", _DIGITS.sub(" ", t))
    words = [w for w in t.split() if w not in STOP and len(w) > 1]
    phrase = " ".join(words)[:80].strip()
    return phrase if len(phrase.replace(" ", "")) >= 4 else None


_REQUEST = re.compile(r"^\W*(?:please\s+)?(?:do you (?:sell|have|stock|carry)|is there|are there|show me|show|find me|find|i (?:need|want|am looking for|'m looking for|would like|'d like)|looking for|get me|any\b|where can i (?:buy|find))\b", re.I)


def _looks_like_a_product_request(message: str | None) -> bool:
    """A follow-up such as 'which one is lighter' or 'what about the price' must never be read as a missing product."""
    return bool(message and _REQUEST.search(message.strip()))


def _stem(w: str) -> str:
    for suffix in ("ies", "es", "s"):
        if w.endswith(suffix) and len(w) - len(suffix) >= 4:
            return w[: -len(suffix)] + ("y" if suffix == "ies" else "")
    return w


def distinctive_terms(phrase: str) -> list[str]:
    return [w for w in phrase.split() if len(w) >= 5 and w not in GENERIC and not w.isdigit()]


def unmatched_terms(phrase: str, products: Iterable[dict[str, Any]], vocabulary: Iterable[str]) -> list[str]:
    """Distinctive terms of the phrase found neither in the returned products nor in the catalogue vocabulary."""
    shown = " ".join(str(v).lower() for p in products for v in p.values() if isinstance(v, (str, int, float)))
    vocab = " ".join(vocabulary).lower()
    return [t for t in distinctive_terms(phrase) if _stem(t) not in shown and _stem(t) not in vocab]


async def _vocabulary(sourcing: Any) -> list[str]:
    rows = await sourcing.catalogue_rows()
    return [str(r.get(k) or "") for r in rows for k in ("name", "brand", "category", "sport")]


async def record_search_outcome(services: Any, principal: Any, slots: dict[str, Any], products: list[dict[str, Any]], message: str | None = None) -> None:
    """Called by the shopping flow after a search. Never raises: measuring demand must not break an answer.
    The extracted `query` slot is used when there is one; the extractor sometimes keeps only a category, so the customer's own message (redacted the same way) is the fallback."""
    try:
        sourcing = getattr(services, "sourcing", None)
        if sourcing is None or principal is None:
            return
        if any(slots.get(k) is not None for k in ("max_price", "min_price", "skill_level", "weight_pref", "age_group")):
            return                      # a filter emptied or narrowed the list: that says the filter was tight, not that the product is missing from the catalogue
        phrase = redact_query(slots.get("query") or (message if _looks_like_a_product_request(message) else None))
        if phrase is None:
            return
        if not products:
            kind, terms = "ZERO_RESULTS", distinctive_terms(phrase)
        else:
            if not distinctive_terms(phrase):
                return
            terms = unmatched_terms(phrase, products, await _vocabulary(sourcing))
            if not terms:
                return
            kind = "LOW_RESULTS"
        key = max(terms, key=lambda t: (len(t), t)) if terms else None            # the longest unknown term identifies the request across wordings
        await sourcing.repo.record_demand(principal.customer_hash, phrase, slots.get("sport"), len(products), kind, key)
    except Exception:  # noqa: BLE001
        log.info("could not record a demand signal", exc_info=True)
