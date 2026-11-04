"""Numeric grounding for inventory explanations: every number the model writes must be present in the facts it was given.

The model may phrase and explain; it may not calculate. A number is accepted when it equals a number in the facts after rounding
to the precision the model used (so "4.2 days" matches 4.17, "INR 1,25,000" matches 125000, "30%" matches 30 or 30.0). Anything
else (a sum the model made up, a percentage it derived, a date it invented) is rejected and the deterministic text is used.
"""
from __future__ import annotations

import json
import math
import re
from datetime import date
from typing import Any

_NUM = re.compile(r"(?<![\w.])\d[\d,]*(?:\.\d+)?")
_LIST_MARKER = re.compile(r"(?m)^\s*(?:[-*]\s+)?\d{1,2}[.)]\s+")


def _numbers(text: str) -> list[float]:
    out = []
    for token in _NUM.findall(text):
        token = token.replace(",", "")
        try:
            out.append(float(token))
        except ValueError:
            continue
    return out


def facts_numbers(facts: Any) -> set[float]:
    """All numbers anywhere in the facts, including inside dates (2026-10-12 -> 2026, 10, 12) and strings."""
    blob = json.dumps(facts, default=str)
    values = set(_numbers(blob))
    values.update(float(x) for x in re.findall(r"\d+(?:\.\d+)?", blob))       # digits inside field names (next_30_days, forecast_90) are facts: the horizons
    for ds in re.findall(r"\d{4}-\d{2}-\d{2}", blob):
        try:
            d = date.fromisoformat(ds)
        except ValueError:
            continue
        values.update({float(d.day), float(d.month), float(d.year)})
    return values


_WORDS = {"two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
          "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19, "twenty": 20,
          "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90, "hundred": 100, "thousand": 1000,
          "million": 1_000_000, "lakh": 100_000, "crore": 10_000_000, "dozen": 12, "half": 0.5}
_WORD_RE = re.compile(r"\b(" + "|".join(_WORDS) + r")\b", re.I)


def _matches(fact: float, written: float, decimals: int) -> bool:
    """`written` is what the model wrote at `decimals` places. It is the fact if rounding OR truncating the fact to that precision gives it:
    4.17 -> 4.2 or 4.1 (and 4), 3,433,499.69 -> 3,433,500 or 3,433,499. Never 5 for 4.17."""
    scale = 10 ** decimals
    target = round(written * scale)
    return round(fact * scale) == target or math.trunc(fact * scale) == target


def _decimals(token: str) -> int:
    return len(token.split(".")[1]) if "." in token else 0


def ungrounded_numbers(text: str, facts: Any, *, allow_small_ints: bool = False) -> list[str]:
    """Numbers in `text` not found in `facts`. Month-day mentions such as "12 October" are covered by the date expansion above."""
    allowed = facts_numbers(facts)
    cleaned = _LIST_MARKER.sub("", text)
    bad: list[str] = []
    for raw in _NUM.findall(cleaned):
        token = raw.replace(",", "")
        value = float(token)
        d = _decimals(token)
        if any(_matches(a, value, d) for a in allowed):
            continue
        if allow_small_ints and d == 0 and value <= 3:
            continue
        bad.append(raw)
    for word in _WORD_RE.findall(cleaned):          # "eight products" is a count the model made itself unless the facts contain 8
        value = float(_WORDS[word.lower()])
        if word.lower() in ("lakh", "crore", "million", "half", "dozen"):
            bad.append(word)                         # unit words that rescale a number are never allowed (the facts use plain digits)
        elif not any(a == value for a in allowed):
            bad.append(word)
    return bad


_REC_SENTENCE = re.compile(r"[^.\n]*\brecommend\w*[^.\n]*", re.I)


def recommendation_consistent(text: str, recommended_pct: float | None) -> bool:
    """The engine's recommended uniform discount is the ONLY recommendation the text may state. A sentence that recommends a discount
    percentage different from it (or any percentage when the engine recommends none) is rejected."""
    for sentence in _REC_SENTENCE.findall(text):
        if re.search(r"\bno (single |uniform )?discount\b|\bnot recommend|\bkeep the current price", sentence, re.I):
            continue
        for pct in re.findall(r"(\d+(?:\.\d+)?)\s*%\s*(?:discount|off)|(?:discount|off)\s*(?:of\s*)?(\d+(?:\.\d+)?)\s*%", sentence, re.I):
            value = float(pct[0] or pct[1])
            if recommended_pct is None or abs(value - recommended_pct) > 1e-9:
                return False
    return True


def is_grounded(text: str, facts: Any) -> tuple[bool, list[str]]:
    bad = ungrounded_numbers(text, facts)
    return (not bad, bad)
