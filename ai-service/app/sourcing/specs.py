"""Category-specific product specifications.

Two jobs, both evidence-only:
  1. canonicalise the spec labels a source page uses ("Weight / Grip Size", "Frame Material") so different pages can be compared;
  2. say which specifications a category needs before a catalogue proposal can be called ready, and report the others as MISSING information.
A specification is never inferred or generated: a value exists only if a source page stated it (and the page URL is kept with the product).
"""
from __future__ import annotations

import re
from typing import Any, Iterable

# canonical key -> label patterns (lower-case regex fragments)
CANONICAL: dict[str, tuple[str, ...]] = {
    "weight": (r"^weight\b", r"\bweight\s*/", r"^weight/grip", r"weight class"),
    "grip_size": (r"grip size", r"grip\s*$", r"/\s*grip"),
    "balance": (r"balance",),
    "stiffness": (r"stiffness", r"flex"),
    "length": (r"^length", r"overall length"),
    "frame_material": (r"frame material", r"^frame$", r"head material"),
    "shaft_material": (r"shaft material", r"^shaft$"),
    "material": (r"^material", r"upper material", r"outer material", r"fabric", r"composition"),
    "string_tension": (r"string tension", r"tension"),
    "player_level": (r"level", r"player type", r"skill"),
    "sizes": (r"^sizes?\b", r"size range", r"available sizes"),
    "colour": (r"colou?r",),
    "speed": (r"^speed", r"flight speed"),
    "quantity": (r"quantity", r"per tube", r"pieces", r"pack size"),
    "sport": (r"^sport",),
    "surface": (r"surface", r"court"),
    "cushioning": (r"cushion",),
    "dimensions": (r"dimension", r"^size\b"),
    "weight_capacity": (r"weight capacity", r"max(imum)? load", r"user weight"),
    "certification": (r"certif", r"standard", r"\bce\b", r"\bbwf\b", r"\bbis\b"),
}

# category family -> the specifications a catalogue entry should carry (any key inside a tuple satisfies that requirement)
REQUIRED: dict[str, tuple[tuple[str, ...], ...]] = {
    "racket": (("weight",), ("frame_material", "material"), ("length", "balance", "stiffness")),
    "shuttlecock": (("speed", "quantity", "material"),),
    "shoes": (("sizes",), ("material", "cushioning", "surface")),
    "bag": (("material", "dimensions"),),
    "apparel": (("sizes",), ("material",)),
    "ball": (("material", "sizes", "dimensions"),),
    "fitness": (("weight", "dimensions", "weight_capacity"), ("material",)),
    "protective": (("sizes",), ("certification", "material")),
}

FAMILY_WORDS: dict[str, tuple[str, ...]] = {
    "racket": ("racket", "racquet", "bat"),
    "shuttlecock": ("shuttle", "birdie"),
    "shoes": ("shoe", "footwear", "sneaker", "boot"),
    "bag": ("bag", "backpack", "kitbag"),
    "apparel": ("shirt", "jersey", "shorts", "apparel", "t-shirt", "jacket", "skirt"),
    "ball": ("ball",),
    "fitness": ("dumbbell", "kettlebell", "mat", "band", "treadmill", "rope", "roller"),
    "protective": ("helmet", "guard", "pad", "glove", "protector"),
}


def canonical_key(label: str) -> str | None:
    low = label.lower().strip()
    for key, patterns in CANONICAL.items():
        if any(re.search(p, low) for p in patterns):
            return key
    return None


def family_of(*texts: str | None) -> str | None:
    blob = " ".join(t.lower() for t in texts if t)
    for family, words in FAMILY_WORDS.items():
        if any(re.search(rf"\b{re.escape(w)}", blob) for w in words):
            return family
    return None


def canonicalise(pairs: Iterable[tuple[str, str]]) -> dict[str, dict[str, str]]:
    """{canonical_key or the source label: {"label": source label, "value": value}}; the first occurrence of a canonical key wins."""
    out: dict[str, dict[str, str]] = {}
    for label, value in pairs:
        if not label or not value:
            continue
        key = canonical_key(label) or re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_")[:40]
        out.setdefault(key, {"label": label[:80], "value": value[:400]})
    return out


def flat(specs: dict[str, Any]) -> dict[str, str]:
    """Specs as stored are {key: {"label", "value"}} (or legacy {label: value}); a flat {label: value} view for display."""
    out: dict[str, str] = {}
    for k, v in (specs or {}).items():
        if isinstance(v, dict):
            out[v.get("label") or k] = str(v.get("value", ""))
        else:
            out[k] = str(v)
    return out


def missing_specs(family: str | None, specs: dict[str, Any]) -> list[str]:
    """Human-readable names of required specifications the source evidence does not provide (empty when the category has no defined requirements)."""
    if not family or family not in REQUIRED:
        return []
    have = {k for k, v in (specs or {}).items() if (v.get("value") if isinstance(v, dict) else v)}
    return [" or ".join(g).replace("_", " ") for g in REQUIRED[family] if not (set(g) & have)]
