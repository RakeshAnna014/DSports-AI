"""Mapping an order's delivery state to a planning region. Geography, not a business fact about demand: it only decides WHICH region an online order is counted in.

The default maps Indian state / union-territory codes to the five regions the Inventory AI already uses (N, S, E, W, C). It is replaceable with `PLANNING_STATE_REGION_MAP`
(JSON {"KA": "S", ...}); a state that is not in the map is counted under the explicit UNKNOWN region, never guessed. Nothing here says which sport a region prefers.
"""
from __future__ import annotations

import json
from typing import Mapping

UNKNOWN_REGION = "UNK"

DEFAULT_STATE_REGION: dict[str, str] = {
    **{s: "N" for s in ("DL", "HR", "PB", "HP", "JK", "LA", "UK", "UT", "CH", "RJ")},
    **{s: "S" for s in ("KA", "TN", "KL", "AP", "TS", "TG", "PY", "LD")},
    **{s: "E" for s in ("WB", "OD", "OR", "BR", "JH", "AS", "SK", "AR", "NL", "MN", "ML", "MZ", "TR")},
    **{s: "W" for s in ("MH", "GJ", "GA", "DD", "DN")},
    **{s: "C" for s in ("MP", "CG", "UP")},
}
STATE_NAMES: dict[str, str] = {
    "DELHI": "DL", "HARYANA": "HR", "PUNJAB": "PB", "HIMACHAL PRADESH": "HP", "JAMMU AND KASHMIR": "JK", "UTTARAKHAND": "UK", "CHANDIGARH": "CH", "RAJASTHAN": "RJ",
    "KARNATAKA": "KA", "TAMIL NADU": "TN", "KERALA": "KL", "ANDHRA PRADESH": "AP", "TELANGANA": "TS", "PUDUCHERRY": "PY",
    "WEST BENGAL": "WB", "ODISHA": "OD", "BIHAR": "BR", "JHARKHAND": "JH", "ASSAM": "AS", "SIKKIM": "SK",
    "MAHARASHTRA": "MH", "GUJARAT": "GJ", "GOA": "GA", "MADHYA PRADESH": "MP", "CHHATTISGARH": "CG", "UTTAR PRADESH": "UP",
}
REGION_NAMES = {"N": "North", "S": "South", "E": "East", "W": "West", "C": "Central", UNKNOWN_REGION: "Unknown region"}


def parse_map(raw: str | None) -> dict[str, str]:
    """The configured map, or the default. A malformed or empty setting is an error at startup, not a silent fall-back to the default."""
    if raw is None or not raw.strip():
        return dict(DEFAULT_STATE_REGION)
    data = json.loads(raw)
    if not isinstance(data, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in data.items()):
        raise ValueError("PLANNING_STATE_REGION_MAP must be a JSON object of state code to region code")
    return {k.strip().upper(): v.strip().upper() for k, v in data.items()}


def region_of(state: str | None, mapping: Mapping[str, str]) -> str:
    if not state or not state.strip():
        return UNKNOWN_REGION
    key = state.strip().upper()
    key = STATE_NAMES.get(key, key)
    return mapping.get(key, UNKNOWN_REGION)


def online_location(region: str) -> str:
    """The id of the ONLINE-channel location of a region: where that region's web orders are counted."""
    return f"ONL-{region}"
